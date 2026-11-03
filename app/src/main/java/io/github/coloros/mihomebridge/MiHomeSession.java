package io.github.coloros.mihomebridge;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.controls.Control;
import android.service.controls.actions.BooleanAction;
import android.service.controls.actions.ControlAction;
import android.service.controls.actions.FloatAction;
import android.service.controls.templates.ControlTemplate;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Independent provider subscription, bounded recovery, and per-user last-known state. */
final class MiHomeSession {
    static final ComponentName SERVICE = new ComponentName(BridgeContract.MIHOME, BridgeContract.SERVICE);
    record Entry(String id, String title, String subtitle, int type, Object info, Control control,
                 boolean pending, String message, boolean stale) {}
    private record Pending(Object marker, Boolean expectedToggle, Float expectedValue) {}
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BooleanSupplier enabled;
    private final Supplier<Object> component;
    private final BiConsumer<String, Throwable> failure;
    private final Supplier<Set<String>> hidden;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Object> favorites = new LinkedHashMap<>();
    private final Map<String, Control> controls = new LinkedHashMap<>();
    private final Set<String> fresh = new HashSet<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, String> messages = new LinkedHashMap<>();
    private final ReconnectPolicy retries = new ReconnectPolicy();
    private volatile List<Entry> entries = List.of();
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean visible;
    private Constructor<?> bindingConstructor;
    private volatile Object[] bindingSeed;
    private volatile Object callback;
    private volatile Object binding;
    private Executor executor;
    private Context context;
    private Class<?> controllerType, infoType, structureType;
    private int userId = -1;
    private boolean authoritative;
    private int selectedCount;
    private String phase = "idle", lastError = "";
    private long lastUpdate;
    private Runnable retryTask, initialTimeout;

    MiHomeSession(BooleanSupplier enabled, Supplier<Object> component, BiConsumer<String, Throwable> failure, Supplier<Set<String>> hidden) {
        this.enabled = enabled; this.component = component; this.failure = failure; this.hidden = hidden;
    }
    void addListener(Runnable listener) { listeners.add(listener); }
    void capture(Constructor<?> constructor, Object[] args) {
        if (bindingSeed != null) return;
        bindingConstructor = constructor; context = (Context) args[0]; executor = (Executor) args[1];
        bindingSeed = args.clone();
        main.post(() -> { if (visible && binding == null && enabled.getAsBoolean()) start(); });
    }
    void prepare(ClassLoader loader) throws Exception {
        controllerType = Class.forName("com.android.systemui.controls.controller.ControlsController", false, loader);
        infoType = Class.forName("com.android.systemui.controls.controller.ControlInfo", false, loader);
        structureType = Class.forName("com.android.systemui.controls.controller.StructureInfo", false, loader);
        structureType.getConstructor(ComponentName.class, CharSequence.class, List.class);
    }
    List<Entry> entries() { return enabled.getAsBoolean() ? entries : List.of(); }
    Icon icon(String id) {
        for (Entry entry : entries()) if (entry.id.equals(id) && entry.control != null) return entry.control.getCustomIcon();
        return null;
    }
    boolean isVisible() { return visible; }
    boolean isReady() { return bindingSeed != null; }
    boolean authoritative() { return authoritative; }
    long lastUpdate() { return lastUpdate; }
    String phase() { return enabled.getAsBoolean() ? phase : "disabled"; }
    boolean ownsCallback(Object candidate) { return candidate != null && candidate == callback; }
    Object currentBinding() { return binding; }
    Object currentCallback() { return callback; }
    int currentUserId() {
        try { Object owner = controller(); return owner == null ? -1 : (Integer) HostReflect.get(owner, "getCurrentUserId"); }
        catch (Exception error) { return -1; }
    }
    void status(Bundle result) {
        result.putString("connection", phase()); result.putString("connection_error", lastError);
        result.putInt("retry_count", retries.attempts()); result.putLong("last_update", lastUpdate);
        result.putInt("fresh_devices", fresh.size()); result.putBoolean("authoritative", authoritative);
        result.putInt("selected_devices", selectedCount);
    }
    PendingIntent detailsIntent(String id) {
        if (!enabled.getAsBoolean() || !visible || context == null || !sameUser() || !favorites.containsKey(id) || locked()) return null;
        Control control = controls.get(id);
        if (control == null) return null;
        PendingIntent intent = control.getAppIntent();
        return BridgeContract.MIHOME.equals(intent.getCreatorPackage()) && intent.getCreatorUid() / 100000 == userId ? intent : null;
    }
    void setVisible(boolean value) {
        main.post(() -> {
            if (visible == value) return;
            visible = value;
            if (value) { retries.reset(); start(); }
            else { disconnect(); phase = "paused"; publish(); }
        });
    }
    void invalidate() { generation.incrementAndGet(); entries = List.of(); }
    void reset() {
        invalidate();
        main.post(() -> {
            disconnect(); favorites.clear(); controls.clear(); messages.clear(); authoritative = false; selectedCount = 0; lastUpdate = 0;
            retries.reset(); phase = enabled.getAsBoolean() ? "idle" : "disabled"; publish();
            if (visible && enabled.getAsBoolean()) start();
        });
    }
    void retry() { main.post(() -> { if (visible && enabled.getAsBoolean()) { retries.reset(); start(); } }); }
    void connectionLost(Object owner, String reason) {
        if (!ownsCallback(owner)) return;
        int epoch = generation.get();
        main.post(() -> { if (epoch == generation.get() && ownsCallback(owner)) recover(epoch, reason); });
    }
    private Object controller() throws Exception {
        Object source = component.get();
        return source == null ? null : ((Optional<?>) HostReflect.get(source, "getControlsController")).orElse(null);
    }
    private boolean sameUser() { return userId >= 0 && userId == currentUserId(); }
    private boolean locked() {
        KeyguardManager keyguard = context == null ? null : context.getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked();
    }
    private void start() {
        disconnect();
        if (!visible || !enabled.getAsBoolean()) return;
        int epoch = generation.get();
        phase = retries.attempts() == 0 ? "connecting" : "retrying";
        try {
            Object owner = controller();
            if (owner == null || bindingSeed == null) { recover(epoch, "系统设备服务尚未就绪"); return; }
            int current = (Integer) HostReflect.get(owner, "getCurrentUserId");
            if (userId != current) {
                favorites.clear(); controls.clear(); messages.clear(); authoritative = false; selectedCount = 0; lastUpdate = 0; userId = current;
            }
            if ((Integer) HostReflect.get(bindingSeed[4], "getUserId") != userId) { recover(epoch, "正在切换用户"); return; }
            Map<String, Object> next = readFavorites(owner);
            selectedCount = next.size(); next.keySet().removeAll(hidden.get());
            favorites.clear(); favorites.putAll(next); controls.keySet().retainAll(next.keySet()); messages.keySet().retainAll(next.keySet());
            authoritative = true; publish();
            if (favorites.isEmpty()) { phase = "empty"; retries.reset(); lastError = ""; publish(); return; }
            Object ownCallback = Proxy.newProxyInstance(controllerType.getClassLoader(), new Class<?>[]{controllerType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return proxyObject(proxy, method.getName(), args);
                if (method.getName().equals("refreshStatus") && SERVICE.equals(args[0]) && args[1] instanceof Control control)
                    main.post(() -> received(epoch, control));
                else if (method.getName().equals("onActionResponse") && SERVICE.equals(args[0])) {
                    String id = (String) args[1]; int response = (Integer) args[2]; main.post(() -> response(epoch, id, response));
                } else if (method.getName().equals("getCurrentUserId")) return userId;
                return null;
            });
            callback = ownCallback;
            Object[] args = bindingSeed.clone();
            Class<?> lazyType = bindingConstructor.getParameterTypes()[2];
            args[2] = Proxy.newProxyInstance(lazyType.getClassLoader(), new Class<?>[]{lazyType}, (proxy, method, values) ->
                    method.getName().equals("get") ? ownCallback : proxyObject(proxy, method.getName(), values));
            Object ownBinding = bindingConstructor.newInstance(args); binding = ownBinding;
            Object structure = structureType.getConstructor(ComponentName.class, CharSequence.class, List.class)
                    .newInstance(SERVICE, "米家", new ArrayList<>(favorites.values()));
            executor.execute(() -> {
                try { if (epoch == generation.get()) HostReflect.call(ownBinding, "subscribe", new Class<?>[]{structureType}, structure); }
                catch (Exception error) { failure.accept("MiHome subscribe failed", error); main.post(() -> recover(epoch, "米家设备服务连接失败")); }
            });
            initialTimeout = () -> { if (valid(epoch) && fresh.size() < favorites.size()) recover(epoch, "部分设备暂未响应"); };
            main.postDelayed(initialTimeout, ReconnectPolicy.INITIAL_TIMEOUT_MS);
        } catch (Exception error) { failure.accept("Cannot start MiHome subscription", error); recover(epoch, "米家设备服务连接失败"); }
    }
    private Map<String, Object> readFavorites(Object owner) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object structure : (List<?>) HostReflect.call(owner, "getFavoritesForComponent", new Class<?>[]{ComponentName.class}, SERVICE))
            for (Object info : (List<?>) HostReflect.get(structure, "getControls")) result.putIfAbsent((String) HostReflect.get(info, "getControlId"), info);
        return result;
    }
    List<Entry> managementEntries() throws Exception {
        Object owner = controller();
        if (owner == null) throw new IllegalStateException("系统设备服务尚未就绪");
        int current = (Integer) HostReflect.get(owner, "getCurrentUserId");
        List<Entry> result = new ArrayList<>();
        for (var item : readFavorites(owner).entrySet()) {
            Object info = item.getValue();
            result.add(new Entry(item.getKey(), HostReflect.get(info, "getControlTitle").toString(),
                    HostReflect.get(info, "getControlSubtitle").toString(), (Integer) HostReflect.get(info, "getDeviceType"),
                    info, current == userId ? controls.get(item.getKey()) : null, false, null, true));
        }
        return result;
    }
    private void received(int epoch, Control control) {
        if (!valid(epoch) || !favorites.containsKey(control.getControlId())) return;
        String id = control.getControlId(); controls.put(id, control); fresh.add(id); lastUpdate = SystemClock.elapsedRealtime();
        Pending waiting = pending.get(id);
        if (waiting != null) {
            ControlCapabilities capability = new ControlCapabilities(control);
            boolean confirmed = waiting.expectedToggle != null && capability.hasToggle()
                    && DeviceSpacePolicy.confirmsAction(waiting.expectedToggle, capability.checked(), control.getStatus() == Control.STATUS_OK);
            confirmed |= waiting.expectedValue != null && capability.range != null && control.getStatus() == Control.STATUS_OK
                    && ControlValuePolicy.confirms(waiting.expectedValue, capability.range.getCurrentValue(), capability.range.getStepValue());
            if (confirmed) pending.remove(id);
        }
        if (!pending.containsKey(id)) messages.remove(id);
        if (fresh.size() == favorites.size()) {
            phase = "connected"; retries.reset(); lastError = "";
            if (initialTimeout != null) main.removeCallbacks(initialTimeout);
        }
        publish();
    }
    private void recover(int epoch, String reason) {
        if (epoch != generation.get() || !visible || !enabled.getAsBoolean()) return;
        disconnect(); lastError = reason;
        long delay = retries.nextDelay();
        phase = delay < 0 ? "failed" : "retrying"; publish();
        if (delay < 0) return;
        int retryEpoch = generation.get();
        retryTask = () -> { if (retryEpoch == generation.get() && visible && enabled.getAsBoolean()) start(); };
        main.postDelayed(retryTask, delay);
    }
    private boolean valid(int epoch) { return ReconnectPolicy.current(epoch, generation.get(), visible, enabled.getAsBoolean(), sameUser()); }
    private void disconnect() {
        generation.incrementAndGet();
        if (retryTask != null) main.removeCallbacks(retryTask);
        if (initialTimeout != null) main.removeCallbacks(initialTimeout);
        Object old = binding; binding = null; callback = null; fresh.clear(); pending.clear();
        if (old != null && executor != null) executor.execute(() -> {
            try { HostReflect.get(old, "unbind"); } catch (Exception error) { failure.accept("MiHome unbind failed", error); }
        });
    }
    void click(String id, boolean details) {
        if (details) { PendingIntent intent = detailsIntent(id); if (intent != null) openDetails(intent); else toast("暂时无法打开米家详情，请稍后重试"); return; }
        Control control = controls.get(id);
        if (control == null) { retry(); toast("正在连接米家"); return; }
        ControlCapabilities capability = new ControlCapabilities(control);
        if (!capability.controllable()) { click(id, true); return; }
        if (!capability.hasToggle() && !capability.stateless()) { click(id, true); return; }
        try { perform(id, capability.hasToggle() ? "toggle" : capability.stateless() ? "command" : "range", !capability.checked(),
                capability.range == null ? 0 : capability.range.getCurrentValue()); }
        catch (IllegalStateException | IllegalArgumentException error) { toast(error.getMessage()); }
    }
    void perform(String id, String operation, boolean value, float number) {
        if (!enabled.getAsBoolean() || !visible || !sameUser() || locked()) throw new IllegalStateException("请先解锁手机");
        if (hidden.get().contains(id)) throw new IllegalStateException("此设备已隐藏");
        Control control = controls.get(id);
        if (control == null || !fresh.contains(id) || binding == null) throw new IllegalStateException("正在重新连接米家，请稍后重试");
        if (control.getStatus() != Control.STATUS_OK) throw new IllegalStateException("设备离线或不可用");
        if (pending.containsKey(id)) throw new IllegalStateException("上一次操作尚未完成");
        ControlCapabilities capability = new ControlCapabilities(control);
        ControlAction action = capability.action(operation, value, number);
        Object marker = new Object();
        pending.put(id, new Pending(marker, action instanceof BooleanAction toggle ? toggle.getNewState() : null,
                action instanceof FloatAction range ? range.getNewValue() : null));
        messages.remove(id); publish();
        int epoch = generation.get(); Object ownBinding = binding, info = favorites.get(id);
        executor.execute(() -> {
            try {
                if (valid(epoch) && !locked()) HostReflect.call(ownBinding, "action",
                        new Class<?>[]{ComponentName.class, infoType, ControlAction.class}, SERVICE, info, action);
            } catch (Exception error) {
                failure.accept("MiHome action failed", error);
                main.post(() -> { if (valid(epoch)) { pending.remove(id); messages.put(id, "操作发送失败，请重试"); publish(); } });
            }
        });
        main.postDelayed(() -> {
            Pending waiting = pending.get(id);
            if (valid(epoch) && waiting != null && waiting.marker == marker) {
                pending.remove(id); messages.put(id, "操作超时，请确认设备状态后再试"); publish();
            }
        }, 8000);
    }
    private void response(int epoch, String id, int response) {
        if (!valid(epoch) || !pending.containsKey(id)) return;
        if (response == ControlAction.RESPONSE_OK) {
            Control current = controls.get(id);
            if (current != null && new ControlCapabilities(current).stateless()) { pending.remove(id); messages.put(id, "已发送"); publish(); }
            return;
        }
        pending.remove(id); messages.put(id, response >= ControlAction.RESPONSE_CHALLENGE_ACK ? "请在米家中确认操作" : "操作失败，请重试"); publish();
    }
    private void openDetails(PendingIntent intent) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
            intent.send(context, 0, null, null, null, null, options.toBundle()); collapsePanel();
        } catch (Exception error) { failure.accept("MiHome detail launch failed", error); toast("无法打开详情，请打开米家重试"); }
    }
    @SuppressLint("WrongConstant") private void collapsePanel() {
        try { HostReflect.get(context.getSystemService("statusbar"), "collapsePanels"); }
        catch (Exception error) { failure.accept("Cannot collapse Device Space", error); }
    }
    static ControlTemplate unwrap(ControlTemplate template) { return ControlCapabilities.unwrap(template); }
    static boolean isOn(Control control) { ControlCapabilities value = new ControlCapabilities(control); return value.hasToggle() ? value.checked() : control.getStatus() == Control.STATUS_OK; }
    private void publish() {
        List<Entry> next = new ArrayList<>();
        try {
            for (var item : favorites.entrySet()) {
                Object info = item.getValue();
                next.add(new Entry(item.getKey(), HostReflect.get(info, "getControlTitle").toString(),
                        HostReflect.get(info, "getControlSubtitle").toString(), (Integer) HostReflect.get(info, "getDeviceType"),
                        info, controls.get(item.getKey()), pending.containsKey(item.getKey()),
                        !fresh.contains(item.getKey()) && phase.equals("failed") ? "连接中断，请重试" : messages.get(item.getKey()), !fresh.contains(item.getKey())));
            }
            entries = List.copyOf(next);
            for (Runnable listener : listeners) try { listener.run(); } catch (RuntimeException error) { failure.accept("MiHome state listener failed", error); }
        } catch (Exception error) { failure.accept("Cannot compose MiHome state", error); }
    }
    private void toast(String text) { if (context != null) Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }
    private static Object proxyObject(Object proxy, String name, Object[] args) {
        return switch (name) { case "toString" -> "MiHomeDeviceSpaceCallback"; case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0]; default -> null; };
    }
}
