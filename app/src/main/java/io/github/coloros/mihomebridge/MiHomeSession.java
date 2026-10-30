package io.github.coloros.mihomebridge;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.graphics.drawable.Icon;
import android.service.controls.Control;
import android.service.controls.actions.BooleanAction;
import android.service.controls.actions.CommandAction;
import android.service.controls.actions.ControlAction;
import android.service.controls.templates.ControlTemplate;
import android.service.controls.templates.StatelessTemplate;
import android.service.controls.templates.TemperatureControlTemplate;
import android.service.controls.templates.ToggleRangeTemplate;
import android.service.controls.templates.ToggleTemplate;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Own subscription, separate from the full-screen Controls UI's binding controller. */
final class MiHomeSession {
    static final ComponentName SERVICE = new ComponentName(BridgeContract.MIHOME, BridgeContract.SERVICE);
    record Entry(String id, String title, String subtitle, int type, Object info, Control control,
                 boolean pending, String message) {}
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BooleanSupplier enabled;
    private final Supplier<Object> component;
    private final BiConsumer<String, Throwable> failure;
    private final Runnable changed;
    private final Map<String, Object> favorites = new LinkedHashMap<>();
    private final Map<String, Control> controls = new LinkedHashMap<>();
    private record Pending(Object marker, Boolean expected) {}
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, String> messages = new LinkedHashMap<>();
    private volatile List<Entry> entries = List.of();
    private volatile int generation;
    private volatile boolean visible;
    private Constructor<?> bindingConstructor;
    private volatile Object[] bindingSeed;
    private Object binding;
    private Executor executor;
    private Context context;
    private Class<?> controllerType, infoType, structureType;
    private int userId = -1;

    MiHomeSession(BooleanSupplier enabled, Supplier<Object> component,
                  BiConsumer<String, Throwable> failure, Runnable changed) {
        this.enabled = enabled; this.component = component; this.failure = failure; this.changed = changed;
    }

    void capture(Constructor<?> constructor, Object[] args) {
        // A second instance is created by this session; retain only the system's original dependencies.
        if (bindingSeed != null) return;
        bindingConstructor = constructor;
        bindingSeed = args.clone();
        context = (Context) args[0];
        executor = (Executor) args[1];
    }

    void prepare(ClassLoader loader) throws Exception {
        controllerType = Class.forName("com.android.systemui.controls.controller.ControlsController", false, loader);
        infoType = Class.forName("com.android.systemui.controls.controller.ControlInfo", false, loader);
        structureType = Class.forName("com.android.systemui.controls.controller.StructureInfo", false, loader);
        structureType.getConstructor(ComponentName.class, CharSequence.class, List.class);
    }

    List<Entry> entries() { return enabled.getAsBoolean() ? entries : List.of(); }
    Icon icon(String id) {
        for (Entry entry : entries()) if (entry.id().equals(id) && entry.control() != null) return entry.control().getCustomIcon();
        return null;
    }
    boolean isVisible() { return visible; }
    boolean isReady() { return bindingSeed != null; }
    /** SystemUI context captured from the system's own binding controller. */
    Context context() { return context; }

    int currentUserId() {
        try { Object owner = controller(); return owner == null ? -1 : (Integer) HostReflect.get(owner, "getCurrentUserId"); }
        catch (Exception error) { return -1; }
    }

    PendingIntent detailsIntent(String id) {
        if (!enabled.getAsBoolean() || !visible || context == null || !sameUser()) return null;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) return null;
        Control control = controls.get(id);
        if (control == null) return null;
        PendingIntent intent = control.getAppIntent();
        return BridgeContract.MIHOME.equals(intent.getCreatorPackage()) && intent.getCreatorUid() / 100000 == userId ? intent : null;
    }

    void setVisible(boolean value) {
        main.post(() -> {
            if (visible == value) return;
            visible = value;
            if (value) start(); else stop(false);
        });
    }

    void reset() {
        // Invalidate Binder callbacks immediately, including during a user switch.
        generation++;
        entries = List.of();
        main.post(() -> { stop(true); if (visible && enabled.getAsBoolean()) start(); });
    }

    private Object controller() throws Exception {
        Object source = component.get();
        return source == null ? null : ((Optional<?>) HostReflect.get(source, "getControlsController")).orElse(null);
    }

    private boolean sameUser() {
        try {
            Object current = controller();
            return current != null && userId == (Integer) HostReflect.get(current, "getCurrentUserId")
                    && userId == (Integer) HostReflect.get(bindingSeed[4], "getUserId");
        } catch (Exception error) { return false; }
    }

    private void start() {
        stop(true);
        if (!enabled.getAsBoolean()) return;
        final int epoch = generation;
        try {
            Object owner = controller();
            if (owner == null || bindingSeed == null) return;
            userId = (Integer) HostReflect.get(owner, "getCurrentUserId");
            if (!sameUser()) return;
            for (Object structure : (List<?>) HostReflect.call(owner, "getFavoritesForComponent",
                    new Class<?>[]{ComponentName.class}, SERVICE)) {
                for (Object info : (List<?>) HostReflect.get(structure, "getControls")) {
                    favorites.putIfAbsent((String) HostReflect.get(info, "getControlId"), info);
                }
            }
            publish();
            if (favorites.isEmpty()) return;
            Object callback = Proxy.newProxyInstance(controllerType.getClassLoader(), new Class<?>[]{controllerType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return proxyObject(proxy, method.getName(), args);
                if (method.getName().equals("refreshStatus") && SERVICE.equals(args[0]) && args[1] instanceof Control control) {
                    main.post(() -> {
                        if (!valid(epoch) || !favorites.containsKey(control.getControlId())) return;
                        controls.put(control.getControlId(), control);
                        Pending waiting = pending.get(control.getControlId());
                        if (waiting != null && DeviceSpacePolicy.confirmsAction(waiting.expected(),
                                isOn(control), control.getStatus() == Control.STATUS_OK)) pending.remove(control.getControlId());
                        if (!pending.containsKey(control.getControlId())) messages.remove(control.getControlId());
                        publish();
                    });
                } else if (method.getName().equals("onActionResponse") && SERVICE.equals(args[0])) {
                    String id = (String) args[1]; int response = (Integer) args[2];
                    main.post(() -> response(epoch, id, response));
                } else if (method.getName().equals("getCurrentUserId")) return userId;
                return null;
            });
            Object[] args = bindingSeed.clone();
            Class<?> lazyType = bindingConstructor.getParameterTypes()[2];
            args[2] = Proxy.newProxyInstance(lazyType.getClassLoader(), new Class<?>[]{lazyType}, (proxy, method, values) ->
                    method.getName().equals("get") ? callback : proxyObject(proxy, method.getName(), values));
            binding = bindingConstructor.newInstance(args);
            Object ownBinding = binding;
            Object structure = structureType.getConstructor(ComponentName.class, CharSequence.class, List.class)
                    .newInstance(SERVICE, "米家", new ArrayList<>(favorites.values()));
            executor.execute(() -> {
                try {
                    if (epoch == generation) HostReflect.call(ownBinding, "subscribe", new Class<?>[]{structureType}, structure);
                } catch (Exception error) { report("MiHome subscription failed", error); }
            });
            main.postDelayed(() -> {
                if (!valid(epoch)) return;
                for (String id : favorites.keySet()) if (!controls.containsKey(id)) messages.put(id, "暂未连接，重新展开重试");
                publish();
            }, 10000);
        } catch (Exception error) { report("Cannot start MiHome subscription", error); }
    }

    private boolean valid(int epoch) {
        return epoch == generation && visible && enabled.getAsBoolean() && sameUser();
    }

    private void stop(boolean clear) {
        generation++;
        Object old = binding;
        binding = null;
        if (old != null) executor.execute(() -> {
            try { HostReflect.get(old, "unbind"); }
            catch (Exception error) { failure.accept("MiHome unbind failed", error); }
        });
        controls.clear(); pending.clear(); messages.clear();
        if (clear) favorites.clear();
        publish();
    }

    void click(String id, boolean details) {
        if (!enabled.getAsBoolean() || !visible || context == null || !sameUser()) return;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        boolean locked = keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked();
        if (locked) { toast("请先解锁手机，再控制米家设备"); return; }
        Control control = controls.get(id);
        if (control == null) { toast("正在连接米家，请稍后重试"); return; }
        if (details) { openDetails(control); return; }
        if (control.getStatus() != Control.STATUS_OK) { toast("设备离线或不可用，可长按打开米家详情"); return; }
        if (pending.containsKey(id)) return;
        ControlAction action = action(control.getControlTemplate());
        if (action == null) { openDetails(control); return; }
        if (!DeviceSpacePolicy.canAct(enabled.getAsBoolean(), visible, locked, sameUser(), binding != null, pending.containsKey(id))) return;
        Object marker = new Object();
        pending.put(id, new Pending(marker, action instanceof BooleanAction toggle ? toggle.getNewState() : null));
        messages.remove(id); publish();
        int epoch = generation;
        Object ownBinding = binding, info = favorites.get(id);
        executor.execute(() -> {
            try {
                // Check again at dispatch: locking the screen or switching users cancels queued actions.
                if (valid(epoch) && !keyguard.isDeviceLocked() && !keyguard.isKeyguardLocked())
                    HostReflect.call(ownBinding, "action", new Class<?>[]{ComponentName.class, infoType, ControlAction.class}, SERVICE, info, action);
            } catch (Exception error) { report("MiHome action failed", error); }
        });
        main.postDelayed(() -> {
            Pending waiting = pending.get(id);
            if (valid(epoch) && waiting != null && waiting.marker() == marker) {
                pending.remove(id); messages.put(id, "操作超时，请重试"); publish();
            }
        }, 8000);
    }

    private void response(int epoch, String id, int response) {
        if (!valid(epoch) || !pending.containsKey(id)) return;
        if (response == ControlAction.RESPONSE_OK) {
            Control current = controls.get(id);
            if (current != null && unwrap(current.getControlTemplate()) instanceof StatelessTemplate) {
                pending.remove(id); messages.put(id, "已发送"); publish();
            }
            // Toggle state is updated only by a fresh provider event, never by optimistic inversion.
            return;
        }
        pending.remove(id);
        messages.put(id, response >= ControlAction.RESPONSE_CHALLENGE_ACK ? "请在米家中确认操作" : "操作失败，请重试");
        publish();
    }

    private void openDetails(Control control) {
        try {
            if (!BridgeContract.MIHOME.equals(control.getAppIntent().getCreatorPackage())) return;
            // The provider supplies the target PendingIntent; no device URI is synthesized.
            // Android 14+ requires the sender to opt in. Only lend our visible window's
            // launch privilege for this unlocked, user-initiated interaction.
            ActivityOptions options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
            control.getAppIntent().send(context, 0, null, null, null, null, options.toBundle());
        } catch (Exception error) {
            failure.accept("MiHome detail launch failed", error);
            toast("无法打开详情，请打开米家重试");
            return;
        }
        collapsePanel();
    }

    @SuppressLint("WrongConstant") // Hidden statusbar service is used only inside the SystemUI host.
    private void collapsePanel() {
        // Send while the shade is still visible, then reveal the launched activity.
        try { HostReflect.get(context.getSystemService("statusbar"), "collapsePanels"); }
        catch (Exception error) { failure.accept("Cannot collapse Device Space after opening details", error); }
    }

    static ControlTemplate unwrap(ControlTemplate template) {
        for (int i = 0; i < 4 && template instanceof TemperatureControlTemplate temperature; i++) template = temperature.getTemplate();
        return template;
    }
    static ControlAction action(ControlTemplate source) {
        ControlTemplate template = unwrap(source);
        if (template instanceof ToggleTemplate toggle) return new BooleanAction(toggle.getTemplateId(), !toggle.isChecked());
        if (template instanceof ToggleRangeTemplate toggle) return new BooleanAction(toggle.getTemplateId(), !toggle.isChecked());
        if (template instanceof StatelessTemplate) return new CommandAction(template.getTemplateId());
        return null;
    }
    static boolean isOn(Control control) {
        ControlTemplate template = unwrap(control.getControlTemplate());
        if (template instanceof ToggleTemplate toggle) return toggle.isChecked();
        if (template instanceof ToggleRangeTemplate toggle) return toggle.isChecked();
        return control.getStatus() == Control.STATUS_OK;
    }

    private void publish() {
        ArrayList<Entry> next = new ArrayList<>();
        try {
            for (var item : favorites.entrySet()) {
                Object info = item.getValue(); Control control = controls.get(item.getKey());
                next.add(new Entry(item.getKey(), HostReflect.get(info, "getControlTitle").toString(),
                        HostReflect.get(info, "getControlSubtitle").toString(), (Integer) HostReflect.get(info, "getDeviceType"),
                        info, control, pending.containsKey(item.getKey()), messages.get(item.getKey())));
            }
            entries = List.copyOf(next);
            changed.run();
        } catch (Exception error) { failure.accept("Cannot compose MiHome state", error); }
    }
    private void report(String message, Throwable error) { failure.accept(message, error); }
    private void toast(String message) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }
    private static Object proxyObject(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "toString" -> "MiHomeDeviceSpaceCallback";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }
}
