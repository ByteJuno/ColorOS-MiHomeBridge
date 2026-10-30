package io.github.coloros.mihomebridge;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Adds transient MiHome DTOs to MyDevices' real home-card adapter. Never writes its device database. */
final class NativeDeviceCenter {
    private static final String HOME = "com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity";
    private static final String DOMAIN = "com.oplus.mydevices.domain.entities.";
    private static final String ICON = "coloros-mihome-icon:";
    private final XposedModule module;
    private final BooleanSupplier prepend;
    private final BiConsumer<String, Throwable> failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, Page> pages = new WeakHashMap<>();
    private final Map<View, String> cardIds = new WeakHashMap<>();
    private final Map<View, GestureDetector> gestures = new WeakHashMap<>();
    private Map<String, Row> images = Map.of();
    private Class<?> dataType, deviceType, connectType, displayType;
    private Constructor<?> dataConstructor, statusConstructor;
    private Method update, displayFactory;
    private Object other, none, offlineRing, displayCompanion;

    private record Row(String id, String title, String room, String status, boolean online, int imageHash, Bitmap bitmap) {
        boolean same(Row row) {
            return id.equals(row.id) && title.equals(row.title) && room.equals(row.room)
                    && status.equals(row.status) && online == row.online && imageHash == row.imageHash;
        }
    }
    private static final class Page {
        final WeakReference<Activity> activity;
        final String token = UUID.randomUUID().toString();
        List<Object> original;
        List<Row> rows = List.of();
        boolean empty, noNetwork, running, waiting, dirty;
        int generation, request, problems;
        Runnable poll;
        BroadcastReceiver receiver;
        Page(Activity activity) { this.activity = new WeakReference<>(activity); }
    }

    NativeDeviceCenter(XposedModule module, BooleanSupplier prepend, BiConsumer<String, Throwable> failure) {
        this.module = module; this.prepend = prepend; this.failure = failure;
    }

    void install(ClassLoader loader) throws Exception {
        Class<?> home = Class.forName(HOME, false, loader);
        dataType = Class.forName(DOMAIN + "cards.QuickCardDeviceData", false, loader);
        deviceType = Class.forName(DOMAIN + "device.DeviceType", false, loader);
        connectType = Class.forName(DOMAIN + "device.ConnectState", false, loader);
        displayType = Class.forName(DOMAIN + "cards.DeviceCardDisplayState", false, loader);
        Class<?> status = Class.forName(DOMAIN + "plugin.DeviceStatus", false, loader);
        Class<?> battery = Class.forName(DOMAIN + "device.BatteryInfo", false, loader);
        Class<?> abnormal = Class.forName(DOMAIN + "cards.DeviceAbnormalState", false, loader);
        Class<?> ring = Class.forName(DOMAIN + "cards.StatusRing", false, loader);
        dataConstructor = dataType.getConstructor(String.class, String.class, String.class, String.class, String.class,
                status, status, status, String.class, List.class, String.class, boolean.class, boolean.class, boolean.class,
                battery, battery, battery, battery, Boolean.class, boolean.class, String.class, abnormal, String.class,
                boolean.class, String.class, String.class, ring);
        statusConstructor = status.getConstructor(String.class, String.class, Integer.class, Integer.class);
        dataType.getMethod("setDeviceType", deviceType);
        dataType.getMethod("setDisplayState", displayType);
        update = home.getDeclaredMethod("s1", List.class, boolean.class, boolean.class);
        Method reordered = home.getDeclaredMethod("j1", dataType, List.class);
        update.setAccessible(true);
        other = HostReflect.enumValue(deviceType, "OTHER");
        none = HostReflect.enumValue(abnormal, "NONE");
        offlineRing = HostReflect.enumValue(ring, "OFFLINE");
        displayCompanion = displayType.getField("Companion").get(null);
        displayFactory = displayCompanion.getClass().getMethod("fromDeviceData", deviceType, boolean.class, connectType, boolean.class);
        Class<?> card = Class.forName("aa.un", false, loader);
        Method bind = card.getDeclaredMethod("h", dataType);
        Class<?> outer = Class.forName("com.oplus.mydevices.deviceui.devicecard.view.homecard.HomeCardView", false, loader);
        Method outerBind = outer.getDeclaredMethod("u", dataType);
        Method touch = outer.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
        Class<?> icon = Class.forName("aa.Ln", false, loader);
        Method iconBind = icon.getDeclaredMethod("d", String.class, String.class, deviceType, Integer.class);
        Method clearIcon = icon.getDeclaredMethod("b");
        Field iconView = icon.getDeclaredField("a");
        if (iconView.getType() != ImageView.class) throw new NoSuchFieldException("Native icon ImageView");
        iconView.setAccessible(true);

        module.hook(update).intercept(chain -> {
            Page page = page((Activity) chain.getThisObject());
            List<?> incoming = (List<?>) chain.getArg(0);
            List<Object> original = new ArrayList<>();
            for (Object item : incoming) if (!id(item).startsWith(DeviceSpacePolicy.PREFIX)) original.add(item);
            page.original = original;
            page.empty = (Boolean) chain.getArg(1) && original.isEmpty();
            page.noNetwork = (Boolean) chain.getArg(2);
            List<Object> merged = original;
            try {
                List<Object> additions = new ArrayList<>();
                for (Row row : page.rows) additions.add(card(row));
                merged = DeviceCenterPolicy.merge(original, additions, NativeDeviceCenter::id, prepend.getAsBoolean());
            } catch (Exception error) { failure.accept("Native device center merge failed", error); }
            return chain.proceed(new Object[]{merged, page.rows.isEmpty() ? page.empty : false, page.noNetwork});
        });
        module.hook(reordered).intercept(chain -> {
            Object moved = chain.getArg(0);
            if (moved != null && id(moved).startsWith(DeviceSpacePolicy.PREFIX)) return null;
            List<String> nativeIds = new ArrayList<>();
            for (Object value : (List<?>) chain.getArg(1)) nativeIds.add((String) value);
            return chain.proceed(new Object[]{moved, DeviceCenterPolicy.nativeOrder(nativeIds)});
        });
        module.hook(Activity.class.getDeclaredMethod("onPostResume")).intercept(chain -> {
            Object result = chain.proceed();
            if (HOME.equals(chain.getThisObject().getClass().getName())) start(page((Activity) chain.getThisObject()));
            return result;
        });
        for (String method : List.of("onPause", "onDestroy")) module.hook(Activity.class.getDeclaredMethod(method)).intercept(chain -> {
            if (HOME.equals(chain.getThisObject().getClass().getName())) stop(page((Activity) chain.getThisObject()));
            return chain.proceed();
        });
        for (Method binder : List.of(bind, outerBind)) module.hook(binder).intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            String id = id(chain.getArg(0));
            if (id.startsWith(DeviceSpacePolicy.PREFIX)) {
                String controlId = id.substring(DeviceSpacePolicy.PREFIX.length());
                cardIds.put(view, controlId);
                view.setOnClickListener(v -> details(controlId));
                view.setOnLongClickListener(v -> { details(controlId); return true; });
                view.setContentDescription(HostReflect.get(chain.getArg(0), "getName") + "，米家，点击打开设备详情");
            } else { cardIds.remove(view); gestures.remove(view); }
            return result;
        });
        module.hook(touch).intercept(chain -> {
            View view = (View) chain.getThisObject();
            if (!cardIds.containsKey(view)) return chain.proceed();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            // Keep RecyclerView scrolling, while fake IDs never reach native delete/unbind/drag actions.
            GestureDetector detector = gestures.computeIfAbsent(view, this::gestures);
            view.setPressed(event.getActionMasked() == MotionEvent.ACTION_DOWN);
            detector.onTouchEvent(event);
            return true;
        });
        module.hook(iconBind).intercept(chain -> {
            String uri = (String) chain.getArg(0);
            if (!uri.startsWith(ICON)) return chain.proceed();
            // Cancel any old native Glide request before reusing this image. Own images stay in memory.
            clearIcon.invoke(chain.getThisObject());
            Object[] args = chain.getArgs().toArray(); args[0] = "";
            Object result = chain.proceed(args);
            Row row = images.get(uri.substring(ICON.length(), uri.lastIndexOf(':')));
            if (row != null && row.bitmap != null) {
                ImageView image = (ImageView) iconView.get(chain.getThisObject());
                image.setImageTintList(null); image.clearColorFilter(); image.setImageBitmap(row.bitmap);
            }
            return result;
        });
    }

    private GestureDetector gestures(View view) {
        WeakReference<View> target = new WeakReference<>(view);
        return new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { View v = target.get(); return v != null && v.performClick(); }
            @Override public void onLongPress(MotionEvent event) {
                View v = target.get();
                if (v == null) return;
                // Match the native card: the same tick the system's own long press would give.
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                v.setPressed(false);
                v.performLongClick();
            }
        });
    }

    private Page page(Activity activity) { return pages.computeIfAbsent(activity, Page::new); }

    private void start(Page page) {
        if (page.running) return;
        page.running = true; page.waiting = false; page.dirty = false;
        page.problems = 0; page.generation++;
        page.rows = List.of(); refresh(page);
        Activity activity = page.activity.get();
        if (activity != null) listen(activity, page);
        page.poll = () -> {
            if (!valid(page)) return;
            if (!page.waiting) snapshot(page);
            main.postDelayed(page.poll, RefreshPolicy.heartbeatDelay(page.problems));
        };
        snapshot(page);
        main.postDelayed(page.poll, RefreshPolicy.HEARTBEAT_MS);
    }

    /** SystemUI announces every MiHome change; the heartbeat only heals a lost broadcast. */
    private void listen(Activity activity, Page page) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // Only SystemUI publishes MiHome state; the payload carries no data anyway.
                int sender = getSentFromUid();
                boolean trusted = BridgeContract.PUSH.equals(intent.getAction()) && fromSystemUi(context, sender);
                if (!trusted || !valid(page)) {
                    module.log(Log.DEBUG, "MiHomeBridge", "center push ignored: from=" + sender
                            + ", trusted=" + trusted + ", valid=" + valid(page));
                    return;
                }
                if (RefreshPolicy.fetchOnPush(page.running, page.waiting)) {
                    main.removeCallbacks(page.poll);
                    snapshot(page);
                    main.postDelayed(page.poll, RefreshPolicy.heartbeatDelay(page.problems));
                } else page.dirty = true;
            }
        };
        try {
            activity.registerReceiver(receiver, new IntentFilter(BridgeContract.PUSH), null, main, Context.RECEIVER_EXPORTED);
            page.receiver = receiver;
        } catch (RuntimeException error) { failure.accept("Cannot listen for device center updates", error); }
    }

    private static boolean fromSystemUi(Context context, int sender) {
        try {
            if (sender < 0) return false;
            return sender == android.os.Process.SYSTEM_UID || sender
                    == context.getPackageManager().getPackageUid(BridgeContract.SYSTEMUI, PackageManager.PackageInfoFlags.of(0));
        } catch (Exception error) { return false; }
    }

    private void stop(Page page) {
        if (!page.running) return;
        page.running = false; page.generation++; page.dirty = false;
        if (page.poll != null) main.removeCallbacks(page.poll);
        page.poll = null;
        Activity activity = page.activity.get();
        if (activity != null && page.receiver != null) {
            try { activity.unregisterReceiver(page.receiver); }
            catch (IllegalArgumentException ignored) { /* Already unregistered by the host. */ }
        }
        page.receiver = null;
        if (activity != null) send(activity, page, BridgeContract.CENTER_CLOSE, null, null);
        page.rows = List.of();
        index();
    }

    private boolean valid(Page page) {
        Activity activity = page.activity.get();
        return page.running && activity != null && !activity.isDestroyed() && !activity.isFinishing();
    }

    private void snapshot(Page page) {
        Activity activity = page.activity.get();
        if (activity == null) return;
        page.waiting = true;
        int epoch = page.generation, request = ++page.request;
        ResultReceiver reply = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!valid(page) || epoch != page.generation || request != page.request) return;
                page.waiting = false;
                page.problems = code == 0 ? 0 : Math.min(RefreshPolicy.MAX_BACKOFF, page.problems + 1);
                try {
                    List<Row> next = new ArrayList<>();
                    if (code == 0 && data != null && data.getBoolean(BridgeContract.ENABLED)
                            && data.getInt("user", -1) == android.os.Process.myUid() / 100000) {
                        ArrayList<Bundle> rows = data.getParcelableArrayList("rows", Bundle.class);
                        if (rows != null) for (Bundle row : rows) {
                            if (next.size() == DeviceCenterPolicy.MAX_ROWS) break;
                            String id = row.getString("id", "");
                            if (id.isBlank() || id.length() > 1024) continue;
                            byte[] png = row.getByteArray("icon");
                            Bitmap bitmap = null;
                            int hash = 0;
                            if (png != null && png.length <= DeviceCenterPolicy.MAX_ICON_BYTES) {
                                hash = Arrays.hashCode(png);
                                Row cached = images.get(id);
                                if (cached != null && cached.imageHash == hash) bitmap = cached.bitmap;
                                else {
                                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                                    BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
                                    if (bounds.outWidth > 0 && bounds.outWidth <= DeviceCenterPolicy.MAX_ICON_PX
                                            && bounds.outHeight > 0 && bounds.outHeight <= DeviceCenterPolicy.MAX_ICON_PX)
                                        bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                                }
                            }
                            next.add(new Row(id, row.getString("title", "米家设备"), row.getString("room", ""),
                                    row.getString("status", "正在连接…"), row.getBoolean("online"), hash, bitmap));
                        }
                    }
                    replace(page, next);
                } catch (RuntimeException error) { replace(page, List.of()); failure.accept("Device center snapshot rejected", error); }
                if (page.dirty) {
                    // Pushes that landed mid-request collapse into a single follow-up fetch.
                    page.dirty = false;
                    main.postDelayed(() -> { if (valid(page) && !page.waiting) snapshot(page); }, RefreshPolicy.COALESCE_MS);
                }
            }
        };
        send(activity, page, BridgeContract.CENTER_SNAPSHOT, null, reply);
        main.postDelayed(() -> {
            if (valid(page) && epoch == page.generation && request == page.request && page.waiting) {
                page.waiting = false; page.request++;
                // Keep whatever the user already sees; a slow round trip must not blank the list.
                page.problems = Math.min(RefreshPolicy.MAX_BACKOFF, page.problems + 1);
            }
        }, BridgeContract.COMMAND_TIMEOUT_MS);
    }

    private void replace(Page page, List<Row> next) {
        boolean same = next.size() == page.rows.size();
        if (same) for (int i = 0; i < next.size(); i++) if (!next.get(i).same(page.rows.get(i))) { same = false; break; }
        if (same) return;
        page.rows = List.copyOf(next);
        index();
        refresh(page);
        module.log(Log.INFO, "MiHomeBridge", "Device center refreshed: native=" + (page.original == null ? 0 : page.original.size())
                + ", mihome=" + next.size() + ", icons=" + next.stream().filter(row -> row.bitmap != null).count());
    }

    /** Rebuilds the icon index from every live page, so one page cannot drop another's bitmaps. */
    private void index() {
        Map<String, Row> next = new HashMap<>();
        for (Page page : new ArrayList<>(pages.values())) for (Row row : page.rows) next.put(row.id, row);
        images = next;
    }

    private void refresh(Page page) {
        Activity activity = page.activity.get();
        if (activity == null || activity.isDestroyed() || page.original == null) return;
        try { update.invoke(activity, page.original, page.empty, page.noNetwork); }
        catch (Exception error) { failure.accept("Device center refresh failed", error); }
    }

    private Object card(Row row) throws Exception {
        String room = row.room.isBlank() ? "米家" : "米家 · " + row.room;
        Object status = statusConstructor.newInstance(row.status, room, null, null);
        // The native adapter compares DTO equality. Include icon content in its URI so a
        // late image causes a rebind even when the provider's status text has not changed.
        Object data = dataConstructor.newInstance(DeviceSpacePolicy.PREFIX + row.id, row.title, ICON + row.id + ":" + Integer.toHexString(row.imageHash), "",
                row.online ? "ON_LINE" : "OFF_LINE", status, status, status, BridgeContract.MIHOME, List.of(), "other",
                false, true, row.online, null, null, null, null, false, false, "", none, "", false, "", null, offlineRing);
        HostReflect.call(data, "setDeviceType", new Class<?>[]{deviceType}, other);
        Object display = displayFactory.invoke(displayCompanion, other, false,
                HostReflect.enumValue(connectType, row.online ? "ON_LINE" : "OFF_LINE"), false);
        HostReflect.call(data, "setDisplayState", new Class<?>[]{displayType}, display);
        return data;
    }

    private void details(String id) {
        for (Page page : new ArrayList<>(pages.values())) if (valid(page) && page.rows.stream().anyMatch(row -> row.id.equals(id))) {
            Activity activity = page.activity.get();
            if (activity == null) return;
            int epoch = page.generation;
            send(activity, page, BridgeContract.CENTER_DETAIL, id, new ResultReceiver(main) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (!valid(page) || epoch != page.generation) return;
                    Activity current = page.activity.get();
                    if (current == null) return;
                    KeyguardManager keyguard = current.getSystemService(KeyguardManager.class);
                    if (keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) return;
                    PendingIntent detail = data == null ? null : data.getParcelable("detail", PendingIntent.class);
                    if (code != 0 || detail == null || !BridgeContract.MIHOME.equals(detail.getCreatorPackage())
                            || detail.getCreatorUid() / 100000 != android.os.Process.myUid() / 100000) {
                        Toast.makeText(current, data == null ? "米家暂未响应" : data.getString("message", "正在连接米家，请稍后重试"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        ActivityOptions options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE);
                        detail.send(current, 0, null, null, null, null, options.toBundle());
                    } catch (Exception error) { failure.accept("Device center detail launch failed", error); }
                }
            });
            return;
        }
    }

    private static void send(Context context, Page page, String operation, String id, ResultReceiver reply) {
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        options.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
        Intent request = new Intent(BridgeContract.HOST_ACTION).setPackage(BridgeContract.SYSTEMUI)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra("operation", operation).putExtra("token", page.token)
                .putExtra(BridgeContract.SENT_AT, SystemClock.elapsedRealtime());
        if (id != null) request.putExtra("id", id);
        if (reply != null) request.putExtra("reply", BridgeTransport.remoteReply(reply));
        context.sendBroadcast(request, null, options.toBundle());
    }

    private static String id(Object data) {
        try { return (String) HostReflect.get(data, "getId"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
