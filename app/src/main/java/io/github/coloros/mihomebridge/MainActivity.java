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
    private TextView frameworkState, providerState, systemState, nativeState, message;
    private Switch enabledSwitch;
    private boolean bindingSwitch;
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
        text(setup, "控制中心小卡片：点击开关，长按详情。设备空间大卡片：点击进入对应的米家详情。调光、调温等操作也可在完整面板中使用。", 14, false, true);

        LinearLayout recovery = card(body);
        text(recovery, "管理与恢复", 19, true, false);
        button(recovery, "重新检查", this::refresh);
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
        handler.postDelayed(this::refresh, 500);
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
            else if (!data.getBoolean("controls")) systemState.setText("控制中心 · 当前系统接口不匹配");
            else systemState.setText("控制中心 · " + (data.getBoolean("tile") ? "入口已添加" : "已连接，可添加入口"));
            nativeState.setText(!ok ? "设备空间 · 等待系统界面连接"
                    : !data.getBoolean("native_space") ? "设备空间 · 当前接口不匹配"
                    : !enabledSwitch.isChecked() ? "设备空间 · 已关闭"
                    : data.getInt("native_bound_cards") > 0 ? "设备空间 · 原生卡片已加载，已选 " + data.getInt("native_devices") + " 台设备"
                    : "设备空间 · 适配已加载，请展开控制中心");
        });
    }
    private void requestCenterScope() {
        try {
            var service = BridgeApplication.framework;
            if (service == null) throw new IllegalStateException("请先在 LSPosed 启用本模块。");
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
