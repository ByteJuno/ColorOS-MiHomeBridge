package io.github.coloros.mihomebridge;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.service.controls.Control;
import android.service.controls.DeviceTypes;
import android.service.controls.actions.BooleanAction;
import android.service.controls.actions.CommandAction;
import android.service.controls.actions.FloatAction;
import android.service.controls.templates.ControlButton;
import android.service.controls.templates.RangeTemplate;
import android.service.controls.templates.StatelessTemplate;
import android.service.controls.templates.TemperatureControlTemplate;
import android.service.controls.templates.ToggleRangeTemplate;
import android.service.controls.templates.ToggleTemplate;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device regression without UiAutomation or any appliance-provider action. */
public final class BridgeSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int checks;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments == null ? new Bundle() : arguments; super.onCreate(arguments); start(); }
    private void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); checks++; }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "controls");
            if (mode.equals("transport_start")) transportStart(result);
            else if (mode.equals("transport_finish")) transportFinish(result);
            else if (mode.equals("status")) { foreground(); result.putAll(command(BridgeContract.STATUS)); }
            else if (mode.equals("status_live")) result.putAll(command(BridgeContract.STATUS));
            else if (mode.equals("controls_preview")) preview();
            else if (mode.equals("layout")) layout(result);
            else if (mode.equals("layout_restore")) restoreLayout(result);
            else if (mode.equals("disable")) enabled(false, result);
            else if (mode.equals("enable_restore")) enabled(true, result);
            else if (mode.equals("open_full")) { foreground(); command(BridgeContract.OPEN); }
            else controls(result);
            result.putInt("checks", checks); result.putString("result", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("error", error.toString());
            android.util.Log.e("MiHomeBridgeSmoke", "Device regression failed", error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private Control control(String id, android.service.controls.templates.ControlTemplate template, int type) {
        PendingIntent target = PendingIntent.getActivity(getTargetContext(), 10404,
                new Intent(getTargetContext(), MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Control.StatefulBuilder(id, target).setTitle("模拟设备").setSubtitle("测试房间").setDeviceType(type)
                .setStatus(Control.STATUS_OK).setStatusText("测试状态").setControlTemplate(template).build();
    }
    private void controls(Bundle result) throws Exception {
        Control light = control("smoke-light", new ToggleRangeTemplate("light-switch", true, "开关",
                new RangeTemplate("light-level", 0, 100, 40, 1, "%.0f%%")), DeviceTypes.TYPE_LIGHT);
        ControlCapabilities caps = new ControlCapabilities(light);
        var off = caps.action("toggle", false, 0);
        check(off instanceof BooleanAction action && !action.getNewState() && action.getTemplateId().equals("light-switch"), "typed toggle");
        var level = caps.action("range", false, 42.6f);
        check(level instanceof FloatAction value && value.getNewValue() == 43 && value.getTemplateId().equals("light-level"), "quantized brightness");
        Control temperature = control("smoke-temperature", new TemperatureControlTemplate("thermostat",
                new RangeTemplate("temperature", 16, 30, 22, .5f, "%.1f ℃"), TemperatureControlTemplate.MODE_HEAT,
                TemperatureControlTemplate.MODE_HEAT, TemperatureControlTemplate.FLAG_MODE_HEAT | TemperatureControlTemplate.FLAG_MODE_OFF), DeviceTypes.TYPE_THERMOSTAT);
        var heat = new ControlCapabilities(temperature).action("range", false, 22.4f);
        check(heat instanceof FloatAction action && action.getNewValue() == 22.5f && action.getTemplateId().equals("temperature"), "nested temperature");
        Control scene = control("smoke-scene", new StatelessTemplate("scene-command"), DeviceTypes.TYPE_GENERIC_ON_OFF);
        check(new ControlCapabilities(scene).action("command", false, 0) instanceof CommandAction, "typed scene command");
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 101}) {
            boolean rejected = false;
            try { caps.action("range", false, invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "range rejection");
        }
        check(caps.bundle(DeviceTypes.TYPE_LIGHT).getString("format").equals("%.0f%%"), "format serialization");
        Control plain = control("smoke-toggle", new ToggleTemplate("plain-switch", new ControlButton(false, "电源")), DeviceTypes.TYPE_OUTLET);
        check(new ControlCapabilities(plain).hasToggle() && !new ControlCapabilities(plain).checked(), "plain toggle capability");
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        CenterDevice[] row = {new CenterDevice("smoke-light", "模拟灯光", "测试房间", "模拟状态", true, false, false,
                caps.bundle(DeviceTypes.TYPE_LIGHT), "", "", null)};
        CenterControlDialog[] dialog = new CenterControlDialog[1]; int[] sent = {0}; String[] last = {""}; float[] number = {0}; boolean[] checked = {true};
        try {
            runOnMainSync(() -> dialog[0] = new CenterControlDialog(activity, () -> row[0], () -> false,
                    (kind, value, amount) -> { sent[0]++; last[0] = kind; checked[0] = value; number[0] = amount; }, () -> {}, () -> {}, () -> {}));
            waitForIdleSync();
            runOnMainSync(() -> {
                try {
                    Switch toggle = (Switch) field(dialog[0], "toggle"); SeekBar bar = (SeekBar) field(dialog[0], "range");
                    check(toggle.isEnabled() && bar.isEnabled(), "fresh dialog enables supported controls");
                    toggle.performClick();
                    check(sent[0] == 1 && last[0].equals("toggle") && !checked[0], "UI gesture emits simulated toggle only");
                    check(toggle.isChecked(), "toggle waits for provider confirmation");
                    Bundle next = new Bundle(row[0].capabilities()); next.putBoolean("checked", false);
                    row[0] = new CenterDevice(row[0].id(), row[0].title(), row[0].room(), row[0].status(), true, false, false, next, "", "", null);
                    dialog[0].refresh(); check(!toggle.isChecked() && sent[0] == 1, "provider refresh does not dispatch");
                    bar.setProgress(25); check(sent[0] == 1, "programmatic slider refresh does not dispatch");
                    check(bar.getWidth() > 0, "slider laid out");
                    long time = SystemClock.uptimeMillis(); float x = bar.getWidth() * .7f, y = bar.getHeight() / 2f;
                    for (int event : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                        MotionEvent touch = MotionEvent.obtain(time, time + 16, event, x, y, 0);
                        bar.dispatchTouchEvent(touch); touch.recycle();
                    }
                    check(sent[0] == 2 && last[0].equals("range") && number[0] >= 0 && number[0] <= 100, "slider emits one simulated range action on release");
                    row[0] = row[0].staleCopy(); dialog[0].refresh();
                    check(!toggle.isEnabled() && !bar.isEnabled() && sent[0] == 2, "stale state disables controls without replay");
                    row[0] = new CenterDevice("smoke-temperature", "模拟温控", "测试房间", "模拟温度", true, false, false,
                            new ControlCapabilities(temperature).bundle(DeviceTypes.TYPE_THERMOSTAT), "", "", null);
                    dialog[0].refresh();
                    check(toggle.getVisibility() == View.GONE && bar.isEnabled() && bar.getMax() == 28, "temperature UI uses provider half-degree steps");
                    row[0] = new CenterDevice("smoke-scene", "模拟场景", "测试房间", "待执行", true, false, false,
                            new ControlCapabilities(scene).bundle(DeviceTypes.TYPE_GENERIC_ON_OFF), "", "", null);
                    dialog[0].refresh();
                    android.widget.Button execute = (android.widget.Button) field(dialog[0], "execute");
                    check(execute.isEnabled() && execute.getVisibility() == View.VISIBLE && bar.getVisibility() == View.GONE, "scene UI only exposes supported control");
                    execute.performClick(); check(sent[0] == 3 && last[0].equals("command"), "scene gesture invokes local simulated command");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            nativeIndicator(activity);
        } finally { runOnMainSync(() -> { if (dialog[0] != null) dialog[0].dismiss(); }); }
        result.putInt("simulated_ui_actions", sent[0]); result.putString("physical_provider_actions", "0");
    }
    private void nativeIndicator(Activity activity) {
        runOnMainSync(() -> {
            try {
                Context host = activity.createPackageContext(BridgeContract.MYDEVICES, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                ClassLoader loader = host.getClassLoader();
                Class<?> type = Class.forName("com.oplus.mydevices.quickapp.homecard.view.IndicatorButtonView", true, loader);
                Class<?> status = Class.forName("com.oplus.mydevices.domain.entities.cards.CastStatus", true, loader);
                Class<?> device = Class.forName("com.oplus.mydevices.domain.entities.device.DeviceType", true, loader);
                View button = (View) type.getConstructor(Context.class, android.util.AttributeSet.class).newInstance(host, null);
                type.getMethod("setDeviceType", device).invoke(button, HostReflect.enumValue(device, "OTHER"));
                int[] clicks = {0}; button.setOnClickListener(v -> clicks[0]++);
                type.getMethod("k", status, boolean.class).invoke(button, HostReflect.enumValue(status, "OFF"), true);
                check(button.isClickable(), "native power indicator accepts user input"); button.performClick();
                check(clicks[0] == 1, "native power click remains local simulated callback");
                type.getMethod("k", status, boolean.class).invoke(button, HostReflect.enumValue(status, "DISABLED"), false);
                check(!button.isClickable(), "native unavailable indicator disables input");
            } catch (Exception error) { throw new RuntimeException("Native power indicator regression", error); }
        });
    }
    private Object field(Object target, String name) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private Bundle command(String operation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (BridgeApplication.framework == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        if (BridgeApplication.framework == null) throw new IllegalStateException("LSPosed settings channel unavailable");
        var prefs = BridgeApplication.framework.getRemotePreferences(BridgeContract.PREFS);
        CountDownLatch latch = new CountDownLatch(1); Bundle[] result = {null}; int[] code = {-1};
        ResultReceiver reply = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int value, Bundle data) { code[0] = value; result[0] = data; latch.countDown(); }
        };
        getTargetContext().sendBroadcast(new Intent(BridgeContract.ACTION).setPackage(BridgeContract.SYSTEMUI).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("operation", operation).putExtra("reply", BridgeTransport.remoteReply(reply))
                .putExtra(BridgeContract.ENABLED, prefs.getBoolean(BridgeContract.ENABLED, true))
                .putExtra(BridgeContract.REVISION, prefs.getLong(BridgeContract.REVISION, 0)).putExtra(BridgeContract.SENT_AT, SystemClock.elapsedRealtime()));
        if (!latch.await(4, TimeUnit.SECONDS) || code[0] != 0) throw new IllegalStateException("Command failed: " + operation);
        return result[0];
    }
    private void foreground() { startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); waitForIdleSync(); }
    private void preview() throws Exception {
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Control light = control("smoke-preview", new ToggleRangeTemplate("preview-switch", true, "开关",
                new RangeTemplate("preview-level", 0, 100, 40, 1, "%.0f%%")), DeviceTypes.TYPE_LIGHT);
        CenterDevice row = new CenterDevice("smoke-preview", "模拟灯光（仅本地）", "测试房间", "模拟状态 · 不连接家电", true, false, false,
                new ControlCapabilities(light).bundle(DeviceTypes.TYPE_LIGHT), "", "", null);
        CenterControlDialog[] dialog = new CenterControlDialog[1];
        runOnMainSync(() -> dialog[0] = new CenterControlDialog(activity, () -> row, () -> false, (a,b,c) -> {}, () -> {}, () -> {}, () -> {}));
        checkpoint("simulated_dialog_visible"); Thread.sleep(30000); runOnMainSync(() -> dialog[0].dismiss());
    }
    private void checkpoint(String step) { Bundle data = new Bundle(); data.putString("step", step); sendStatus(0, data); }
    private void transportStart(Bundle result) throws Exception {
        foreground(); checkpoint("foreground");
        Bundle status = command(BridgeContract.STATUS);
        check(status.getString("build", "").equals(BuildConfig.VERSION_NAME), "current SystemUI module loaded");
        check(status.getBoolean("core_ready"), "independent backend installed");
        command(BridgeContract.CENTER_OPEN); checkpoint("center_open"); Thread.sleep(3500);
        Bundle before = command(BridgeContract.STATUS);
        check(before.getBoolean("center_compatible"), "current MyDevices hooks reported");
        check(before.getInt("center_clients") > 0, "authenticated subscription active");
        var stored = getTargetContext().getSharedPreferences("smoke_transport", 0).edit();
        for (String key : new String[]{"center_pushes", "center_heartbeats", "icon_bytes_sent"}) stored.putLong(key, before.getLong(key));
        stored.commit(); result.putInt("rendered", before.getInt("center_rendered"));
        result.putString("connection", before.getString("connection")); result.putInt("fresh_devices", before.getInt("fresh_devices"));
    }
    private void transportFinish(Bundle result) throws Exception {
        foreground(); Bundle after = command(BridgeContract.STATUS);
        var before = getTargetContext().getSharedPreferences("smoke_transport", 0);
        check(after.getLong("center_heartbeats") > before.getLong("center_heartbeats", Long.MAX_VALUE), "low-frequency heartbeat received");
        check(after.getLong("icon_bytes_sent") == before.getLong("icon_bytes_sent", -1), "cached icons not retransmitted during steady state");
        for (String key : new String[]{"center_pushes", "center_heartbeats", "icon_bytes_sent"}) result.putLong(key + "_delta", after.getLong(key) - before.getLong(key, 0));
        result.putInt("rendered", after.getInt("center_rendered")); result.putString("connection", after.getString("connection"));
    }
    private void layout(Bundle result) throws Exception {
        foreground(); Bundle devices = command(BridgeContract.DEVICES);
        java.util.ArrayList<Bundle> entries = devices.getParcelableArrayList("devices", Bundle.class);
        check(entries != null && entries.size() >= 2, "management sees saved favorites");
        var prefs = BridgeApplication.framework.getRemotePreferences(BridgeContract.PREFS);
        int user = devices.getInt("user", 0);
        DeviceLayoutPolicy.Options before = LayoutPreferences.read(prefs, user);
        java.util.ArrayList<String> order = new java.util.ArrayList<>(); for (Bundle device : entries) order.add(device.getString("id"));
        java.util.Collections.reverse(order);
        // Private target-app storage retains recovery data even if the instrumentation process is killed.
        getTargetContext().getSharedPreferences("smoke_recovery", 0).edit().putBoolean("pending", true).putInt("user", user)
                .putStringSet("hidden", before.hidden()).putString("order", DeviceLayoutPolicy.encodeOrder(before.order())).putBoolean("rooms", before.groupRooms()).commit();
        saveLayout(user, new DeviceLayoutPolicy.Options(java.util.Set.of(order.get(1)), order, true));
        command(BridgeContract.SYNC); command(BridgeContract.CENTER_OPEN); Thread.sleep(4000);
        Bundle after = command(BridgeContract.STATUS);
        check(after.getInt("native_devices") == entries.size() - 1, "hidden device omitted from bridge subscription");
        result.putInt("visible", after.getInt("native_devices")); result.putBoolean("group_rooms", true); result.putString("recovery", "pending");
    }
    private void saveLayout(int user, DeviceLayoutPolicy.Options options) {
        var prefs = BridgeApplication.framework.getRemotePreferences(BridgeContract.PREFS);
        prefs.edit().putStringSet(LayoutPreferences.key(user, "hidden"), options.hidden())
                .putString(LayoutPreferences.key(user, "order"), DeviceLayoutPolicy.encodeOrder(options.order()))
                .putBoolean(LayoutPreferences.key(user, "rooms"), options.groupRooms())
                .putLong(BridgeContract.REVISION, prefs.getLong(BridgeContract.REVISION, 0) + 1).commit();
    }
    private void restoreLayout(Bundle result) throws Exception {
        foreground(); var recovery = getTargetContext().getSharedPreferences("smoke_recovery", 0);
        check(recovery.getBoolean("pending", false), "layout recovery available");
        saveLayout(recovery.getInt("user", 0), new DeviceLayoutPolicy.Options(recovery.getStringSet("hidden", java.util.Set.of()),
                DeviceLayoutPolicy.decodeOrder(recovery.getString("order", "")), recovery.getBoolean("rooms", false)));
        command(BridgeContract.SYNC); command(BridgeContract.CENTER_OPEN); Thread.sleep(3500);
        Bundle after = command(BridgeContract.STATUS); result.putInt("visible", after.getInt("native_devices"));
        recovery.edit().clear().commit(); result.putString("recovery", "restored");
    }
    private void enabled(boolean restore, Bundle result) throws Exception {
        foreground(); var prefs = BridgeApplication.framework.getRemotePreferences(BridgeContract.PREFS);
        var recovery = getTargetContext().getSharedPreferences("smoke_enabled", 0);
        boolean value;
        if (restore) { check(recovery.contains("before"), "enabled recovery available"); value = recovery.getBoolean("before", true); }
        else { value = false; recovery.edit().putBoolean("before", prefs.getBoolean(BridgeContract.ENABLED, true)).commit(); }
        prefs.edit().putBoolean(BridgeContract.ENABLED, value).putLong(BridgeContract.REVISION, prefs.getLong(BridgeContract.REVISION, 0) + 1).commit();
        command(BridgeContract.SYNC); command(BridgeContract.CENTER_OPEN); Thread.sleep(3500);
        Bundle after = command(BridgeContract.STATUS);
        check(after.getInt("native_devices") == (value ? after.getInt("selected_devices") : 0), "enabled state matches displayed bridge rows");
        result.putInt("visible", after.getInt("native_devices")); result.putBoolean("enabled", value);
        if (restore) recovery.edit().clear().commit();
    }
}
