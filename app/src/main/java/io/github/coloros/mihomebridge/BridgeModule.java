package io.github.coloros.mihomebridge;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedModule;

/** Uses Xiaomi's existing BIND_CONTROLS service and ColorOS's existing controls tile. */
public final class BridgeModule extends XposedModule {
    private static final String TAG = "MiHomeBridge";
    private final AtomicBoolean registered = new AtomicBoolean();
    private WeakReference<Object> controlsComponent = new WeakReference<>(null);
    private WeakReference<Object> tilesInteractor = new WeakReference<>(null);
    private Class<?> tileSpecType;
    private Object controlsSpec;
    private SharedPreferences preferences;
    private final BridgeState state = new BridgeState();
    private volatile boolean miHookInstalled;
    private NativeDeviceSpace nativeSpace;
    private final ThreadLocal<Boolean> providerRequest = ThreadLocal.withInitial(() -> false);

    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage() || Build.VERSION.SDK_INT != 37) return;
        String pkg = param.getPackageName();
        if (!pkg.equals(BridgeContract.MIHOME) && !pkg.equals(BridgeContract.SYSTEMUI) && !pkg.equals(BridgeContract.MYDEVICES)) return;
        try {
            preferences = getRemotePreferences(BridgeContract.PREFS);
            ClassLoader loader = param.getDefaultClassLoader();
            if (pkg.equals(BridgeContract.MYDEVICES)) {
                new NativeDeviceCenter(this, this::failure).install(loader);
                log(Log.INFO, TAG, "Native device center hooks installed");
                return;
            }
            if (pkg.equals(BridgeContract.MIHOME)) installMiHome(loader);
            else {
                captureControls(loader);
                nativeSpace = new NativeDeviceSpace(this, this::enabled, () -> controlsComponent.get(), this::failure);
                try { nativeSpace.install(loader); }
                catch (Throwable error) { failure("Native Device Space is not compatible", error); }
            }
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof Application app && pkg.equals(app.getPackageName())
                        && pkg.equals(Application.getProcessName())) {
                    try { attach(app, pkg); }
                    catch (Throwable error) { failure("Host attach failed", error); }
                }
                return result;
            });
            log(Log.INFO, TAG, "Hooks installed: " + pkg);
        } catch (Throwable error) {
            failure("Unsupported host shape: " + pkg, error);
        }
    }

    private boolean enabled() {
        try {
            if (preferences == null) return false;
            state.update(preferences.getLong(BridgeContract.REVISION, 0),
                    preferences.getBoolean(BridgeContract.ENABLED, true));
            return state.enabled();
        }
        catch (RuntimeException e) { return false; }
    }

    private void installMiHome(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> utils = Class.forName("com.xiaomi.smarthome.controls.ControlUtils", false, loader);
        Method init = utils.getDeclaredMethod("initService", Context.class, boolean.class);
        if (init.getReturnType() != void.class) throw new NoSuchMethodException("initService return type");
        Class<?> provider = Class.forName(BridgeContract.SERVICE, false, loader);
        if (!android.service.controls.ControlsProviderService.class.isAssignableFrom(provider))
            throw new IllegalStateException("Unexpected controls provider");
        installEnumeration(utils, loader);
        hook(init).intercept(chain -> {
            if (!enabled()) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            if (args[0] instanceof Context context && BridgeContract.MIHOME.equals(context.getPackageName())) {
                try {
                    rememberService(context);
                    args[1] = true;
                } catch (Throwable error) {
                    failure("Cannot save original provider state", error);
                    return chain.proceed();
                }
            }
            return chain.proceed(args);
        });
        deoptimize(init);
        miHookInstalled = true;
    }

    private void installEnumeration(Class<?> utils, ClassLoader loader) throws ReflectiveOperationException {
        // On non-MIUI, Xiaomi otherwise returns only frequent devices and zero scenes.
        // Redirect that exact fallback while inside the provider's enumeration request.
        Method suggested = Arrays.stream(utils.getDeclaredMethods()).filter(method -> {
            Class<?>[] p = method.getParameterTypes();
            return method.getName().equals("readSuggestDeviceList") && Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class && p.length == 5 && p[0] == Context.class
                    && p[1] == String.class && p[2] == int.class && p[3] == int.class;
        }).findFirst().orElseThrow(() -> new NoSuchMethodException("readSuggestDeviceList"));
        Method all = utils.getDeclaredMethod("readAllDeviceList", Context.class, String.class,
                suggested.getParameterTypes()[4]);
        if (!Modifier.isStatic(all.getModifiers()) || all.getReturnType() != void.class)
            throw new NoSuchMethodException("readAllDeviceList signature");
        Method request = Class.forName(BridgeContract.SERVICE + "$2", false, loader)
                .getDeclaredMethod("onReadyRequest", long.class);
        hook(request).intercept(chain -> {
            boolean previous = providerRequest.get();
            providerRequest.set(true);
            try { return chain.proceed(); }
            finally {
                if (previous) providerRequest.set(true);
                else providerRequest.remove();
            }
        });
        hook(suggested).intercept(chain -> {
            if (enabled() && EnumerationPolicy.useAllDevices(providerRequest.get(),
                    (Integer) chain.getArg(2), (Integer) chain.getArg(3))) {
                try { return all.invoke(null, chain.getArg(0), chain.getArg(1), chain.getArg(4)); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            }
            return chain.proceed();
        });
        deoptimize(request);
    }

    private void captureControls(ClassLoader loader) throws ReflectiveOperationException {
        // Observe construction without changing feature flags or lock-screen policy.
        Class<?> type = Class.forName("com.android.systemui.controls.dagger.ControlsComponent", false, loader);
        type.getDeclaredMethod("getControlsUiController");
        type.getDeclaredMethod("getControlsController");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                controlsComponent = new WeakReference<>(chain.getThisObject());
                return result;
            });
        }
        Class<?> interactor = Class.forName("com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractorImpl", false, loader);
        tileSpecType = Class.forName("com.android.systemui.qs.pipeline.shared.TileSpec", false, loader);
        Object companion = tileSpecType.getField("Companion").get(null);
        controlsSpec = companion.getClass().getMethod("create", String.class).invoke(companion, "controls");
        interactor.getMethod("addTile", tileSpecType, int.class);
        interactor.getMethod("removeTiles", java.util.Collection.class);
        for (Constructor<?> constructor : interactor.getDeclaredConstructors()) {
            hook(constructor).intercept(chain -> {
                Object result = chain.proceed();
                tilesInteractor = new WeakReference<>(chain.getThisObject());
                return result;
            });
        }
    }

    private void attach(Application app, String pkg) {
        if (!registered.compareAndSet(false, true)) return;
        if (pkg.equals(BridgeContract.SYSTEMUI)) new DeviceCenterHost(nativeSpace, this::enabled, this::failure).register(app);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!BridgeContract.ACTION.equals(intent.getAction())) return;
                ResultReceiver reply = null;
                String operation = null;
                Bundle result = new Bundle();
                try {
                    reply = intent.getParcelableExtra("reply", ResultReceiver.class);
                    operation = intent.getStringExtra("operation");
                    // The signature permission authenticates this settings snapshot. Frozen
                    // hosts can miss preference callbacks, and must reject deferred commands.
                    if (!BridgeState.timely(intent.getLongExtra(BridgeContract.SENT_AT, -1),
                            SystemClock.elapsedRealtime(), BridgeContract.COMMAND_TIMEOUT_MS))
                        throw new IllegalStateException("请求已过期，请重新操作。");
                    enabled();
                    if (!intent.hasExtra(BridgeContract.ENABLED)
                            || !state.update(intent.getLongExtra(BridgeContract.REVISION, -1),
                                    intent.getBooleanExtra(BridgeContract.ENABLED, false)))
                        throw new IllegalStateException("设置已变化，请重新操作。");
                    result.putBoolean("hook", true);
                    result.putString("host", pkg);
                    result.putString("build", BuildConfig.VERSION_NAME);
                    result.putBoolean(BridgeContract.ENABLED, state.enabled());
                    result.putLong(BridgeContract.REVISION, state.revision());
                    if (pkg.equals(BridgeContract.SYSTEMUI)) handleSystemUi(app, operation, result);
                    else handleMiHome(app, operation, result);
                    if (result.getBoolean("pending")) {
                        PendingResult pending = goAsync();
                        awaitTile(app, BridgeContract.ADD.equals(operation), reply, result, pending, 20);
                        return;
                    }
                    if (reply != null) reply.send(0, result);
                } catch (Throwable error) {
                    failure("Command failed: " + operation, error);
                    result.putString("message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                    if (reply != null) reply.send(1, result);
                }
            }
        }, new IntentFilter(BridgeContract.ACTION), BridgeContract.PERMISSION,
                new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
        if (pkg.equals(BridgeContract.MIHOME) && enabled()) {
            try { enableService(app); }
            catch (Throwable error) { failure("Cannot enable provider", error); }
        }
    }

    private synchronized void rememberService(Context context) {
        SharedPreferences backup = context.getSharedPreferences(BridgeContract.BACKUP, Context.MODE_PRIVATE);
        if (!backup.contains("service_state")) {
            int state = context.getPackageManager().getComponentEnabledSetting(service());
            if (!backup.edit().putInt("service_state", state).commit())
                throw new IllegalStateException("无法保存米家服务原状态，已取消修改。");
        }
    }

    private void enableService(Context context) throws PackageManager.NameNotFoundException {
        if (!miHookInstalled) throw new IllegalStateException("米家版本的服务接口不匹配。");
        var info = context.getPackageManager().getServiceInfo(service(), PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
        if (!"android.permission.BIND_CONTROLS".equals(info.permission) || !info.exported)
            throw new IllegalStateException("米家设备服务声明不匹配。");
        rememberService(context);
        context.getPackageManager().setComponentEnabledSetting(service(), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    private void handleMiHome(Context context, String op, Bundle result) throws Exception {
        if (BridgeContract.RESTORE.equals(op)) {
            if (enabled()) throw new IllegalStateException("请先关闭模块开关，再恢复。");
            SharedPreferences backup = context.getSharedPreferences(BridgeContract.BACKUP, Context.MODE_PRIVATE);
            if (backup.contains("service_state")) {
                int state = backup.getInt("service_state", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                context.getPackageManager().setComponentEnabledSetting(service(), state, PackageManager.DONT_KILL_APP);
                backup.edit().remove("service_state").commit();
            }
        } else if (BridgeContract.ADD.equals(op)) {
            if (!enabled()) throw new IllegalStateException("请先启用模块开关。");
            enableService(context);
        } else if (!BridgeContract.STATUS.equals(op)) throw new IllegalArgumentException("未知操作");
        result.putBoolean("provider", providerEnabled(context));
        result.putBoolean("compatible", miHookInstalled);
    }

    private void handleSystemUi(Context context, String op, Bundle result) throws Exception {
        Object component = controlsComponent.get();
        boolean available = component != null && tilesInteractor.get() != null
                && ((Optional<?>) invoke(component, "getControlsController")).isPresent();
        SharedPreferences backup = context.getSharedPreferences(BridgeContract.BACKUP, Context.MODE_PRIVATE);
        NativeControlsTile tile = available ? nativeTile() : null;
        if (BridgeContract.SYNC.equals(op)) {
            if (nativeSpace != null) nativeSpace.settingsChanged();
        } else if (BridgeContract.ADD.equals(op)) {
            if (!enabled()) throw new IllegalStateException("请先启用模块开关。");
            if (!available) throw new IllegalStateException("当前系统未提供原生设备控制接口。");
            if (!providerEnabled(context)) throw new IllegalStateException("请先打开一次米家，确认已登录，再重试。");
            if (!tile.isPresent()) {
                if (!backup.edit().putBoolean("native_tile_added", true).remove("tile_added").commit())
                    throw new IllegalStateException("无法保存入口恢复记录。");
                tile.add();
                result.putBoolean("pending", true);
            }
        } else if (BridgeContract.RESTORE.equals(op)) {
            if (enabled()) throw new IllegalStateException("请先关闭模块开关，再恢复。");
            if (nativeSpace != null) nativeSpace.settingsChanged();
            if (backup.getBoolean("native_tile_added", false)) {
                if (tile == null) throw new IllegalStateException("原生快捷开关接口尚未就绪。");
                tile.remove();
                result.putBoolean("pending", true);
            }
        } else if (BridgeContract.CENTER_OPEN.equals(op)) {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard == null || keyguard.isDeviceLocked()) throw new IllegalStateException("请先解锁手机。");
            context.startActivity(new Intent().setComponent(new ComponentName(BridgeContract.MYDEVICES,
                    "com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } else if (BridgeContract.OPEN.equals(op)) {
            if (!enabled()) throw new IllegalStateException("请先启用模块开关。");
            if (!available) throw new IllegalStateException("原生设备控制接口尚未就绪。");
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard == null || keyguard.isDeviceLocked()) throw new IllegalStateException("请先解锁手机。");
            Object ui = ((Optional<?>) invoke(component, "getControlsUiController")).orElseThrow();
            Object target = invoke(ui, "resolveActivity");
            if (!(target instanceof Class<?> type) || !type.getName().startsWith("com.android.systemui.controls."))
                throw new IllegalStateException("设备控制页面不匹配。");
            context.startActivity(new Intent().setComponent(new ComponentName(context, type))
                    .putExtra("extra_animate", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } else if (!BridgeContract.STATUS.equals(op)) throw new IllegalArgumentException("未知操作");
        result.putBoolean("controls", available);
        result.putBoolean("tile", tile != null && tile.isPresent());
        if (nativeSpace != null) nativeSpace.status(result);
    }

    private NativeControlsTile nativeTile() throws ReflectiveOperationException {
        Object host = tilesInteractor.get();
        if (host == null) throw new IllegalStateException("快捷开关管理器尚未就绪。");
        return new NativeControlsTile(host, tileSpecType, controlsSpec);
    }

    private void awaitTile(Context context, boolean expected, ResultReceiver reply, Bundle result,
                           BroadcastReceiver.PendingResult pending, int remaining) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean done = false;
            try {
                boolean actual = nativeTile().isPresent();
                if (actual != expected && remaining > 0) {
                    awaitTile(context, expected, reply, result, pending, remaining - 1);
                    return;
                }
                done = true;
                result.putBoolean("pending", false);
                result.putBoolean("tile", actual);
                if (actual != expected) throw new IllegalStateException("系统尚未应用入口修改，请重新检查后重试。");
                if (!expected) context.getSharedPreferences(BridgeContract.BACKUP, Context.MODE_PRIVATE)
                        .edit().remove("native_tile_added").commit();
                if (reply != null) reply.send(0, result);
            } catch (Throwable error) {
                done = true;
                failure("Native tile update failed", error);
                result.putString("message", error.getMessage());
                if (reply != null) reply.send(1, result);
            } finally { if (done) pending.finish(); }
        }, 80);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
    private static ComponentName service() { return new ComponentName(BridgeContract.MIHOME, BridgeContract.SERVICE); }
    private static boolean providerEnabled(Context context) {
        try {
            var pm = context.getPackageManager();
            var info = pm.getServiceInfo(service(), PackageManager.ComponentInfoFlags.of(0));
            int state = pm.getComponentEnabledSetting(service());
            return info.applicationInfo.enabled && (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled));
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }
    private void failure(String message, Throwable error) { log(Log.ERROR, TAG, message, error); }
}
