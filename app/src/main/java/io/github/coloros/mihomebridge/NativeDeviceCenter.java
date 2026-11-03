package io.github.coloros.mihomebridge;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native MyDevices list, room headers and power controls with an authenticated push subscription. */
final class NativeDeviceCenter {
    private static final String HOME = "com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity";
    private static final String DOMAIN = "com.oplus.mydevices.domain.entities.";
    private static final String ICON = "coloros-mihome-icon:", ROOM_HEADER = "米家 · ";
    private final XposedModule module;
    private final BooleanSupplier enabled;
    private final BooleanSupplier prepend;
    private final Supplier<String> tapBehavior;
    private final BiConsumer<String, Throwable> failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, Page> pages = new WeakHashMap<>();
    private final Map<View, String> cardIds = new WeakHashMap<>();
    private final Map<View, GestureDetector> gestures = new WeakHashMap<>();
    private final Map<View, WeakReference<View>> buttons = new WeakHashMap<>(), touchTargets = new WeakHashMap<>();
    private final Map<String, Decoded> decoded = new LinkedHashMap<>();
    private final Map<String, DecodeRequest> decoding = new HashMap<>();
    private final ThreadPoolExecutor imageWorker = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "MiHomeBridge-center-images"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private Map<String, CenterDevice> rows = Map.of();
    private WeakReference<Page> active = new WeakReference<>(null);
    private int cacheGeneration;
    private boolean groupRooms;
    private Class<?> dataType, deviceType, displayType, connectType, cardItemType, headerType, sectionType, castType;
    private Constructor<?> dataConstructor, statusConstructor, cardItemConstructor, headerConstructor;
    private Method update, displayFactory, indicatorState, indicatorType;
    private Field nativeButton, nativeLight;
    private Object other, none, offlineRing, displayCompanion, smartSection;
    private record Decoded(String hash, Bitmap image) {}
    private record DecodeRequest(String hash, Object token) {}
    private static final class Page {
        final WeakReference<Activity> activity;
        String token = UUID.randomUUID().toString();
        final ReconnectPolicy retries = new ReconnectPolicy();
        final Set<String> busy = new HashSet<>();
        List<Object> original;
        List<CenterDevice> rows = List.of();
        boolean empty, noNetwork, running, subscribed, groupRooms;
        int generation, rendered;
        long serial;
        String instance = "", phase = "connecting";
        Runnable heartbeat, reconnect, refresh, ack;
        CenterControlDialog controls;
        Page(Activity activity) { this.activity = new WeakReference<>(activity); }
    }
    NativeDeviceCenter(XposedModule module, BooleanSupplier enabled, BooleanSupplier prepend, Supplier<String> tapBehavior, BiConsumer<String, Throwable> failure) {
        this.module = module; this.enabled = enabled; this.failure = failure; imageWorker.allowCoreThreadTimeOut(true);
        this.prepend = prepend; this.tapBehavior = tapBehavior;
    }
    void install(ClassLoader loader) throws Exception {
        Class<?> home = Class.forName(HOME, false, loader);
        dataType = Class.forName(DOMAIN + "cards.QuickCardDeviceData", false, loader);
        deviceType = Class.forName(DOMAIN + "device.DeviceType", false, loader);
        connectType = Class.forName(DOMAIN + "device.ConnectState", false, loader);
        displayType = Class.forName(DOMAIN + "cards.DeviceCardDisplayState", false, loader);
        Class<?> status = Class.forName(DOMAIN + "plugin.DeviceStatus", false, loader), battery = Class.forName(DOMAIN + "device.BatteryInfo", false, loader);
        Class<?> abnormal = Class.forName(DOMAIN + "cards.DeviceAbnormalState", false, loader), ring = Class.forName(DOMAIN + "cards.StatusRing", false, loader);
        dataConstructor = dataType.getConstructor(String.class, String.class, String.class, String.class, String.class,
                status, status, status, String.class, List.class, String.class, boolean.class, boolean.class, boolean.class,
                battery, battery, battery, battery, Boolean.class, boolean.class, String.class, abnormal, String.class,
                boolean.class, String.class, String.class, ring);
        statusConstructor = status.getConstructor(String.class, String.class, Integer.class, Integer.class);
        dataType.getMethod("setDeviceType", deviceType); dataType.getMethod("setDisplayState", displayType);
        update = home.getDeclaredMethod("s1", List.class, boolean.class, boolean.class); update.setAccessible(true);
        Method reordered = home.getDeclaredMethod("j1", dataType, List.class);
        other = HostReflect.enumValue(deviceType, "OTHER"); none = HostReflect.enumValue(abnormal, "NONE"); offlineRing = HostReflect.enumValue(ring, "OFFLINE");
        displayCompanion = displayType.getField("Companion").get(null);
        displayFactory = displayCompanion.getClass().getMethod("fromDeviceData", deviceType, boolean.class, connectType, boolean.class);
        Class<?> card = Class.forName("aa.un", false, loader), outer = Class.forName("com.oplus.mydevices.deviceui.devicecard.view.homecard.HomeCardView", false, loader);
        Method bind = card.getDeclaredMethod("h", dataType), outerBind = outer.getDeclaredMethod("u", dataType);
        Method touch = outer.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
        nativeButton = card.getDeclaredField("k"); nativeButton.setAccessible(true);
        nativeLight = card.getDeclaredField("l"); nativeLight.setAccessible(true);
        Class<?> indicator = Class.forName("com.oplus.mydevices.quickapp.homecard.view.IndicatorButtonView", false, loader);
        castType = Class.forName(DOMAIN + "cards.CastStatus", false, loader);
        indicatorState = indicator.getDeclaredMethod("k", castType, boolean.class);
        indicatorType = indicator.getDeclaredMethod("setDeviceType", deviceType);
        Class<?> icon = Class.forName("aa.Ln", false, loader);
        Method iconBind = icon.getDeclaredMethod("d", String.class, String.class, deviceType, Integer.class), clearIcon = icon.getDeclaredMethod("b");
        Field iconView = icon.getDeclaredField("a"); iconView.setAccessible(true);
        if (iconView.getType() != ImageView.class) throw new NoSuchFieldException("Native icon view");
        Class<?> adapter = Class.forName("com.oplus.mydevices.deviceui.devicecard.adapter.DeviceCardAdapter", false, loader);
        Method submit = adapter.getDeclaredMethod("J", List.class);
        Class<?> itemType = Class.forName("com.oplus.mydevices.deviceui.devicecard.c", false, loader);
        cardItemType = Class.forName("com.oplus.mydevices.deviceui.devicecard.c$a", false, loader);
        headerType = Class.forName("com.oplus.mydevices.deviceui.devicecard.c$c", false, loader);
        sectionType = Class.forName("com.oplus.mydevices.deviceui.devicecard.HomeSection", false, loader);
        smartSection = HostReflect.enumValue(sectionType, "SMARTHOME");
        cardItemConstructor = cardItemType.getConstructor(dataType, sectionType); headerConstructor = headerType.getConstructor(sectionType, String.class);
        Method itemId = itemType.getDeclaredMethod("a"); Field headerTitle = headerType.getDeclaredField("b"); headerTitle.setAccessible(true);
        module.hook(update).intercept(chain -> {
            Page page = page((Activity) chain.getThisObject());
            List<Object> merged;
            try {
                List<Object> originals = new ArrayList<>();
                for (Object item : (List<?>) chain.getArg(0)) if (!id(item).startsWith(DeviceSpacePolicy.PREFIX)) originals.add(item);
                page.original = originals; page.empty = (Boolean) chain.getArg(1) && originals.isEmpty(); page.noNetwork = (Boolean) chain.getArg(2);
                List<Object> additions = new ArrayList<>(); for (CenterDevice row : page.rows) additions.add(card(row, page.busy.contains(row.id())));
                merged = DeviceCenterPolicy.merge(originals, additions, NativeDeviceCenter::id, prepend.getAsBoolean());
            } catch (Exception error) { failure.accept("Device center merge failed", error); return chain.proceed(); }
            Object result = chain.proceed(new Object[]{merged, page.rows.isEmpty() ? page.empty : false, page.noNetwork});
            int rendered = 0; for (Object item : merged) if (id(item).startsWith(DeviceSpacePolicy.PREFIX)) rendered++;
            if (page.rendered != rendered) { page.rendered = rendered; if (valid(page) && page.subscribed) acknowledge(page); }
            return result;
        });
        module.hook(reordered).intercept(chain -> {
            Object moved = chain.getArg(0);
            if (moved != null && id(moved).startsWith(DeviceSpacePolicy.PREFIX)) return null;
            List<String> ids = new ArrayList<>(); for (Object value : (List<?>) chain.getArg(1)) ids.add((String) value);
            return chain.proceed(new Object[]{moved, DeviceCenterPolicy.nativeOrder(ids)});
        });
        module.hook(itemId).intercept(chain -> {
            Object item = chain.getThisObject();
            if (headerType.isInstance(item)) {
                String title = (String) headerTitle.get(item);
                if (title.startsWith(ROOM_HEADER)) return DeviceSpacePolicy.PREFIX + "room:" + title;
            }
            return chain.proceed();
        });
        module.hook(submit).intercept(chain -> {
            if (!groupRooms) return chain.proceed();
            List<Object> grouped;
            try { grouped = grouped((List<?>) chain.getArg(0)); }
            catch (Exception error) { failure.accept("Device center room grouping failed", error); return chain.proceed(); }
            return chain.proceed(new Object[]{grouped});
        });
        module.hook(Activity.class.getDeclaredMethod("onPostResume")).intercept(chain -> {
            Object result = chain.proceed(); if (HOME.equals(chain.getThisObject().getClass().getName())) start(page((Activity) chain.getThisObject())); return result;
        });
        for (String method : List.of("onPause", "onDestroy")) module.hook(Activity.class.getDeclaredMethod(method)).intercept(chain -> {
            if (HOME.equals(chain.getThisObject().getClass().getName())) stop(page((Activity) chain.getThisObject())); return chain.proceed();
        });
        for (Method binder : List.of(bind, outerBind)) module.hook(binder).intercept(chain -> {
            Object result = chain.proceed();
            try {
                View view = (View) chain.getThisObject(); String key = id(chain.getArg(0));
                if (key.startsWith(DeviceSpacePolicy.PREFIX)) {
                    String controlId = key.substring(DeviceSpacePolicy.PREFIX.length()); cardIds.put(view, controlId);
                    view.setOnClickListener(v -> showControls(pageFor(v), controlId));
                    view.setOnLongClickListener(v -> { details(pageFor(v), controlId); return true; });
                    CenterDevice row = rows.get(controlId);
                    view.setContentDescription((row == null ? "米家设备" : row.title()) + "，点击快捷控制，长按设备详情");
                    if (binder == bind && row != null) bindPower(view, row);
                } else { cardIds.remove(view); gestures.remove(view); buttons.remove(view); touchTargets.remove(view); }
            } catch (Exception error) { failure.accept("Device center card controls failed", error); }
            return result;
        });
        module.hook(touch).intercept(chain -> {
            View view = (View) chain.getThisObject(); if (!cardIds.containsKey(view)) return chain.proceed();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                View button = hitPower(view, event); if (button != null) touchTargets.put(view, new WeakReference<>(button));
            }
            WeakReference<View> target = touchTargets.get(view); View button = target == null ? null : target.get();
            if (button != null) {
                int[] a = new int[2], b = new int[2]; view.getLocationOnScreen(a); button.getLocationOnScreen(b);
                MotionEvent local = MotionEvent.obtain(event); local.offsetLocation(a[0] - b[0], a[1] - b[1]);
                try { button.dispatchTouchEvent(local); } finally { local.recycle(); }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) touchTargets.remove(view);
                return true;
            }
            view.setPressed(event.getActionMasked() == MotionEvent.ACTION_DOWN);
            gestures.computeIfAbsent(view, this::gestures).onTouchEvent(event); return true;
        });
        module.hook(iconBind).intercept(chain -> {
            String uri = (String) chain.getArg(0); if (!uri.startsWith(ICON)) return chain.proceed();
            clearIcon.invoke(chain.getThisObject()); Object[] args = chain.getArgs().toArray(); args[0] = ""; Object result = chain.proceed(args);
            try {
                int colon = uri.indexOf(':', ICON.length());
                String key = new String(Base64.getUrlDecoder().decode(uri.substring(ICON.length(), colon)), StandardCharsets.UTF_8);
                CenterDevice row = rows.get(key);
                if (row != null && row.image() != null) { ImageView image = (ImageView) iconView.get(chain.getThisObject()); image.setImageTintList(null); image.clearColorFilter(); image.setImageBitmap(row.image()); }
            } catch (RuntimeException malformed) { failure.accept("Device center image key rejected", malformed); }
            return result;
        });
    }
    private List<Object> grouped(List<?> input) throws Exception {
        List<Object> output = new ArrayList<>(); Map<String, Object> own = new LinkedHashMap<>();
        for (Object item : input) {
            if (cardItemType.isInstance(item)) {
                Object data = HostReflect.get(item, "b"); String key = id(data);
                if (key.startsWith(DeviceSpacePolicy.PREFIX)) { own.put(key.substring(DeviceSpacePolicy.PREFIX.length()), data); continue; }
            }
            output.add(item);
        }
        List<Object> nativeRows = output; output = new ArrayList<>();
        String previous = null;
        for (CenterDevice row : rows.values()) {
            Object data = own.get(row.id()); if (data == null) continue;
            String room = DeviceLayoutPolicy.roomName(row.room());
            if (!room.equals(previous)) { output.add(headerConstructor.newInstance(smartSection, ROOM_HEADER + room)); previous = room; }
            output.add(cardItemConstructor.newInstance(data, smartSection));
        }
        if (prepend.getAsBoolean()) { output.addAll(nativeRows); return output; }
        nativeRows.addAll(output); return nativeRows;
    }
    private void bindPower(View root, CenterDevice row) throws Exception {
        View button = (View) nativeButton.get(root), light = (View) nativeLight.get(root);
        if (!row.toggle()) { buttons.remove(root); return; }
        Page page = pageFor(root); boolean busy = page != null && page.busy.contains(row.id());
        light.setVisibility(View.GONE); button.setVisibility(View.VISIBLE); indicatorType.invoke(button, other);
        String state = !row.ready() && !row.pending() && !busy ? "DISABLED" : row.pending() || busy ? row.checked() ? "CLOSING" : "OPENING" : row.checked() ? "ON" : "OFF";
        indicatorState.invoke(button, HostReflect.enumValue(castType, state), row.ready() && !busy);
        button.setContentDescription((row.checked() ? "关闭" : "开启") + row.title());
        button.setOnClickListener(v -> action(pageFor(root), row.id(), "toggle", !row.checked(), 0));
        button.setEnabled(row.ready() && !busy); button.setClickable(row.ready() && !busy);
        buttons.put(root, new WeakReference<>(button));
    }
    private View hitPower(View outer, MotionEvent event) {
        for (WeakReference<View> reference : new ArrayList<>(buttons.values())) {
            View button = reference.get(); if (button == null || !button.isShown()) continue;
            boolean inside = false;
            for (ViewParent parent = button.getParent(); parent != null; parent = parent.getParent()) if (parent == outer) { inside = true; break; }
            Rect bounds = new Rect(); if (inside && button.getGlobalVisibleRect(bounds) && bounds.contains((int) event.getRawX(), (int) event.getRawY())) return button;
        }
        return null;
    }
    private GestureDetector gestures(View view) {
        WeakReference<View> target = new WeakReference<>(view);
        return new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { View v = target.get(); return v != null && v.performClick(); }
            @Override public void onLongPress(MotionEvent event) { View v = target.get(); if (v != null) { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); v.setPressed(false); v.performLongClick(); } }
        });
    }
    private Page page(Activity activity) {
        return pages.computeIfAbsent(activity, owner -> {
            Page page = new Page(owner); page.rows = rows.values().stream().map(CenterDevice::staleCopy).toList(); page.groupRooms = groupRooms; return page;
        });
    }
    private Page pageFor(View view) {
        Context current = view.getContext();
        for (int i = 0; i < 8; i++) {
            if (current instanceof Activity activity) return pages.get(activity);
            if (!(current instanceof ContextWrapper wrapper) || wrapper.getBaseContext() == current) break;
            current = wrapper.getBaseContext();
        }
        Page currentPage = active.get(); return currentPage != null && valid(currentPage) ? currentPage : null;
    }
    private boolean valid(Page page) {
        Activity activity = page == null ? null : page.activity.get();
        return page != null && page.running && activity != null && !activity.isDestroyed() && !activity.isFinishing();
    }
    private void start(Page page) {
        if (page.running) return;
        page.running = true; page.generation++; page.retries.reset(); active = new WeakReference<>(page);
        if (!enabled.getAsBoolean()) clear(page, SnapshotPolicy.Event.DISABLED); else stale(page);
        index(page); refresh(page); subscribe(page);
    }
    private void stop(Page page) {
        if (!page.running) return;
        Activity activity = page.activity.get(); if (activity != null) send(activity, page, BridgeContract.CENTER_CLOSE, null, null);
        page.running = false; page.subscribed = false; page.generation++; cancel(page); page.heartbeat = null; page.busy.clear();
        if (page.controls != null) { page.controls.dismiss(); page.controls = null; }
        page.rows = page.rows.stream().map(CenterDevice::staleCopy).toList();
    }
    private void cancel(Page page) {
        for (Runnable task : new Runnable[]{page.heartbeat, page.reconnect, page.refresh, page.ack}) if (task != null) main.removeCallbacks(task);
    }
    private void subscribe(Page page) {
        if (!valid(page)) return;
        cancel(page); page.heartbeat = null; page.token = UUID.randomUUID().toString();
        int epoch = ++page.generation; page.subscribed = false; page.phase = "connecting";
        Activity activity = page.activity.get(); if (activity == null) return;
        ResultReceiver callback = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!valid(page) || epoch != page.generation) return;
                if (code != 0 || data == null) { lost(page); return; }
                page.subscribed = true; page.retries.reset(); receive(page, data);
                if (page.heartbeat == null) heartbeat(page, epoch);
            }
        };
        send(activity, page, BridgeContract.CENTER_SUBSCRIBE, null, callback);
        main.postDelayed(() -> { if (valid(page) && epoch == page.generation && !page.subscribed) lost(page); }, BridgeContract.COMMAND_TIMEOUT_MS);
    }
    private void heartbeat(Page page, int epoch) {
        page.heartbeat = () -> {
            if (!valid(page) || epoch != page.generation || !page.subscribed) return;
            Activity activity = page.activity.get(); if (activity == null) return;
            boolean[] completed = {false};
            send(activity, page, BridgeContract.CENTER_PING, null, new ResultReceiver(main) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    if (!valid(page) || epoch != page.generation) return;
                    completed[0] = true;
                    if (code != 0 || data == null) { lost(page); return; }
                    if (!data.getBoolean(BridgeContract.ENABLED)) clear(page, SnapshotPolicy.Event.DISABLED);
                    else if (data.getInt("user", -1) != android.os.Process.myUid() / 100000) clear(page, SnapshotPolicy.Event.USER_CHANGED);
                }
            });
            main.postDelayed(() -> { if (!completed[0] && valid(page) && epoch == page.generation) lost(page); }, BridgeContract.COMMAND_TIMEOUT_MS);
            main.postDelayed(page.heartbeat, ReconnectPolicy.HEARTBEAT_MS);
        };
        main.postDelayed(page.heartbeat, ReconnectPolicy.HEARTBEAT_MS);
    }
    private void lost(Page page) {
        if (!valid(page)) return;
        Activity activity = page.activity.get(); if (activity != null) send(activity, page, BridgeContract.CENTER_CLOSE, null, null);
        cancel(page); page.heartbeat = null; page.subscribed = false; int epoch = ++page.generation; stale(page);
        long delay = page.retries.nextDelay(); page.phase = delay < 0 ? "failed" : "retrying";
        if (delay >= 0) { page.reconnect = () -> { if (valid(page) && epoch == page.generation) subscribe(page); }; main.postDelayed(page.reconnect, delay); }
    }
    private void receive(Page page, Bundle data) {
        try {
            if (data.getInt("user", -1) != android.os.Process.myUid() / 100000 || data.getBoolean("reset")) { clear(page, SnapshotPolicy.Event.USER_CHANGED); return; }
            if (!data.getBoolean(BridgeContract.ENABLED)) { clear(page, SnapshotPolicy.Event.DISABLED); return; }
            String instance = data.getString("instance", ""); long serial = data.getLong("serial");
            if (instance.equals(page.instance) && serial <= page.serial) return;
            page.instance = instance; page.serial = serial; page.phase = data.getString("connection", "connecting");
            boolean oldGrouping = page.groupRooms; page.groupRooms = data.getBoolean("group_rooms"); groupRooms = page.groupRooms;
            ArrayList<Bundle> incoming = data.getParcelableArrayList("rows", Bundle.class);
            if ((incoming == null || incoming.isEmpty()) && !data.getBoolean("authoritative")) { stale(page); return; }
            List<CenterDevice> next = new ArrayList<>(); Set<String> ids = new HashSet<>();
            if (incoming != null) for (Bundle item : incoming) {
                if (next.size() == DeviceCenterPolicy.MAX_ROWS) break;
                String id = item.getString("id", ""); if (id.isBlank() || id.length() > 1024 || !ids.add(id)) continue;
                String wanted = item.getString("icon_hash", ""); Decoded image = decoded.get(id);
                Bundle capabilities = item.getBundle("capabilities"); if (capabilities == null) capabilities = new Bundle();
                CenterDevice row = new CenterDevice(id, item.getString("title", "米家设备"), item.getString("room", ""), item.getString("status", "正在连接…"),
                        item.getBoolean("online"), item.getBoolean("stale"), item.getBoolean("pending"), capabilities,
                        wanted, image == null ? "" : image.hash, image == null ? null : image.image, item.getInt("event_type"));
                CenterDevice previous = find(page, id);
                if (row.pending() || previous != null && !ControlCapabilities.fingerprint(previous.capabilities()).equals(ControlCapabilities.fingerprint(capabilities))) page.busy.remove(id);
                next.add(row);
                byte[] png = item.getByteArray("icon"); if (png != null) decode(page, id, wanted, png);
            }
            decoded.keySet().retainAll(ids); decoding.keySet().retainAll(ids);
            replace(page, next, oldGrouping != page.groupRooms);
        } catch (RuntimeException error) { failure.accept("Device center frame rejected", error); lost(page); }
    }
    private void decode(Page page, String id, String hash, byte[] png) {
        if (!hash.matches("[a-f0-9]{64}") || png.length > DeviceCenterPolicy.MAX_ICON_BYTES) return;
        Decoded existing = decoded.get(id); if (existing != null && existing.hash.equals(hash)) return;
        DecodeRequest pending = decoding.get(id); if (pending != null && pending.hash.equals(hash)) return;
        Object token = new Object(); int epoch = cacheGeneration; decoding.put(id, new DecodeRequest(hash, token));
        try { imageWorker.execute(() -> {
            Bitmap image = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
                if (bounds.outWidth > 0 && bounds.outWidth <= DeviceCenterPolicy.MAX_ICON_EDGE && bounds.outHeight > 0 && bounds.outHeight <= DeviceCenterPolicy.MAX_ICON_EDGE)
                    image = BitmapFactory.decodeByteArray(png, 0, png.length);
            } catch (RuntimeException error) { failure.accept("Device center image decode failed", error); }
            Bitmap finished = image;
            main.post(() -> {
                DecodeRequest current = decoding.get(id);
                if (epoch != cacheGeneration || current == null || current.token != token) { if (finished != null) finished.recycle(); return; }
                decoding.remove(id); if (finished == null) return;
                decoded.put(id, new Decoded(hash, finished));
                for (Page target : new ArrayList<>(pages.values())) {
                    boolean updated = false; List<CenterDevice> next = new ArrayList<>();
                    for (CenterDevice row : target.rows) {
                        if (row.id().equals(id) && row.wantedIcon().equals(hash)) { next.add(row.image(hash, finished)); updated = true; } else next.add(row);
                    }
                    if (updated) { replace(target, next, false); if (valid(target) && target.subscribed) acknowledge(target); }
                }
            });
        }); } catch (RuntimeException rejected) { decoding.remove(id); }
    }
    private void acknowledge(Page page) {
        if (page.ack != null) main.removeCallbacks(page.ack);
        page.ack = () -> { Activity activity = page.activity.get(); if (valid(page) && page.subscribed && activity != null) send(activity, page, BridgeContract.CENTER_ACK, null, null); };
        main.postDelayed(page.ack, 100);
    }
    private void stale(Page page) { replace(page, page.rows.stream().map(CenterDevice::staleCopy).toList(), false); }
    private void clear(Page page, SnapshotPolicy.Event reason) {
        if (!SnapshotPolicy.clear(reason)) return;
        cacheGeneration++; decoded.clear(); decoding.clear(); imageWorker.getQueue().clear();
        for (Page target : new ArrayList<>(pages.values())) { target.busy.clear(); replace(target, List.of(), false); }
        rows = Map.of();
    }
    private void index(Page page) {
        Map<String, CenterDevice> index = new LinkedHashMap<>(); for (CenterDevice row : page.rows) index.put(row.id(), row);
        for (Page other : new ArrayList<>(pages.values())) if (other != page) for (CenterDevice row : other.rows) index.putIfAbsent(row.id(), row);
        rows = index;
    }
    private void replace(Page page, List<CenterDevice> next, boolean force) {
        boolean same = next.size() == page.rows.size();
        if (same) for (int i = 0; i < next.size(); i++) if (!next.get(i).contentKey().equals(page.rows.get(i).contentKey())) { same = false; break; }
        if (same && !force) return;
        page.rows = List.copyOf(next); index(page);
        if (page.controls != null) page.controls.refresh(); scheduleRefresh(page);
    }
    private void scheduleRefresh(Page page) {
        if (page.refresh != null) main.removeCallbacks(page.refresh);
        page.refresh = () -> refresh(page); main.postDelayed(page.refresh, 60);
    }
    private void refresh(Page page) {
        Activity activity = page.activity.get(); if (activity == null || activity.isDestroyed() || page.original == null) return;
        try { update.invoke(activity, page.original, page.empty, page.noNetwork); }
        catch (Exception error) { failure.accept("Device center refresh failed", error); }
    }
    private Object card(CenterDevice row, boolean busy) throws Exception {
        Integer event = DeviceSpacePolicy.displayEvent(row.pending() || busy, row.stale(), row.eventType());
        Object status = statusConstructor.newInstance(row.displayStatus(busy), row.room().isBlank() ? "米家" : "米家 · " + row.room(), event, null);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(row.id().getBytes(StandardCharsets.UTF_8));
        String image = ICON + key + ":" + row.iconHash() + ":" + Integer.toHexString((row.contentKey() + busy).hashCode());
        Object data = dataConstructor.newInstance(DeviceSpacePolicy.PREFIX + row.id(), row.title(), image, "", row.online() && !row.stale() ? "ON_LINE" : "OFF_LINE",
                status, status, status, BridgeContract.MIHOME, List.of(), "other", false, true, row.online() && !row.stale(),
                null, null, null, null, false, false, "", none, "", false, "", null, offlineRing);
        HostReflect.call(data, "setDeviceType", new Class<?>[]{deviceType}, other);
        Object display = displayFactory.invoke(displayCompanion, other, false, HostReflect.enumValue(connectType, row.online() && !row.stale() ? "ON_LINE" : "OFF_LINE"), false);
        HostReflect.call(data, "setDisplayState", new Class<?>[]{displayType}, display); return data;
    }
    private CenterDevice find(Page page, String id) { if (page != null) for (CenterDevice row : page.rows) if (row.id().equals(id)) return row; return null; }
    private void showControls(Page page, String id) {
        if (!valid(page)) return; CenterDevice row = find(page, id); if (row == null) return;
        String behavior = tapBehavior.get();
        if (BridgeContract.TAP_DETAILS.equals(behavior)) { details(page, id); return; }
        if (BridgeContract.TAP_TOGGLE.equals(behavior) && row.toggle() && row.ready()) { action(page, id, "toggle", !row.checked(), 0); return; }
        if (!row.controllable() && !row.stale()) { details(page, id); return; }
        Activity activity = page.activity.get(); if (activity == null) return;
        if (page.controls != null) page.controls.dismiss();
        page.controls = new CenterControlDialog(activity, () -> find(page, id), () -> page.busy.contains(id),
                (kind, checked, number) -> action(page, id, kind, checked, number), () -> details(page, id), () -> retry(page), () -> management(page));
    }
    private void retry(Page page) {
        if (!valid(page)) return; page.retries.reset(); Activity activity = page.activity.get(); if (activity == null) return;
        if (page.subscribed) send(activity, page, BridgeContract.CENTER_RETRY, null, null); else subscribe(page);
    }
    private void management(Page page) {
        Activity activity = page.activity.get(); if (activity != null) activity.startActivity(new Intent().setClassName(BridgeContract.MODULE, BridgeContract.MODULE + ".MainActivity").putExtra("manage_devices", true));
    }
    private void action(Page page, String id, String kind, boolean checked, float number) {
        if (!valid(page) || !page.subscribed) { if (page != null) retry(page); return; }
        CenterDevice row = find(page, id); Activity activity = page.activity.get();
        if (row == null || activity == null || !row.ready() || page.busy.contains(id)) return;
        Bundle extra = new Bundle(); extra.putString("id", id); extra.putString("kind", kind); extra.putBoolean("value", checked); extra.putFloat("number", number); extra.putString("action_id", UUID.randomUUID().toString());
        page.busy.add(id); refresh(page); if (page.controls != null) page.controls.refresh();
        int epoch = page.generation; boolean[] completed = {false};
        send(activity, page, BridgeContract.CENTER_ACTION, extra, new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!valid(page) || epoch != page.generation) return; completed[0] = true;
                if (code != 0) { page.busy.remove(id); toast(page, data == null ? "操作未完成，请重试" : data.getString("message", "操作未完成，请重试")); refresh(page); if (page.controls != null) page.controls.refresh(); }
            }
        });
        main.postDelayed(() -> {
            if (valid(page) && epoch == page.generation && !completed[0]) { page.busy.remove(id); toast(page, "操作响应超时，请先确认设备状态"); refresh(page); if (page.controls != null) page.controls.refresh(); }
        }, BridgeContract.COMMAND_TIMEOUT_MS);
        main.postDelayed(() -> { if (valid(page) && epoch == page.generation && page.busy.remove(id)) { refresh(page); if (page.controls != null) page.controls.refresh(); } }, 8500);
    }
    private void details(Page page, String id) {
        if (!valid(page)) return; Activity activity = page.activity.get(); if (activity == null) return;
        Bundle extra = new Bundle(); extra.putString("id", id); int epoch = page.generation;
        send(activity, page, BridgeContract.CENTER_DETAIL, extra, new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (!valid(page) || epoch != page.generation) return; Activity current = page.activity.get(); if (current == null) return;
                KeyguardManager keyguard = current.getSystemService(KeyguardManager.class); if (keyguard == null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) return;
                PendingIntent intent = data == null ? null : data.getParcelable("detail", PendingIntent.class);
                if (code != 0 || intent == null || !BridgeContract.MIHOME.equals(intent.getCreatorPackage()) || intent.getCreatorUid() / 100000 != android.os.Process.myUid() / 100000) {
                    toast(page, data == null ? "米家暂未响应" : data.getString("message", "正在连接米家，请稍后重试")); return;
                }
                try { intent.send(current, 0, null, null, null, null, ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle()); }
                catch (Exception error) { failure.accept("Device center detail launch failed", error); toast(page, "无法打开详情，请稍后重试"); }
            }
        });
    }
    private void toast(Page page, String message) { Activity activity = page.activity.get(); if (activity != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private static void send(Context context, Page page, String operation, Bundle extra, ResultReceiver reply) {
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true); options.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
        Intent intent = new Intent(BridgeContract.HOST_ACTION).setPackage(BridgeContract.SYSTEMUI).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("operation", operation).putExtra("token", page.token).putExtra(BridgeContract.SENT_AT, SystemClock.elapsedRealtime())
                .putExtra("client_build", BuildConfig.VERSION_NAME).putExtra("compatible", true).putExtra("process", Application.getProcessName()).putExtra("rendered", page.rendered);
        Bundle known = new Bundle(); for (CenterDevice row : page.rows) if (row.image() != null && !row.iconHash().isEmpty()) known.putString(row.id(), row.iconHash());
        intent.putExtra("known_icons", known);
        if (extra != null) intent.putExtras(extra); if (reply != null) intent.putExtra("reply", BridgeTransport.remoteReply(reply));
        context.sendBroadcast(intent, null, options.toBundle());
    }
    static void report(Context context, boolean compatible, String message) {
        BroadcastOptions options = BroadcastOptions.makeBasic(); options.setShareIdentityEnabled(true); options.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
        context.sendBroadcast(new Intent(BridgeContract.HOST_ACTION).setPackage(BridgeContract.SYSTEMUI).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("operation", BridgeContract.CENTER_REPORT).putExtra("token", UUID.randomUUID().toString())
                .putExtra("client_build", BuildConfig.VERSION_NAME).putExtra("compatible", compatible).putExtra("client_error", message)
                .putExtra("process", Application.getProcessName()).putExtra(BridgeContract.SENT_AT, SystemClock.elapsedRealtime()), null, options.toBundle());
    }
    private static String id(Object data) {
        try { return (String) HostReflect.get(data, "getId"); } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
