package io.github.coloros.mihomebridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.service.controls.Control;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** SystemUI owns Controls; only the verified same-user MyDevices UID can read its center snapshot. */
final class DeviceCenterHost extends BroadcastReceiver {
    private final NativeDeviceSpace space;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final class Image {
        final Icon source;
        byte[] png;
        Image(Icon source) { this.source = source; }
    }
    private final Map<String, Image> images = new HashMap<>();

    DeviceCenterHost(NativeDeviceSpace space, BooleanSupplier enabled, BiConsumer<String, Throwable> failure) {
        this.space = space; this.enabled = enabled; this.failure = failure;
    }

    void register(Context context) {
        // Separate from the signature-protected settings channel. Android's shared sender
        // identity is checked below before any result, visibility lease or PendingIntent is supplied.
        context.registerReceiver(this, new IntentFilter(BridgeContract.HOST_ACTION), null, main, Context.RECEIVER_EXPORTED);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!BridgeContract.HOST_ACTION.equals(intent.getAction()) || space == null) return;
        try {
            int installedUid = context.getPackageManager().getPackageUid(BridgeContract.MYDEVICES, PackageManager.PackageInfoFlags.of(0));
            if (!DeviceCenterPolicy.authorized(getSentFromUid(), getSentFromPackage(), installedUid, space.currentUserId(),
                    intent.getLongExtra(BridgeContract.SENT_AT, -1), SystemClock.elapsedRealtime())) return;
            String token = intent.getStringExtra("token");
            if (token == null || token.isBlank() || token.length() > 64) return;
            String operation = intent.getStringExtra("operation");
            if (BridgeContract.CENTER_CLOSE.equals(operation)) { space.centerVisible(token, false); return; }
            ResultReceiver reply = intent.getParcelableExtra("reply", ResultReceiver.class);
            if (reply == null) return;
            Bundle result = new Bundle();
            boolean active = enabled.getAsBoolean();
            result.putBoolean(BridgeContract.ENABLED, active);
            result.putInt("user", space.currentUserId());
            if (BridgeContract.CENTER_SNAPSHOT.equals(operation)) {
                space.centerVisible(token, active);
                result.putParcelableArrayList("rows", snapshot(context, active));
                reply.send(0, result);
            } else if (BridgeContract.CENTER_DETAIL.equals(operation)) {
                String id = intent.getStringExtra("id");
                PendingIntent detail = active && id != null && id.length() <= 1024 ? space.centerDetail(id) : null;
                if (detail != null) result.putParcelable("detail", detail);
                else result.putString("message", active ? "正在连接米家，请稍后重试" : "米家桥接已关闭");
                reply.send(detail == null ? 1 : 0, result);
            }
        } catch (Exception error) { failure.accept("Device center request failed", error); }
    }

    private ArrayList<Bundle> snapshot(Context context, boolean active) {
        ArrayList<Bundle> rows = new ArrayList<>();
        Set<String> retained = new HashSet<>();
        int budget = DeviceCenterPolicy.MAX_TOTAL_ICON_BYTES;
        if (active) for (MiHomeSession.Entry entry : space.centerEntries()) {
            if (rows.size() == DeviceCenterPolicy.MAX_ROWS) break;
            retained.add(entry.id());
            Bundle row = new Bundle();
            row.putString("id", bounded(entry.id(), 1024));
            row.putString("title", bounded(entry.title(), 160));
            row.putString("room", bounded(entry.subtitle(), 160));
            row.putString("status", bounded(NativeDeviceSpace.statusText(entry), 160));
            Control control = entry.control();
            row.putBoolean("online", control != null && control.getStatus() == Control.STATUS_OK);
            Icon icon = control == null ? null : control.getCustomIcon();
            byte[] png = image(context, entry.id(), icon);
            if (png != null && png.length <= budget) { row.putByteArray("icon", png); budget -= png.length; }
            rows.add(row);
        }
        images.keySet().retainAll(retained);
        return rows;
    }

    private byte[] image(Context context, String id, Icon source) {
        if (source == null) { images.remove(id); return null; }
        Image image = images.get(id);
        if (image != null && image.source == source) return image.png;
        Image request = new Image(source);
        images.put(id, request);
        source.loadDrawableAsync(context, drawable -> {
            if (drawable == null || images.get(id) != request || !enabled.getAsBoolean()) return;
            try {
                int w = Math.max(1, drawable.getIntrinsicWidth()), h = Math.max(1, drawable.getIntrinsicHeight());
                for (int size : new int[]{128, 96, 64, 32}) {
                    float scale = (float) size / Math.max(w, h);
                    Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                    drawable.draw(new Canvas(bitmap));
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    bitmap.recycle();
                    if (stream.size() <= DeviceCenterPolicy.MAX_ICON_BYTES) { request.png = stream.toByteArray(); break; }
                }
            } catch (RuntimeException error) { failure.accept("Device center icon decode failed", error); }
        }, main);
        return null;
    }

    private static String bounded(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
