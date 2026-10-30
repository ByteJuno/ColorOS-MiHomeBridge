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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView frameworkState, providerState, systemState, nativeState, message;
    private Switch enabledSwitch, placementSwitch, tapSwitch;
    private boolean bindingSwitch;
    private interface Reply { void accept(boolean success, Bundle result); }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));
        LinearLayout body = column();
        body.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(body);
        // A scrim the height of the status bar keeps scrolled text from running under the clock.
        View scrim = new View(this);
        scrim.setBackgroundColor(getColor(R.color.background));
        scrim.setClickable(false);
        scrim.setFocusable(false);
        FrameLayout root = new FrameLayout(this);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            body.setPadding(dp(18) + bars.left, dp(20) + bars.top, dp(18) + bars.right, dp(28) + bars.bottom);
            ViewGroup.LayoutParams params = scrim.getLayoutParams();
            if (params.height != bars.top) { params.height = bars.top; scrim.setLayoutParams(params); }
            return insets;
        });
        text(body, "COLOROS 17  /  米家", 12, true, true);
        text(body, "让家，就在手边", 30, true, false);
        text(body, "米家设备，显示在控制中心和设备空间主列表。", 15, false, true);

        heading(body, "连接状态");
        LinearLayout status = card(body);
        frameworkState = value(status, "框架", "正在检查");
        divider(status);
        providerState = value(status, "米家", "正在检查");
        divider(status);
        systemState = value(status, "控制中心", "正在检查");
        divider(status);
        nativeState = value(status, "设备空间", "正在检查");
        divider(status);
        enabledSwitch = switchRow(status, "启用米家桥接", "关闭后立即移除注入的卡片，并释放米家的订阅。");
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingSwitch && !saveEnabled(checked)) refresh();
        });

        heading(body, "显示与交互");
        LinearLayout placement = card(body);
        placementSwitch = switchRow(placement, "主列表米家设备排在原生设备之前",
                "仅影响设备空间主列表，默认保持 OPPO 原有设备在前。控制中心的设备空间只有 1～3 个卡位，米家卡片始终排在最前以保证可见。");
        placementSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingSwitch) savePlacement(checked);
        });
        divider(placement);
        tapSwitch = switchRow(placement, "点击大卡片直接开关设备",
                "关闭后点击打开米家详情，与系统卡片一致；长按始终打开详情。");
        tapSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingSwitch) saveOption(BridgeContract.TAP,
                    checked ? BridgeContract.TAP_TOGGLE : BridgeContract.TAP_DETAILS, tapSwitch, checked);
        });

        heading(body, "首次连接");
        LinearLayout setup = card(body);
        action(setup, "打开米家", "确认已登录，并让设备控制服务保持可用。", this::openMiHome);
        divider(setup);
        action(setup, "启用设备空间主列表", "在 LSPosed 作用域中补充「设备空间」。", this::requestCenterScope);
        divider(setup);
        action(setup, "打开设备空间", "查看原生设备卡片，米家设备已追加在列表中。", () ->
                request(BridgeContract.SYSTEMUI, BridgeContract.CENTER_OPEN, (ok, result) -> { if (!ok) showResult(result); }));
        divider(setup);
        action(setup, "添加完整面板快捷入口", "可选。添加控制中心里的「设备控制」开关。", () -> {
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
        divider(setup);
        action(setup, "选择设备，打开完整面板", "在完整面板里挑选要显示的设备与手动场景。", () ->
                request(BridgeContract.SYSTEMUI, BridgeContract.OPEN, (ok, result) -> { if (!ok) showResult(result); }));
        note(body, "控制中心小卡片：点击开关，长按详情。设备空间大卡片：默认点击直接开关，可在「显示与交互」中改为打开米家详情。调光、调温等操作在完整面板中使用。");
        note(body, "若一直停在连接中，请在米家的耗电管理中选择「完全允许后台行为」，再打开一次米家。");

        heading(body, "管理与恢复");
        LinearLayout recovery = card(body);
        action(recovery, "重新检查", "重新读取三个宿主进程的适配状态。", this::refresh);
        divider(recovery);
        action(recovery, "关闭并恢复本模块的改动", "恢复米家服务原状态，并移除本模块添加的入口。",
                () -> new AlertDialog.Builder(this)
                        .setTitle("关闭米家桥接？")
                        .setMessage("恢复米家设备控制服务的原状态，并移除由本模块添加的入口。其他快捷开关会保留。系统可能清除已选的米家设备，重新启用后需要重新选择。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("恢复", (dialog, which) -> restore()).show());

        message = text(body, "设备开关、在线状态和场景由米家提供；支持范围以米家设备面板为准。", 13, false, true);
        text(body, BuildConfig.VERSION_NAME + " · 适配基线 PLK110 / 17.0.0.105", 11, false, true);
        setContentView(root);
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
                frameworkState.setText("尚未连接，请先启用模块");
                enabledSwitch.setEnabled(false);
                placementSwitch.setEnabled(false);
                tapSwitch.setEnabled(false);
            } else {
                List<String> scope = service.getScope();
                boolean complete = scope.contains(BridgeContract.MIHOME) && scope.contains(BridgeContract.SYSTEMUI)
                        && scope.contains(BridgeContract.MYDEVICES);
                frameworkState.setText(service.getFrameworkName() + (complete ? " · 作用域已配置" : " · 请勾选米家、系统界面和设备空间"));
                enabledSwitch.setEnabled(true);
                placementSwitch.setEnabled(true);
                tapSwitch.setEnabled(true);
                SharedPreferences prefs = service.getRemotePreferences(BridgeContract.PREFS);
                enabledSwitch.setChecked(prefs.getBoolean(BridgeContract.ENABLED, true));
                placementSwitch.setChecked(BridgeContract.PREPEND.equals(
                        prefs.getString(BridgeContract.PLACEMENT, BridgeContract.APPEND)));
                tapSwitch.setChecked(!BridgeContract.TAP_DETAILS.equals(
                        prefs.getString(BridgeContract.TAP, BridgeContract.TAP_TOGGLE)));
            }
        } catch (RuntimeException e) {
            frameworkState.setText("连接暂不可用");
            enabledSwitch.setEnabled(false);
            placementSwitch.setEnabled(false);
            tapSwitch.setEnabled(false);
        } finally { bindingSwitch = false; }
        try {
            var pm = getPackageManager();
            var info = pm.getPackageInfo(BridgeContract.MIHOME, 0);
            providerState.setText(info.versionName + " · 设备服务" + (providerEnabled() ? "已开启" : "未开启"));
        } catch (PackageManager.NameNotFoundException e) { providerState.setText("未安装或没有设备控制服务"); }
        request(BridgeContract.SYSTEMUI, BridgeContract.STATUS, (ok, data) -> {
            if (!ok) systemState.setText("模块尚未加载，请启用后重启");
            else if (!data.getBoolean("controls")) systemState.setText("当前系统接口不匹配");
            else systemState.setText(data.getBoolean("tile") ? "入口已添加" : "已连接，可添加入口");
            nativeState.setText(!ok ? "等待系统界面连接"
                    : !data.getBoolean("native_space") ? "当前接口不匹配"
                    : !enabledSwitch.isChecked() ? "已关闭"
                    : data.getInt("native_bound_cards") > 0 ? "原生卡片已加载，已选 " + data.getInt("native_devices") + " 台设备"
                    : "适配已加载，请展开控制中心");
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
    private void savePlacement(boolean prepend) {
        saveOption(BridgeContract.PLACEMENT, prepend ? BridgeContract.PREPEND : BridgeContract.APPEND,
                placementSwitch, prepend);
    }
    /** Persists a display option and asks SystemUI to re-publish with the new value. */
    private void saveOption(String key, String value, Switch control, boolean shown) {
        try {
            var service = BridgeApplication.framework;
            if (service == null) throw new IllegalStateException("请先在 LSPosed 中启用本模块。");
            SharedPreferences prefs = service.getRemotePreferences(BridgeContract.PREFS);
            if (!prefs.edit().putString(key, value).commit())
                throw new IllegalStateException("设置保存失败，请重试。");
            // Re-publishing the panel picks the new value up without waiting for the next event.
            request(BridgeContract.SYSTEMUI, BridgeContract.SYNC, (ok, data) -> {
                if (!ok) showResult(data);
            });
        } catch (RuntimeException e) {
            showMessage(e.getMessage());
            bindingSwitch = true;
            control.setChecked(!shown);
            bindingSwitch = false;
        }
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
            return ProviderPolicy.available(info.applicationInfo.enabled,
                    pm.getComponentEnabledSetting(component), info.enabled);
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
    private void heading(LinearLayout parent, String title) {
        TextView view = text(parent, title, 13, true, true);
        view.setPadding(dp(8), dp(22), dp(8), dp(8));
    }
    private void note(LinearLayout parent, String value) {
        TextView view = text(parent, value, 13, false, true);
        view.setPadding(dp(8), dp(10), dp(8), 0);
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.card));
        background.setCornerRadius(dp(24));
        card.setBackground(background);
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(2);
        parent.addView(card, params);
        return card;
    }
    private LinearLayout row(LinearLayout card) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(18), dp(12), dp(16), dp(12));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
    private LinearLayout labels(LinearLayout row, String title, String subtitle) {
        LinearLayout stack = column();
        stack.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        text(stack, title, 16, false, false);
        if (!TextUtils.isEmpty(subtitle)) text(stack, subtitle, 13, false, true);
        row.addView(stack);
        return stack;
    }
    private TextView value(LinearLayout card, String label, String initial) {
        LinearLayout row = row(card);
        text(row, label, 16, false, false).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text(row, initial, 13, false, true);
        value.setGravity(Gravity.END);
        return value;
    }
    private Switch switchRow(LinearLayout card, String title, String subtitle) {
        LinearLayout row = row(card);
        labels(row, title, subtitle);
        Switch toggle = new Switch(this);
        toggle.setTextSize(15);
        toggle.setPadding(dp(8), 0, 0, 0);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return toggle;
    }
    private void action(LinearLayout card, String title, String subtitle, Runnable action) {
        LinearLayout row = row(card);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true))
            row.setBackgroundResource(ripple.resourceId);
        labels(row, title, subtitle);
        text(row, "›", 22, false, true).setPadding(dp(10), 0, 0, 0);
        row.setOnClickListener(v -> action.run());
    }
    private void divider(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        params.leftMargin = dp(18);
        params.rightMargin = dp(18);
        card.addView(line, params);
    }
    private TextView text(LinearLayout parent, String value, int size, boolean bold, boolean muted) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(muted ? R.color.muted : R.color.text));
        view.setLineSpacing(dp(2), 1.12f);
        view.setPadding(0, dp(2), 0, dp(2));
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Inside a horizontal row only the label column may stretch; everything else wraps.
        int width = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        parent.addView(view, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
