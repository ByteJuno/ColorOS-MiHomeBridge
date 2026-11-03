package io.github.coloros.mihomebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native Android controls displayed in MyDevices; programmatic refresh never sends an appliance action. */
final class CenterControlDialog {
    interface Action { void send(String operation, boolean checked, float value); }
    private final Supplier<CenterDevice> source;
    private final BooleanSupplier busy;
    private final AlertDialog dialog;
    private final TextView status, rangeValue;
    private final Switch toggle;
    private final SeekBar range;
    private final Button execute, retry;
    private float min, max, step;
    private String format = "";
    private boolean dragging;

    CenterControlDialog(Activity activity, Supplier<CenterDevice> source, BooleanSupplier busy,
                        Action action, Runnable details, Runnable retryAction, Runnable management) {
        this.source = source; this.busy = busy;
        CenterDevice initial = source.get();
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density); body.setPadding(padding, padding / 2, padding, padding / 2);
        status = new TextView(activity); status.setTextSize(14); body.addView(status);
        toggle = new Switch(activity); toggle.setText("开关"); toggle.setTextSize(17); body.addView(toggle);
        rangeValue = new TextView(activity); rangeValue.setTextSize(16); body.addView(rangeValue);
        range = new SeekBar(activity); body.addView(range);
        execute = new Button(activity); execute.setText("执行场景"); body.addView(execute);
        retry = new Button(activity); retry.setText("重新连接"); retry.setOnClickListener(v -> retryAction.run()); body.addView(retry);
        Button manage = new Button(activity); manage.setText("管理设备卡片"); body.addView(manage);
        dialog = new AlertDialog.Builder(activity).setTitle(initial == null ? "米家设备" : initial.title()).setView(body)
                .setPositiveButton("米家详情", (d, which) -> details.run()).setNegativeButton("关闭", null).create();
        manage.setOnClickListener(v -> { dialog.dismiss(); management.run(); });
        toggle.setOnClickListener(v -> {
            CenterDevice row = source.get(); if (row == null) return;
            boolean requested = toggle.isChecked(); toggle.setChecked(row.checked());
            if (row.ready() && !busy.getAsBoolean()) action.send("toggle", requested, 0);
        });
        execute.setOnClickListener(v -> { CenterDevice row = source.get(); if (row != null && row.ready() && !busy.getAsBoolean()) action.send("command", false, 0); });
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { if (user) rangeValue.setText(label(value(progress))); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false; CenterDevice row = source.get();
                if (row != null && row.ready() && !busy.getAsBoolean()) try { action.send("range", false, value(bar.getProgress())); }
                catch (IllegalArgumentException changed) { status.setText("设备调节范围已变化，请重试"); }
                refresh();
            }
        });
        dialog.show(); refresh();
    }
    private float value(int progress) {
        float proposed = min + (max - min) * progress / Math.max(1, range.getMax());
        return ControlValuePolicy.normalize(Math.max(min, Math.min(max, proposed)), min, max, step);
    }
    private String label(float value) {
        if (!format.isBlank() && format.length() < 96) try { return String.format(Locale.getDefault(), format, value); }
        catch (RuntimeException ignored) { }
        return String.format(Locale.getDefault(), "%.2f", value);
    }
    void refresh() {
        if (!dialog.isShowing()) return;
        CenterDevice row = source.get();
        if (row == null) { dialog.dismiss(); return; }
        boolean pending = busy.getAsBoolean(), ready = row.ready() && !pending;
        dialog.setTitle(row.title()); status.setText(row.displayStatus(pending));
        toggle.setVisibility(row.toggle() ? View.VISIBLE : View.GONE); toggle.setEnabled(ready); toggle.setChecked(row.checked());
        execute.setVisibility(row.command() ? View.VISIBLE : View.GONE); execute.setEnabled(ready);
        range.setVisibility(row.range() ? View.VISIBLE : View.GONE); rangeValue.setVisibility(row.range() ? View.VISIBLE : View.GONE); range.setEnabled(ready);
        retry.setVisibility(row.stale() ? View.VISIBLE : View.GONE);
        if (row.range() && !dragging) {
            min = row.capabilities().getFloat("min"); max = row.capabilities().getFloat("max"); step = row.capabilities().getFloat("step");
            format = row.capabilities().getString("format", "");
            if (!Float.isFinite(min) || !Float.isFinite(max) || !Float.isFinite(step) || max <= min || step <= 0) { range.setEnabled(false); return; }
            range.setMax(Math.max(1, Math.min(10000, Math.round((max - min) / step))));
            float value = row.capabilities().getFloat("value", min);
            range.setProgress(Math.round((value - min) * range.getMax() / (max - min)));
            rangeValue.setText(row.capabilities().getString("range_title", "调节") + "  " + label(value));
        }
    }
    void dismiss() { dialog.dismiss(); }
}
