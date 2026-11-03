package io.github.coloros.mihomebridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Main-thread index; all drawable loading, rasterization, hashing and PNG work runs on one bounded worker. */
final class DeviceIconCache {
    static final class Image {
        Icon source;
        Object request;
        String hash = "";
        byte[] png;
    }
    private final Handler main;
    private final Runnable changed;
    private final BiConsumer<String, Throwable> failure;
    private final Map<String, Image> images = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(DeviceCenterPolicy.MAX_ROWS), task -> {
                Thread thread = new Thread(task, "MiHomeBridge-icons"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private int user = -1, generation;
    private long encoded, bytesSent;
    DeviceIconCache(Handler main, Runnable changed, BiConsumer<String, Throwable> failure) {
        this.main = main; this.changed = changed; this.failure = failure;
        worker.allowCoreThreadTimeOut(true);
    }
    void forUser(int current) { if (user != current) { clear(); user = current; } }
    void clear() { generation++; images.clear(); worker.getQueue().clear(); }
    Image image(Context context, String id, Icon source) {
        Image cached = images.get(id);
        if (source == null) return cached;
        if (cached == null) { cached = new Image(); images.put(id, cached); }
        if (cached.source == source && (cached.request != null || cached.png != null)) return cached;
        Image entry = cached;
        Object token = new Object(); entry.source = source; entry.request = token;
        int epoch = generation;
        try { worker.execute(() -> encode(context, id, entry, source, token, epoch)); }
        catch (RuntimeException rejected) { entry.request = null; entry.source = null; }
        return entry;
    }
    void retain(java.util.Set<String> ids) { images.keySet().retainAll(ids); }
    private void encode(Context context, String id, Image entry, Icon source, Object token, int epoch) {
        byte[] png = null; String hash = "";
        try {
            Drawable drawable = source.loadDrawable(context);
            if (drawable != null) {
                int w = Math.max(1, drawable.getIntrinsicWidth()), h = Math.max(1, drawable.getIntrinsicHeight());
                for (int edge : DeviceCenterPolicy.iconSteps(context.getResources().getDisplayMetrics().density)) {
                    float scale = (float) edge / Math.max(w, h);
                    Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
                    try {
                        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight()); drawable.draw(new Canvas(bitmap));
                        ByteArrayOutputStream output = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                        if (output.size() <= DeviceCenterPolicy.MAX_ICON_BYTES) { png = output.toByteArray(); break; }
                    } finally { bitmap.recycle(); }
                }
                if (png != null) {
                    StringBuilder digest = new StringBuilder();
                    for (byte value : MessageDigest.getInstance("SHA-256").digest(png)) {
                        digest.append(Character.forDigit((value >>> 4) & 15, 16)); digest.append(Character.forDigit(value & 15, 16));
                    }
                    hash = digest.toString();
                }
            }
        } catch (Exception error) { failure.accept("MiHome image worker failed", error); }
        byte[] finished = png; String digest = hash;
        main.post(() -> {
            if (epoch != generation || images.get(id) != entry || entry.request != token) return;
            entry.request = null;
            if (finished == null) { entry.source = null; return; }
            encoded++;
            boolean updated = !digest.equals(entry.hash);
            entry.png = finished; entry.hash = digest;
            if (updated) changed.run();
        });
    }
    void sent(int bytes) { bytesSent += bytes; }
    long encoded() { return encoded; }
    long bytesSent() { return bytesSent; }
}
