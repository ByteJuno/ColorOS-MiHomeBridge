package io.github.coloros.mihomebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shared Controls backend. Either native presentation can fail without taking down the other. */
final class MiHomeRuntime {
    final MiHomeSession session;
    private final XposedModule module;
    private final BooleanSupplier enabled;
    private final Supplier<SharedPreferences> preferences;
    private final BiConsumer<String, Throwable> failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Boolean> panels = new WeakHashMap<>();
    private final Map<UserHandle, IBinder> providerTokens = new ConcurrentHashMap<>();
    private final DeviceCenterPolicy.Leases leases = new DeviceCenterPolicy.Leases();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Runnable expiry = this::expire;
    private boolean installed, lastEnabled;
    private java.util.Set<String> lastHidden = java.util.Set.of();

    MiHomeRuntime(XposedModule module, BooleanSupplier enabled, Supplier<Object> controls,
                  Supplier<SharedPreferences> preferences, BiConsumer<String, Throwable> failure) {
        this.module = module; this.enabled = enabled; this.preferences = preferences; this.failure = failure;
        lastEnabled = enabled.getAsBoolean();
        session = new MiHomeSession(enabled, controls, failure, () -> layout().hidden()); session.addListener(this::changed);
    }
    void install(ClassLoader loader) throws Exception {
        Class<?> executor = Class.forName("com.android.systemui.util.concurrency.DelayableExecutor", false, loader);
        Class<?> factory = Class.forName("com.android.systemui.controls.controller.PackageUpdateMonitor$Factory", false, loader);
        Class<?> binding = Class.forName("com.android.systemui.controls.controller.ControlsBindingControllerImpl", false, loader);
        Constructor<?> bindingCtor = binding.getConstructor(Context.class, executor, Class.forName("dagger.Lazy", false, loader),
                factory, Class.forName("com.android.systemui.settings.UserTracker", false, loader));
        Class<?> lifecycle = Class.forName("com.android.systemui.controls.controller.ControlsProviderLifecycleManager", false, loader);
        Constructor<?> lifecycleCtor = lifecycle.getConstructor(Context.class, executor,
                Class.forName("android.service.controls.IControlsActionCallback$Stub", false, loader), UserHandle.class, ComponentName.class, factory);
        Field token = lifecycle.getDeclaredField("token"), intent = lifecycle.getDeclaredField("intent");
        if (token.getType() != IBinder.class || intent.getType() != Intent.class) throw new NoSuchFieldException("Provider callback fields");
        token.setAccessible(true); intent.setAccessible(true);
        Class<?> controller = Class.forName("com.android.systemui.controls.controller.ControlsControllerImpl", false, loader);
        Method changeUser = controller.getDeclaredMethod("changeUser", UserHandle.class);
        Class<?> subscriber = Class.forName("com.android.systemui.controls.controller.StatefulControlSubscriber", false, loader);
        Field subscriberOwner = subscriber.getDeclaredField("controller"); subscriberOwner.setAccessible(true);
        Method onError = subscriber.getDeclaredMethod("onError", IBinder.class, String.class);
        Method onComplete = subscriber.getDeclaredMethod("onComplete", IBinder.class);
        Class<?> serviceConnection = Class.forName("com.android.systemui.controls.controller.ControlsProviderLifecycleManager$serviceConnection$1", false, loader);
        Field manager = serviceConnection.getDeclaredField("this$0"); manager.setAccessible(true);
        Field currentProvider = binding.getDeclaredField("currentProvider"); currentProvider.setAccessible(true);
        Method disconnected = serviceConnection.getDeclaredMethod("onServiceDisconnected", ComponentName.class);
        session.prepare(loader);
        module.hook(bindingCtor).intercept(chain -> {
            Object result = chain.proceed(); session.capture(bindingCtor, chain.getArgs().toArray()); return result;
        });
        module.hook(lifecycleCtor).intercept(chain -> {
            Object result = chain.proceed();
            if (MiHomeSession.SERVICE.equals(chain.getArg(4))) try {
                UserHandle user = (UserHandle) chain.getArg(3);
                IBinder shared = providerTokens.computeIfAbsent(user, key -> new Binder());
                Intent target = (Intent) intent.get(chain.getThisObject());
                Bundle callback = target.getBundleExtra("CALLBACK_BUNDLE");
                if (callback == null) throw new IllegalStateException("Missing provider callback token");
                token.set(chain.getThisObject(), shared); callback.putBinder("CALLBACK_TOKEN", shared); target.putExtra("CALLBACK_BUNDLE", callback);
            } catch (Exception error) { failure.accept("Cannot align MiHome callback token", error); }
            return result;
        });
        for (Method event : List.of(onError, onComplete)) module.hook(event).intercept(chain -> {
            Object owner = subscriberOwner.get(chain.getThisObject());
            Object result = chain.proceed(); session.connectionLost(owner, "米家设备订阅已断开"); return result;
        });
        module.hook(disconnected).intercept(chain -> {
            Object provider = manager.get(chain.getThisObject());
            Object active = session.currentBinding();
            Object owner = active != null && currentProvider.get(active) == provider ? session.currentCallback() : null;
            Object result = chain.proceed(); session.connectionLost(owner, "米家设备服务已断开"); return result;
        });
        module.hook(changeUser).intercept(chain -> {
            session.invalidate();
            Object result = chain.proceed();
            main.post(() -> { leases.clear(); session.reset(); updateVisibility(); changed(); });
            return result;
        });
        installed = true;
    }
    void addListener(Runnable listener) { listeners.add(listener); }
    private void changed() { for (Runnable listener : listeners) try { listener.run(); } catch (RuntimeException error) { failure.accept("Runtime observer failed", error); } }
    boolean enabled() { return installed && enabled.getAsBoolean(); }
    int user() { return session.currentUserId(); }
    DeviceLayoutPolicy.Options layout() { return LayoutPreferences.read(preferences.get(), user()); }
    List<MiHomeSession.Entry> entries() {
        return enabled() ? DeviceLayoutPolicy.arrange(session.entries(), layout(), MiHomeSession.Entry::id, MiHomeSession.Entry::subtitle) : List.of();
    }
    void panelVisible(Object panel, boolean visible) {
        synchronized (panels) { if (visible) panels.put(panel, true); else panels.remove(panel); }
        updateVisibility();
    }
    void centerVisible(String token, boolean visible) {
        leases.update(token, visible, SystemClock.elapsedRealtime()); updateVisibility();
        main.removeCallbacks(expiry);
        if (leases.active(SystemClock.elapsedRealtime())) main.postDelayed(expiry, DeviceCenterPolicy.LEASE_MS);
    }
    boolean leased(String token) { return leases.contains(token, SystemClock.elapsedRealtime()); }
    boolean hasPanels() { synchronized (panels) { return !panels.isEmpty(); } }
    private void expire() {
        updateVisibility();
        if (leases.active(SystemClock.elapsedRealtime())) main.postDelayed(expiry, 1000);
        changed();
    }
    private void updateVisibility() {
        synchronized (panels) { session.setVisible(enabled() && (!panels.isEmpty() || leases.active(SystemClock.elapsedRealtime()))); }
    }
    void settingsChanged() {
        boolean current = enabled.getAsBoolean();
        if (current != lastEnabled) { lastEnabled = current; session.reset(); }
        java.util.Set<String> hidden = layout().hidden();
        if (!hidden.equals(lastHidden)) { lastHidden = hidden; session.retry(); }
        updateVisibility(); changed();
    }
    void status(Bundle result) {
        result.putBoolean("core_ready", installed); result.putBoolean("native_binding", session.isReady());
        result.putBoolean("native_visible", session.isVisible()); result.putBoolean("center_visible", leases.active(SystemClock.elapsedRealtime()));
        result.putInt("native_devices", entries().size()); session.status(result);
    }
}
