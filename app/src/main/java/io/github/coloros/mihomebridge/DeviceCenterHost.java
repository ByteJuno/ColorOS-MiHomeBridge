package io.github.coloros.mihomebridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.service.controls.Control;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Authenticated subscriptions: state-change pushes, image acknowledgements and low-frequency leases. */
final class DeviceCenterHost extends BroadcastReceiver {
    private final MiHomeRuntime runtime;
    private final BiConsumer<String, Throwable> failure;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Client> clients = new LinkedHashMap<>();
    private final Map<String, Bundle> reports = new HashMap<>();
    private final String instance = UUID.randomUUID().toString();
    private final Runnable flush = this::flush;
    private final Runnable cleanup = this::cleanup;
    private final DeviceIconCache icons;
    private Context context;
    private long serial, pushes, heartbeatCount, lastClientSeen;
    private int lastRendered;
    private static final class Client {
        final String token;
        final int uid, user;
        final ResultReceiver reply;
        final IBinder binder;
        IBinder.DeathRecipient death;
        long expires;
        String fingerprint = "";
        final Map<String, String> known = new HashMap<>();
        final Set<String> actions = new LinkedHashSet<>();
        Client(String token, int uid, int user, ResultReceiver reply) {
            this.token = token; this.uid = uid; this.user = user; this.reply = reply; binder = BridgeTransport.replyBinder(reply);
        }
    }
    DeviceCenterHost(MiHomeRuntime runtime, BiConsumer<String, Throwable> failure) {
        this.runtime = runtime; this.failure = failure;
        icons = new DeviceIconCache(main, this::changed, failure);
        if (runtime != null) runtime.addListener(this::changed);
    }
    void register(Context context) {
        this.context = context;
        context.registerReceiver(this, new IntentFilter(BridgeContract.HOST_ACTION), null, main, Context.RECEIVER_EXPORTED);
    }
    private void changed() { main.removeCallbacks(flush); main.postDelayed(flush, 80); }
    @Override public void onReceive(Context context, Intent intent) {
        if (!BridgeContract.HOST_ACTION.equals(intent.getAction()) || runtime == null) return;
        try {
            int uid = getSentFromUid(), user = runtime.user();
            int installed = context.getPackageManager().getPackageUid(BridgeContract.MYDEVICES, PackageManager.PackageInfoFlags.of(0));
            if (!DeviceCenterPolicy.authorized(uid, getSentFromPackage(), installed, user,
                    intent.getLongExtra(BridgeContract.SENT_AT, -1), SystemClock.elapsedRealtime())) return;
            String token = intent.getStringExtra("token"), operation = intent.getStringExtra("operation");
            if (token == null || token.isBlank() || token.length() > 64) return;
            if (BridgeContract.CENTER_REPORT.equals(operation)) { report(intent); return; }
            if (BridgeContract.CENTER_CLOSE.equals(operation)) { remove(token); return; }
            ResultReceiver reply = intent.getParcelableExtra("reply", ResultReceiver.class);
            if (BridgeContract.CENTER_SUBSCRIBE.equals(operation)) {
                if (reply == null) return;
                remove(token);
                if (clients.size() >= 8) remove(clients.keySet().iterator().next());
                Client client = new Client(token, uid, user, reply);
                client.death = () -> main.post(() -> { if (clients.get(token) == client) remove(token); });
                client.binder.linkToDeath(client.death, 0);
                clients.put(token, client); acknowledge(client, intent); renew(client); report(intent); emit(client, true); return;
            }
            if (BridgeContract.CENTER_SNAPSHOT.equals(operation)) {
                if (reply == null) return;
                runtime.centerVisible(token, runtime.enabled());
                Client legacy = new Client(token, uid, user, reply); emit(legacy, true); return;
            }
            Client client = clients.get(token);
            if (client == null || client.uid != uid || client.user != user || client.expires <= SystemClock.elapsedRealtime()) {
                respond(reply, 1, "设备中心连接已过期，请重新连接"); return;
            }
            if (BridgeContract.CENTER_PING.equals(operation)) {
                acknowledge(client, intent); renew(client); report(intent); heartbeatCount++;
                if (reply != null) { Bundle ack = base(); ack.putBoolean("heartbeat", true); reply.send(0, ack); }
                emit(client, false); return;
            }
            if (BridgeContract.CENTER_ACK.equals(operation)) { acknowledge(client, intent); report(intent); emit(client, false); return; }
            if (BridgeContract.CENTER_RETRY.equals(operation)) { runtime.session.retry(); respond(reply, 0, "正在重新连接米家"); return; }
            if (!runtime.enabled() || !runtime.leased(token)) { respond(reply, 1, "米家桥接已关闭"); return; }
            if (BridgeContract.CENTER_DETAIL.equals(operation)) {
                String id = intent.getStringExtra("id");
                PendingIntent target = id != null && id.length() <= 1024 ? runtime.session.detailsIntent(id) : null;
                Bundle result = base();
                if (target == null) result.putString("message", "正在连接米家，请稍后重试"); else result.putParcelable("detail", target);
                if (reply != null) reply.send(target == null ? 1 : 0, result);
            } else if (BridgeContract.CENTER_ACTION.equals(operation)) {
                if (reply == null) return;
                String actionId = intent.getStringExtra("action_id"), id = intent.getStringExtra("id");
                if (actionId == null || actionId.length() > 64 || actionId.isBlank() || id == null || id.length() > 1024)
                    throw new IllegalArgumentException("无效的设备操作");
                if (!client.actions.add(actionId)) { respond(reply, 1, "该操作已提交，请等待设备确认"); return; }
                if (client.actions.size() > 32) client.actions.remove(client.actions.iterator().next());
                try {
                    runtime.session.perform(id, intent.getStringExtra("kind"), intent.getBooleanExtra("value", false), intent.getFloatExtra("number", Float.NaN));
                    respond(reply, 0, "正在等待设备确认");
                } catch (IllegalStateException | IllegalArgumentException error) { respond(reply, 1, error.getMessage()); }
            }
        } catch (Exception error) { failure.accept("Device center request failed", error); }
    }
    private Bundle base() {
        Bundle data = new Bundle(); data.putBoolean(BridgeContract.ENABLED, runtime.enabled()); data.putInt("user", runtime.user());
        data.putString("instance", instance); data.putString("connection", runtime.session.phase()); data.putLong("last_update", runtime.session.lastUpdate());
        data.putBoolean("authoritative", !runtime.enabled() || runtime.session.authoritative());
        data.putBoolean("group_rooms", runtime.layout().groupRooms()); data.putString("build", BuildConfig.VERSION_NAME);
        return data;
    }
    private void acknowledge(Client client, Intent intent) {
        Bundle known = intent.getBundleExtra("known_icons");
        if (known == null) return;
        client.known.clear();
        for (String id : known.keySet()) {
            if (client.known.size() == DeviceCenterPolicy.MAX_ROWS) break;
            String hash = known.getString(id, "");
            if (id.length() <= 1024 && hash.matches("[a-f0-9]{64}")) client.known.put(id, hash);
        }
    }
    private void renew(Client client) {
        client.expires = SystemClock.elapsedRealtime() + DeviceCenterPolicy.LEASE_MS;
        lastClientSeen = SystemClock.elapsedRealtime(); runtime.centerVisible(client.token, runtime.enabled());
        main.removeCallbacks(cleanup); main.postDelayed(cleanup, DeviceCenterPolicy.LEASE_MS);
    }
    private void report(Intent intent) {
        Bundle report = new Bundle();
        report.putBoolean("compatible", intent.getBooleanExtra("compatible", true));
        report.putString("build", bounded(intent.getStringExtra("client_build"), 48));
        report.putString("error", bounded(intent.getStringExtra("client_error"), 240));
        report.putLong("seen", SystemClock.elapsedRealtime());
        String process = bounded(intent.getStringExtra("process"), 128);
        if (process.isBlank()) process = BridgeContract.MYDEVICES + ":cards";
        if (reports.size() < 8 || reports.containsKey(process)) reports.put(process, report);
        lastRendered = intent.getIntExtra("rendered", lastRendered);
    }
    private void cleanup() {
        long now = SystemClock.elapsedRealtime();
        for (Client client : new ArrayList<>(clients.values())) if (client.expires <= now) remove(client.token);
        if (!clients.isEmpty()) main.postDelayed(cleanup, 1000);
    }
    private void remove(String token) {
        Client old = clients.remove(token);
        if (old != null) try { old.binder.unlinkToDeath(old.death, 0); } catch (RuntimeException ignored) { }
        if (runtime != null) runtime.centerVisible(token, false);
    }
    private void flush() {
        if (runtime == null || context == null) return;
        int user = runtime.user();
        icons.forUser(user);
        if (!runtime.enabled()) icons.clear();
        for (Client client : new ArrayList<>(clients.values())) {
            if (client.user != user) { Bundle data = base(); data.putBoolean("reset", true); client.reply.send(0, data); remove(client.token); }
            else if (client.expires <= SystemClock.elapsedRealtime()) remove(client.token);
            else { runtime.centerVisible(client.token, runtime.enabled()); emit(client, false); }
        }
    }
    private void emit(Client client, boolean force) {
        if (context == null) return;
        icons.forUser(runtime.user());
        Bundle data = base(); ArrayList<Bundle> rows = new ArrayList<>();
        int budget = DeviceCenterPolicy.MAX_TOTAL_ICON_BYTES, payload = 0, missing = 0;
        Set<String> retained = new HashSet<>();
        StringBuilder fingerprint = new StringBuilder(data.toString());
        if (runtime.enabled()) for (MiHomeSession.Entry entry : runtime.entries()) {
            if (rows.size() == DeviceCenterPolicy.MAX_ROWS) break;
            retained.add(entry.id());
            Bundle row = new Bundle(); row.putString("id", bounded(entry.id(), 1024)); row.putString("title", bounded(entry.title(), 160));
            row.putString("room", bounded(entry.subtitle(), 160)); row.putString("status", bounded(NativeDeviceSpace.statusText(entry), 180));
            Control control = entry.control();
            row.putBoolean("stale", entry.stale()); row.putBoolean("pending", entry.pending());
            row.putBoolean("online", control != null && control.getStatus() == Control.STATUS_OK);
            if (control != null) try { row.putBundle("capabilities", new ControlCapabilities(control).bundle(entry.type())); }
            catch (RuntimeException error) { failure.accept("Unsupported control template", error); }
            Icon source = control == null ? null : control.getCustomIcon();
            DeviceIconCache.Image image = icons.image(context, entry.id(), source);
            String hash = image == null ? "" : image.hash;
            row.putString("icon_hash", hash); fingerprint.append(row);
            if (image != null && image.png != null && !hash.equals(client.known.get(entry.id()))) {
                if (image.png.length <= budget) { row.putByteArray("icon", image.png); budget -= image.png.length; payload += image.png.length; }
                else missing++;
            }
            rows.add(row);
        }
        icons.retain(retained);
        String signature = fingerprint.toString();
        if (!force && signature.equals(client.fingerprint) && payload == 0) return;
        client.fingerprint = signature; data.putParcelableArrayList("rows", rows); data.putLong("serial", ++serial); data.putInt("remaining_icons", missing);
        try { client.reply.send(0, data); pushes++; icons.sent(payload); }
        catch (RuntimeException error) { failure.accept("Device center callback failed", error); remove(client.token); }
    }
    void status(Bundle result) {
        Bundle report = reports.get(BridgeContract.MYDEVICES + ":cards");
        if (report == null && !reports.isEmpty()) report = reports.values().iterator().next();
        result.putBoolean("center_reported", report != null);
        if (report != null) {
            result.putBoolean("center_compatible", report.getBoolean("compatible")); result.putString("center_build", report.getString("build"));
            result.putString("center_error", report.getString("error")); result.putLong("center_report_time", report.getLong("seen"));
        }
        result.putInt("center_clients", clients.size()); result.putInt("center_rendered", lastRendered); result.putLong("center_last_seen", lastClientSeen);
        result.putLong("center_pushes", pushes); result.putLong("center_heartbeats", heartbeatCount);
        result.putLong("icon_encodes", icons.encoded()); result.putLong("icon_bytes_sent", icons.bytesSent());
    }
    private static void respond(ResultReceiver reply, int code, String message) {
        if (reply == null) return; Bundle data = new Bundle(); data.putString("message", message); reply.send(code, data);
    }
    private static String bounded(String text, int max) { return text == null ? "" : text.length() <= max ? text : text.substring(0, max); }
}
