package io.github.coloros.mihomebridge;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.IBinder;
import android.os.SystemClock;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.service.controls.Control;
import android.service.controls.DeviceTypes;
import android.view.View;
import android.widget.ImageView;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Adapts Controls to the actual ColorOS Device Space SDK, without replacing its views. */
final class NativeDeviceSpace {
    private static final String SDK = "com.oplus.deviceplugin.sdk.";
    private final XposedModule module;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Object> listeners = new WeakHashMap<>();
    private final Map<ImageView, Object> iconRequests = new WeakHashMap<>();
    private final MiHomeSession session;
    private final MiHomeRuntime runtime;
    private long lastBound;
    private final Runnable refresh = this::refreshPanels;
    private Class<?> panelInfo, panelState, deviceType, statusType, itemType, connectType, coordinationType;
    private Constructor<?> panelConstructor, deviceConstructor;
    private Method editMode;
    private volatile boolean installed;
    private volatile int boundCards;

    NativeDeviceSpace(XposedModule module, BooleanSupplier enabled, MiHomeRuntime runtime, BiConsumer<String, Throwable> failure) {
        this.module = module; this.enabled = enabled; this.runtime = runtime; this.failure = failure;
        session = runtime.session;
        runtime.addListener(() -> { main.removeCallbacks(refresh); main.postDelayed(refresh, 60); });
    }

    void install(ClassLoader loader) throws Exception {
        panelInfo = type(loader, "entity.PanelInfo"); panelState = type(loader, "entity.PanelState");
        Class<?> device = type(loader, "entity.PanelDevice");
        deviceType = type(loader, "entity.DeviceType"); statusType = type(loader, "entity.DeviceStatus");
        itemType = type(loader, "entity.DeviceItemType"); connectType = type(loader, "entity.ConnectState");
        coordinationType = type(loader, "entity.SynergyCoordinationState");
        panelConstructor = panelInfo.getConstructor(panelState, List.class);
        deviceConstructor = device.getConstructor(String.class, deviceType, Integer.class, List.class, Map.class,
                String.class, statusType, String.class, int.class, List.class, itemType, connectType, coordinationType,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, Boolean.class);
        statusType.getConstructor(String.class, String.class, Integer.class);
        editMode = type(loader, "utils.PlatformUtils").getDeclaredMethod("getIsEditMode");
        // Resolve every hook before installing any. Only this firmware's explicit shapes are supported.
        Method initial = type(loader, "presenter.l").getDeclaredMethod("b");
        Method[] updates = {type(loader, "ui.panel.d").getDeclaredMethod("a", panelInfo),
                type(loader, "ui.panel.j").getDeclaredMethod("a", panelInfo)};
        Class<?>[] panelTypes = {type(loader, "ui.panel.h"), type(loader, "ui.panel.m")};
        for (Class<?> type : panelTypes) {
            type.getDeclaredMethod("onResume"); type.getDeclaredMethod("onPause"); type.getDeclaredMethod("onDestroy");
        }
        Method[] binders = {
                type(loader, "ui.view.separatecardview.SquareDeviceCardView").getDeclaredMethod("d", device),
                type(loader, "ui.view.separatecardview.RectangleDeviceCardView").getDeclaredMethod("e", device),
                type(loader, "ui.view.d").getDeclaredMethod("f", device)
        };
        Class<?> progress = type(loader, "ui.view.progressbar.BatteryCircularProgressLayout");
        Class<?> viewUtils = type(loader, "utils.ViewExtKt");
        Method[] icons = {
                viewUtils.getDeclaredMethod("bindDeviceIcon", progress, device, boolean.class),
                viewUtils.getDeclaredMethod("bindDeviceIconBlurDisable", progress, device, boolean.class),
                viewUtils.getDeclaredMethod("bindDeviceIconGlobalTheme", progress, device, boolean.class)
        };
        Class<?> span = Class.forName("com.android.systemui.plugins.qs.SpanSizeInPlugin", false, loader);
        Class<?> ability = Class.forName("com.android.systemui.plugins.qs.IHostAbility", false, loader);
        Method submit = panelTypes[1].getDeclaredMethod("a", panelTypes[1], List.class, ability, span);
        module.hook(initial).intercept(chain -> {
            Object original = chain.proceed();
            try { return merge(original); }
            catch (Exception error) { failure.accept("Initial Device Space data failed", error); return original; }
        });
        for (Method update : updates) module.hook(update).intercept(chain -> {
            Object original = chain.getArg(0);
            synchronized (listeners) { listeners.put(chain.getThisObject(), original); }
            Object combined = original;
            try { combined = merge(original); }
            catch (Exception error) { failure.accept("Device Space merge failed", error); }
            return chain.proceed(new Object[]{combined});
        });
        for (Class<?> panel : panelTypes) {
            module.hook(panel.getDeclaredMethod("onResume")).intercept(chain -> {
                Object result = chain.proceed(); panelVisible(chain.getThisObject(), true); return result;
            });
            for (String name : List.of("onPause", "onDestroy")) module.hook(panel.getDeclaredMethod(name)).intercept(chain -> {
                Object result = chain.proceed(); panelVisible(chain.getThisObject(), false); return result;
            });
        }
        for (Method binder : binders) module.hook(binder).intercept(chain -> {
            Object result = chain.proceed();
            try {
                String id = (String) HostReflect.get(chain.getArg(0), "getDeviceId");
                if (installed && id.startsWith(DeviceSpacePolicy.PREFIX) && chain.getThisObject() instanceof View view) {
                    String controlId = id.substring(DeviceSpacePolicy.PREFIX.length());
                    view.setContentDescription(HostReflect.get(chain.getArg(0), "getName") + "，"
                            + HostReflect.get(HostReflect.get(chain.getArg(0), "getDeviceStatus"), "getMainStatus"));
                    view.setOnClickListener(v -> { if (!editing()) session.click(controlId, false); });
                    view.setOnLongClickListener(v -> {
                        if (editing()) return false;
                        session.click(controlId, true); return true;
                    });
                    boundCards++; lastBound = SystemClock.elapsedRealtime();
                }
            } catch (Exception error) { failure.accept("Device Space card binding failed", error); }
            return result;
        });
        for (Method icon : icons) module.hook(icon).intercept(chain -> {
            Object result = chain.proceed();
            try { bindIcon((View) chain.getArg(0), chain.getArg(1)); }
            catch (Exception error) { failure.accept("MiHome icon binding failed", error); }
            return result;
        });
        module.hook(submit).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            try {
                List<?> list = (List<?>) args[1];
                boolean containsMiHome = false;
                for (Object item : list) if (((String) HostReflect.get(item, "getDeviceId")).startsWith(DeviceSpacePolicy.PREFIX)) {
                    containsMiHome = true; break;
                }
                if (containsMiHome) {
                    int limit = DeviceSpacePolicy.capacity((Integer) HostReflect.get(args[3], "getColSpan"),
                            (Integer) HostReflect.get(args[3], "getRowSpan"));
                    if (list.size() > limit) args[1] = new ArrayList<>(list.subList(0, limit));
                }
            } catch (Exception error) { failure.accept("Device Space capacity check failed", error); }
            return chain.proceed(args);
        });
        installed = true;
    }

    private void panelVisible(Object panel, boolean visible) { runtime.panelVisible(panel, visible); }
    void settingsChanged() { runtime.settingsChanged(); }
    void status(Bundle result) {
        result.putBoolean("native_space", installed);
        result.putInt("native_bound_cards", boundCards);
        result.putLong("native_last_bind", lastBound);
        result.putBoolean("native_panel_active", installed && runtime.hasPanels() && lastBound > 0);
    }

    private Object merge(Object original) throws Exception {
        if (!installed || original == null) return original;
        List<MiHomeSession.Entry> entries = runtime.entries();
        List<?> existing = (List<?>) HostReflect.get(original, "getPanelDevices");
        if (entries.isEmpty() && existing.stream().noneMatch(item -> {
            try { return ((String) HostReflect.get(item, "getDeviceId")).startsWith(DeviceSpacePolicy.PREFIX); }
            catch (Exception error) { return false; }
        })) return original;
        Object oldState = HostReflect.get(original, "getPanelState");
        // Keep the vendor's privacy-consent page intact.
        if ("PRIVACY".equals(oldState.toString())) return original;
        List<Object> additions = new ArrayList<>();
        for (MiHomeSession.Entry entry : entries) additions.add(card(entry));
        List<Object> combined = DeviceSpacePolicy.merge(new ArrayList<>(existing), additions, item -> {
            try { return (String) HostReflect.get(item, "getDeviceId"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        return panelConstructor.newInstance(combined.isEmpty() ? oldState : HostReflect.enumValue(panelState, "DEVICE_LIST"), combined);
    }

    private Object card(MiHomeSession.Entry entry) throws Exception {
        Control control = entry.control();
        boolean ready = control != null && !entry.stale() && control.getStatus() == Control.STATUS_OK;
        boolean on = ready && MiHomeSession.isOn(control);
        String text = statusText(entry);
        Object status = statusType.getConstructor(String.class, String.class, Integer.class)
                .newInstance(text, entry.subtitle().isBlank() ? "米家" : "米家 · " + entry.subtitle(), null);
        return deviceConstructor.newInstance(DeviceSpacePolicy.PREFIX + entry.id(),
                HostReflect.enumValue(deviceType, nativeType(entry.type())), null, List.of(), Map.of(),
                entry.title(), status, "", ready ? (on ? 1 : 2) : 3, List.of(),
                HostReflect.enumValue(itemType, "SINGLE"), HostReflect.enumValue(connectType, ready ? "ON_LINE" : "OFF_LINE"),
                HostReflect.enumValue(coordinationType, "DEFAULT"), 0, 0, 0, 0, 0, 0, 0, false);
    }

    static String statusText(MiHomeSession.Entry entry) {
        Control control = entry.control();
        boolean ready = control != null && !entry.stale() && control.getStatus() == Control.STATUS_OK;
        String text;
        if (entry.pending()) text = "正在执行…";
        else if (entry.message() != null) text = entry.message();
        else if (control == null) text = "正在连接…";
        else if (entry.stale()) text = control.getStatusText().length() == 0 ? "正在重连…" : control.getStatusText() + " · 正在重连";
        else if (!ready) text = switch (control.getStatus()) {
            case Control.STATUS_NOT_FOUND -> "设备已移除";
            case Control.STATUS_DISABLED -> "设备不可用";
            default -> control.getStatusText().length() == 0 ? "设备离线或不可用" : control.getStatusText().toString();
        };
        else text = control.getStatusText().length() == 0 ? (MiHomeSession.isOn(control) ? "已开启" : "已关闭") : control.getStatusText().toString();
        return text;
    }

    private static String nativeType(int type) {
        return switch (type) {
            case DeviceTypes.TYPE_LIGHT -> "LAMP";
            case DeviceTypes.TYPE_AC_UNIT, DeviceTypes.TYPE_THERMOSTAT -> "SMART_AIR_CONDITIONER";
            case DeviceTypes.TYPE_FAN -> "SMART_ELECTRIC_FAN";
            case DeviceTypes.TYPE_WASHER, DeviceTypes.TYPE_DRYER -> "SMART_WASH_MACHINE";
            case DeviceTypes.TYPE_WATER_HEATER -> "SMART_WATER_HEATER";
            case DeviceTypes.TYPE_TV -> "TV";
            case DeviceTypes.TYPE_CAMERA -> "CAMERA";
            default -> "OTHER";
        };
    }

    private void refreshPanels() {
        List<Map.Entry<Object, Object>> targets;
        synchronized (listeners) { targets = new ArrayList<>(listeners.entrySet()); }
        for (var entry : targets) {
            try { HostReflect.call(entry.getKey(), "a", new Class<?>[]{panelInfo}, entry.getValue()); }
            catch (Exception error) { failure.accept("Device Space refresh failed", error); }
        }
    }

    private boolean editing() {
        try { return (Boolean) editMode.invoke(null); }
        catch (Exception error) { return true; }
    }
    private void bindIcon(View container, Object device) throws Exception {
        int imageId = container.getResources().getIdentifier("device_image", "id", BridgeContract.SYSTEMUI);
        View child = container.findViewById(imageId);
        if (!(child instanceof ImageView image)) return;
        String id = (String) HostReflect.get(device, "getDeviceId");
        Object token = new Object();
        iconRequests.put(image, token); // Also invalidates callbacks when a view is reused for a native device.
        if (!id.startsWith(DeviceSpacePolicy.PREFIX) || !enabled.getAsBoolean()) return;
        Icon icon = session.icon(id.substring(DeviceSpacePolicy.PREFIX.length()));
        if (icon == null) return;
        int state = (Integer) HostReflect.get(device, "getDeviceState");
        WeakReference<ImageView> target = new WeakReference<>(image);
        icon.loadDrawableAsync(container.getContext(), drawable -> {
            ImageView current = target.get();
            if (current == null || drawable == null || !enabled.getAsBoolean() || iconRequests.get(current) != token) return;
            current.clearColorFilter();
            current.setImageTintList(null);
            drawable = drawable.mutate();
            drawable.setAlpha(state == 3 ? 102 : 255);
            current.setImageDrawable(drawable);
        }, main);
    }
    private static Class<?> type(ClassLoader loader, String suffix) throws ClassNotFoundException {
        return Class.forName(SDK + suffix, false, loader);
    }
}
