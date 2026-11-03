package io.github.coloros.mihomebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView frameworkState, providerState, systemState, nativeState, centerState, connectionState, message;
    private Switch enabledSwitch;
    private boolean bindingSwitch;
    private boolean openingManagement;
    private boolean resumed;
    private long managementDeadline;
    private final Runnable managementLaunch = this::openPendingManagement;
    private interface Reply { void accept(boolean success, Bundle result); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));
        LinearLayout body = column();
        body.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(dp(22) + bars.left, dp(24) + bars.top, dp(22) + bars.right, dp(28) + bars.bottom);
            return insets;
        });
        text(body, "COLOROS 17  /  米家", 12, true, true);
        text(body, "让家，就在手边", 30, true, false);
        text(body, "米家设备，显示在控制中心和设备空间主列表。", 15, false, true);

        LinearLayout status = card(body);
        text(status, "连接状态", 19, true, false);
        frameworkState = text(status, "LSPosed · 正在检查", 14, false, true);
        providerState = text(status, "米家 · 正在检查", 14, false, true);
        systemState = text(status, "控制中心 · 正在检查", 14, false, true);
        nativeState = text(status, "设备空间 · 正在检查", 14, false, true);
        centerState = text(status, "设备中心 · 正在检查", 14, false, true);
        connectionState = text(status, "米家连接 · 正在检查", 14, false, true);
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用米家桥接");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setTextColor(getColor(R.color.text));
        enabledSwitch.setPadding(0, dp(10), 0, dp(4));
        status.addView(enabledSwitch);
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingSwitch && !saveEnabled(checked)) refresh();
        });

        LinearLayout setup = card(body);
        text(setup, "首次连接", 19, true, false);
        text(setup, "1  在 LSPosed 启用本模块，勾选「米家」「系统界面」和「设备空间」。\n2  重启手机，再打开米家确认已登录。\n3  打开设备面板，选择米家并保存设备。\n4  在控制中心操作小卡片，或进入设备空间查看原生设备大卡片。", 14, false, true);
        text(setup, "若一直停在连接中，请在米家的耗电管理中选择「完全允许后台行为」，再打开一次米家。", 14, false, true);
        button(setup, "打开米家", this::openMiHome);
        button(setup, "启用设备空间主列表", this::requestCenterScope);
        button(setup, "打开设备空间", () -> request(BridgeContract.SYSTEMUI, BridgeContract.CENTER_OPEN,
                (ok, result) -> { if (!ok) showResult(result); }));
        button(setup, "管理设备卡片 / 房间分组", this::manageDevices);
        button(setup, "添加完整面板快捷入口（可选）", () -> {
            if (!saveEnabled(true)) return;
            if (providerEnabled()) {
                addSystemEntry();
                return;
            }
            request(BridgeContract.MIHOME, BridgeContract.ADD, (ok, result) -> {
                if (!ok) { showResult(result); return; }
                addSystemEntry();
            });
        });
        button(setup, "选择设备 / 打开完整面板", () -> request(BridgeContract.SYSTEMUI, BridgeContract.OPEN,
                (ok, result) -> { if (!ok) showResult(result); }));
        text(setup, "控制中心小卡片：点击开关，长按详情。设备空间大卡片：右上角开关直接操作，点击卡片快捷调光、调温或执行场景，长按进入米家详情。可用操作以设备提供的能力为准。", 14, false, true);

        LinearLayout recovery = card(body);
        text(recovery, "管理与恢复", 19, true, false);
        button(recovery, "重新检查", this::refresh);
        button(recovery, "重新连接设备服务", () -> request(BridgeContract.SYSTEMUI, BridgeContract.RECONNECT, (ok, data) -> {
            showMessage(ok ? "正在连接米家设备服务" : data.getString("message"));
            handler.postDelayed(this::refresh, 1500); handler.postDelayed(this::refresh, 6000);
        }));
        button(recovery, "查看连接详情", this::showDiagnostics);
        button(recovery, "关闭并恢复本模块的改动", () -> new AlertDialog.Builder(this)
                .setTitle("关闭米家桥接？")
                .setMessage("恢复米家设备控制服务的原状态，并移除由本模块添加的入口。其他快捷开关会保留。系统可能清除已选的米家设备，重新启用后需要重新选择。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> restore()).show());
        message = text(body, "设备开关、在线状态和场景由米家提供；支持范围以米家设备面板为准。", 13, false, true);
        text(body, BuildConfig.VERSION_NAME + " · 适配基线 PLK110 / 17.0.0.105", 11, false, true);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.postDelayed(this::refresh, 500);
        managementDeadline = SystemClock.elapsedRealtime() + 5000;
        handler.post(managementLaunch);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (resumed) { managementDeadline = SystemClock.elapsedRealtime() + 5000; handler.removeCallbacks(managementLaunch); handler.post(managementLaunch); }
    }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(managementLaunch); super.onPause(); }
    private void openPendingManagement() {
        if (!resumed || isDestroyed() || !getIntent().getBooleanExtra("manage_devices", false)) return;
        if (BridgeApplication.framework == null) {
            if (SystemClock.elapsedRealtime() < managementDeadline) handler.postDelayed(managementLaunch, 100);
            else showMessage("LSPosed 尚未连接，请连接后点击管理设备卡片");
            return;
        }
        getIntent().removeExtra("manage_devices"); manageDevices();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private void refresh() {
        if (isDestroyed()) return;
        var service = BridgeApplication.framework;
        bindingSwitch = true;
        try {
            if (service == null) {
                frameworkState.setText("LSPosed · 尚未连接，请先启用模块");
                enabledSwitch.setEnabled(false);
            } else {
                List<String> scope = service.getScope();
                boolean complete = scope.contains(BridgeContract.MIHOME) && scope.contains(BridgeContract.SYSTEMUI)
                        && scope.contains(BridgeContract.MYDEVICES);
                frameworkState.setText(service.getFrameworkName() + " · " + (complete ? "作用域已配置" : "请勾选米家、系统界面和设备空间"));
                enabledSwitch.setEnabled(true);
                enabledSwitch.setChecked(service.getRemotePreferences(BridgeContract.PREFS).getBoolean(BridgeContract.ENABLED, true));
            }
        } catch (RuntimeException e) {
            frameworkState.setText("LSPosed · 连接暂不可用");
            enabledSwitch.setEnabled(false);
        } finally { bindingSwitch = false; }
        try {
            var pm = getPackageManager();
            var info = pm.getPackageInfo(BridgeContract.MIHOME, 0);
            ComponentName component = new ComponentName(BridgeContract.MIHOME, BridgeContract.SERVICE);
            var si = pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
            int state = pm.getComponentEnabledSetting(component);
            boolean on = si.applicationInfo.enabled && (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && si.enabled));
            providerState.setText("米家 " + info.versionName + " · 设备服务" + (on ? "已开启" : "未开启"));
        } catch (PackageManager.NameNotFoundException e) { providerState.setText("米家 · 未安装或没有设备控制服务"); }
        request(BridgeContract.SYSTEMUI, BridgeContract.STATUS, (ok, data) -> {
            if (!ok) systemState.setText("控制中心 · 模块尚未加载，请启用后重启");
            else if (!BuildConfig.VERSION_NAME.equals(data.getString("build"))) systemState.setText("系统界面 · 仍在运行旧版模块，请重启手机");
            else if (!data.getBoolean("core_ready")) systemState.setText("系统界面 · 设备服务接口不匹配");
            else if (!data.getBoolean("controls")) systemState.setText("控制中心 · 当前系统接口不匹配");
            else systemState.setText("系统界面 · 已连接");
            nativeState.setText(!ok ? "控制中心小卡片 · 等待连接" : !data.getBoolean("native_space") ? "控制中心小卡片 · 当前接口不匹配"
                    : !enabledSwitch.isChecked() ? "控制中心小卡片 · 已关闭" : data.getBoolean("native_panel_active") ? "控制中心小卡片 · 已展开" : "控制中心小卡片 · 适配就绪");
            centerState.setText(!ok ? "设备中心 · 等待连接" : !data.getBoolean("center_reported") ? "设备中心 · 尚未打开"
                    : !data.getBoolean("center_compatible") ? "设备中心 · 当前版本不兼容"
                    : !BuildConfig.VERSION_NAME.equals(data.getString("center_build")) ? "设备中心 · 仍在运行旧版模块，请重启手机"
                    : data.getInt("center_clients") > 0 ? "设备中心 · 已连接，显示 " + data.getInt("center_rendered") + " 台米家设备" : "设备中心 · 适配就绪");
            connectionState.setText(!ok ? "米家连接 · 等待连接" : connectionText(data));
        });
    }
    private String connectionText(Bundle data) {
        if (!enabledSwitch.isChecked()) return "米家连接 · 已关闭";
        String phase = data.getString("connection", "idle");
        return "米家连接 · " + switch (phase) {
            case "connected" -> "状态正常 · " + age(data.getLong("last_update"));
            case "connecting" -> "正在连接";
            case "retrying" -> "正在重连（第 " + data.getInt("retry_count") + " 次）";
            case "failed" -> "连接中断，请重新连接设备服务";
            case "empty" -> data.getInt("selected_devices") == 0 ? "尚未选择米家设备" : "已隐藏全部设备卡片";
            case "paused" -> "按需连接 · " + age(data.getLong("last_update"));
            default -> "等待打开设备列表";
        };
    }
    private String age(long time) { return time <= 0 ? "暂未收到状态" : "上次更新 " + Math.max(0, (SystemClock.elapsedRealtime() - time) / 1000) + " 秒前"; }
    private void manageDevices() {
        if (isDestroyed() || openingManagement) return;
        if (BridgeApplication.framework == null) { showMessage("请先在 LSPosed 启用模块，再重新打开设置"); return; }
        openingManagement = true;
        request(BridgeContract.SYSTEMUI, BridgeContract.DEVICES, (ok, data) -> {
            openingManagement = false;
            if (!ok) { showResult(data); return; }
            int user = data.getInt("user", -1);
            var devices = data.getParcelableArrayList("devices", Bundle.class);
            if (user < 0 || devices == null || devices.isEmpty()) { showMessage("尚未选择米家设备，请先在完整面板添加设备"); return; }
            try {
                var service = BridgeApplication.framework;
                if (service == null) throw new IllegalStateException("LSPosed 连接已断开，请重试");
                var prefs = service.getRemotePreferences(BridgeContract.PREFS);
                new DeviceManagerDialog(this, devices, LayoutPreferences.read(prefs, user), options -> {
                    try {
                        long revision = Math.addExact(prefs.getLong(BridgeContract.REVISION, 0), 1);
                        if (!prefs.edit().putStringSet(LayoutPreferences.key(user, "hidden"), options.hidden())
                                .putString(LayoutPreferences.key(user, "order"), DeviceLayoutPolicy.encodeOrder(options.order()))
                                .putBoolean(LayoutPreferences.key(user, "rooms"), options.groupRooms()).putLong(BridgeContract.REVISION, revision).commit())
                            throw new IllegalStateException("保存失败，请重试");
                        request(BridgeContract.SYSTEMUI, BridgeContract.SYNC, (saved, result) -> { showMessage(saved ? "卡片显示设置已保存" : result.getString("message")); refresh(); });
                    } catch (RuntimeException error) { showMessage(error.getMessage()); }
                });
            } catch (RuntimeException error) { showMessage(error.getMessage()); }
        });
    }
    private void showDiagnostics() {
        request(BridgeContract.SYSTEMUI, BridgeContract.STATUS, (ok, data) -> {
            if (!ok) { showResult(data); return; }
            String text = "模块版本：" + BuildConfig.VERSION_NAME + "\n系统界面加载：" + data.getString("build", "未知")
                    + "\n设备中心加载：" + data.getString("center_build", "尚未启动") + "\n" + connectionText(data)
                    + "\n已选设备：" + data.getInt("selected_devices") + "，当前收到状态：" + data.getInt("fresh_devices")
                    + "\n状态推送：" + data.getLong("center_pushes") + " 次，心跳：" + data.getLong("center_heartbeats") + " 次"
                    + "\n图片编码：" + data.getLong("icon_encodes") + " 次，传输：" + data.getLong("icon_bytes_sent") / 1024 + " KiB";
            if (!data.getString("connection_error", "").isBlank()) text += "\n连接提示：" + data.getString("connection_error");
            if (!data.getString("center_error", "").isBlank()) text += "\n设备中心适配：" + data.getString("center_error");
            new AlertDialog.Builder(this).setTitle("连接详情").setMessage(text).setPositiveButton("关闭", null).show();
        });
    }
    private void requestCenterScope() {
        try {
            var service = BridgeApplication.framework;
            if (service == null) throw new IllegalStateException("请先在 LSPosed 启用本模块。");
            if (service.getScope().contains(BridgeContract.MYDEVICES)) { showMessage("设备空间作用域已配置，更新模块后重启手机即可加载新版"); return; }
            service.requestScope(List.of(BridgeContract.MYDEVICES), new io.github.libxposed.service.XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> scope) {
                    handler.post(() -> { if (!isDestroyed()) { showMessage("设备空间作用域已启用，重启设备空间后生效。"); refresh(); } });
                }
                @Override public void onScopeRequestFailed(String reason) {
                    handler.post(() -> { if (!isDestroyed()) showMessage("请在 LSPosed 的本模块作用域中勾选「设备空间」。" + reason); });
                }
            });
        } catch (RuntimeException error) { showMessage(error.getMessage()); }
    }
    private boolean saveEnabled(boolean value) {
        try {
            var service = BridgeApplication.framework;
            if (service == null) throw new IllegalStateException("请先在 LSPosed 中启用本模块。");
            SharedPreferences prefs = service.getRemotePreferences(BridgeContract.PREFS);
            long revision = Math.addExact(prefs.getLong(BridgeContract.REVISION, 0), 1);
            if (!prefs.edit().putBoolean(BridgeContract.ENABLED, value)
                    .putLong(BridgeContract.REVISION, revision).commit())
                throw new IllegalStateException("开关保存失败，请重试。");
            bindingSwitch = true;
            enabledSwitch.setChecked(value);
            bindingSwitch = false;
            request(BridgeContract.SYSTEMUI, BridgeContract.SYNC, (ok, data) -> {
                if (!ok) showResult(data);
            });
            return true;
        } catch (RuntimeException e) { showMessage(e.getMessage()); return false; }
    }
    private void restore() {
        if (!saveEnabled(false)) return;
        request(BridgeContract.MIHOME, BridgeContract.RESTORE, (miOk, mi) ->
                request(BridgeContract.SYSTEMUI, BridgeContract.RESTORE, (uiOk, ui) -> {
                    showMessage(miOk && uiOk ? "已关闭并恢复。现在可以在 LSPosed 禁用或卸载模块。"
                            : "恢复尚未完成。" + (miOk ? "" : " 米家：" + mi.getString("message"))
                            + (uiOk ? "" : " 控制中心：" + ui.getString("message")));
                    refresh();
                }));
    }
    private void request(String target, String operation, Reply callback) {
        SharedPreferences prefs;
        try {
            var framework = BridgeApplication.framework;
            if (framework == null) throw new IllegalStateException("请先在 LSPosed 启用本模块。");
            prefs = framework.getRemotePreferences(BridgeContract.PREFS);
        } catch (RuntimeException error) {
            Bundle data = new Bundle();
            data.putString("message", error.getMessage());
            callback.accept(false, data);
            return;
        }
        AtomicBoolean finished = new AtomicBoolean();
        ResultReceiver receiver = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!isDestroyed() && finished.compareAndSet(false, true)) callback.accept(code == 0, data == null ? new Bundle() : data);
            }
        };
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
        sendBroadcast(new Intent(BridgeContract.ACTION).setPackage(target)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(BridgeContract.ENABLED, prefs.getBoolean(BridgeContract.ENABLED, true))
                .putExtra(BridgeContract.REVISION, prefs.getLong(BridgeContract.REVISION, 0))
                .putExtra(BridgeContract.SENT_AT, SystemClock.elapsedRealtime())
                .putExtra("operation", operation).putExtra("reply", BridgeTransport.remoteReply(receiver)), null, options.toBundle());
        handler.postDelayed(() -> {
            if (!isDestroyed() && finished.compareAndSet(false, true)) {
                Bundle data = new Bundle();
                data.putString("message", target.equals(BridgeContract.MIHOME)
                        ? "米家暂未响应，请打开米家后返回重试；首次启用模块后需重启。"
                        : "系统界面模块未响应，请勾选作用域并重启手机。");
                callback.accept(false, data);
            }
        }, BridgeContract.COMMAND_TIMEOUT_MS);
    }
    private boolean providerEnabled() {
        try {
            var pm = getPackageManager();
            ComponentName component = new ComponentName(BridgeContract.MIHOME, BridgeContract.SERVICE);
            var info = pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
            int state = pm.getComponentEnabledSetting(component);
            return info.applicationInfo.enabled && (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled));
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }
    private void addSystemEntry() {
        request(BridgeContract.SYSTEMUI, BridgeContract.ADD, (ok, result) -> {
            showMessage(ok ? "已添加。下拉控制中心，点击「设备控制」，选择米家并添加设备。" : result.getString("message"));
            refresh();
        });
    }
    private void openMiHome() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(BridgeContract.MIHOME);
        if (intent == null) showMessage("请先安装米家。");
        else startActivity(intent);
    }
    private void showResult(Bundle data) { showMessage(data.getString("message", "操作未完成，请重试。")); }
    private void showMessage(String value) { if (!isDestroyed()) message.setText(value == null ? "请重试。" : value); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return layout;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.card));
        background.setCornerRadius(dp(22));
        card.setBackground(background);
        card.setPadding(dp(20), dp(15), dp(20), dp(15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(18);
        parent.addView(card, params);
        return card;
    }
    private TextView text(LinearLayout parent, String value, int size, boolean bold, boolean muted) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(muted ? R.color.muted : R.color.text));
        view.setLineSpacing(dp(3), 1.1f);
        view.setPadding(0, dp(6), 0, dp(6));
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(50));
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
