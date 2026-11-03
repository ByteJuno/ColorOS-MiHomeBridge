package io.github.coloros.mihomebridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** User-owned visibility/order settings; none of these controls invokes the appliance provider. */
final class DeviceManagerDialog {
    private record Device(String id, String title, String room) {}
    private final Activity activity;
    private final List<Device> original = new ArrayList<>(), order = new ArrayList<>();
    private final Set<String> hidden;
    private final LinearLayout list;
    private final TextView count;

    DeviceManagerDialog(Activity activity, List<Bundle> input, DeviceLayoutPolicy.Options options, Consumer<DeviceLayoutPolicy.Options> save) {
        this.activity = activity; hidden = new HashSet<>(options.hidden());
        for (Bundle item : input) original.add(new Device(item.getString("id", ""), item.getString("title", "米家设备"), item.getString("room", "")));
        order.addAll(DeviceLayoutPolicy.arrange(original, new DeviceLayoutPolicy.Options(Set.of(), options.order(), false), Device::id, Device::room));
        LinearLayout body = column(); int pad = dp(20); body.setPadding(pad, dp(8), pad, dp(8));
        TextView description = new TextView(activity); description.setText("选择要显示的卡片并调整顺序。隐藏只影响桥接卡片，米家收藏会保留。"); description.setTextSize(14); body.addView(description);
        Switch group = new Switch(activity); group.setText("按房间分组"); group.setChecked(options.groupRooms()); body.addView(group);
        count = new TextView(activity); body.addView(count);
        ScrollView scroll = new ScrollView(activity); list = column(); scroll.addView(list);
        int listHeight = Math.min(dp(Math.max(140, Math.min(420, original.size() * 64))), activity.getResources().getDisplayMetrics().heightPixels / 2);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, listHeight));
        Button reset = new Button(activity); reset.setText("恢复默认显示和顺序");
        reset.setOnClickListener(v -> { hidden.clear(); order.clear(); order.addAll(original); group.setChecked(false); render(); }); body.addView(reset);
        render();
        new AlertDialog.Builder(activity).setTitle("管理设备卡片").setView(body).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> save.accept(new DeviceLayoutPolicy.Options(hidden, order.stream().map(Device::id).toList(), group.isChecked()))).show();
    }
    private void render() {
        list.removeAllViews(); updateCount();
        for (int i = 0; i < order.size(); i++) {
            int index = i; Device device = order.get(i);
            LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
            CheckBox shown = new CheckBox(activity); shown.setText(device.title + "\n" + DeviceLayoutPolicy.roomName(device.room)); shown.setChecked(!hidden.contains(device.id));
            shown.setOnCheckedChangeListener((button, checked) -> { if (checked) hidden.remove(device.id); else hidden.add(device.id); updateCount(); });
            row.addView(shown, new LinearLayout.LayoutParams(0, -2, 1));
            Button up = new Button(activity); up.setText("↑"); up.setContentDescription("上移" + device.title); up.setEnabled(i > 0); up.setMinWidth(0);
            up.setOnClickListener(v -> { java.util.Collections.swap(order, index, index - 1); render(); }); row.addView(up, new LinearLayout.LayoutParams(dp(48), dp(48)));
            Button down = new Button(activity); down.setText("↓"); down.setContentDescription("下移" + device.title); down.setEnabled(i + 1 < order.size()); down.setMinWidth(0);
            down.setOnClickListener(v -> { java.util.Collections.swap(order, index, index + 1); render(); }); row.addView(down, new LinearLayout.LayoutParams(dp(48), dp(48)));
            list.addView(row);
        }
    }
    private void updateCount() { count.setText("显示 " + order.stream().filter(device -> !hidden.contains(device.id)).count() + " / " + order.size() + " 台"); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
