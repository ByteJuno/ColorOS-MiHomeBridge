package io.github.coloros.mihomebridge;

final class BridgeContract {
    static final String MODULE = "io.github.coloros.mihomebridge";
    /** First API level with the ColorOS 17 controls and device-plugin shapes this module targets. */
    static final int MIN_SDK = 37;
    static final String MIHOME = "com.xiaomi.smarthome";
    static final String SYSTEMUI = "com.android.systemui";
    static final String MYDEVICES = "com.heytap.mydevices";
    static final String HOST_ACTION = MODULE + ".DEVICE_CENTER";
    /** SystemUI -> MyDevices. Carries no data; the receiver re-reads the snapshot. */
    static final String PUSH = MODULE + ".DEVICE_CENTER_PUSH";
    static final String CENTER_SNAPSHOT = "center_snapshot", CENTER_DETAIL = "center_detail", CENTER_CLOSE = "center_close";
    static final String CENTER_OPEN = "open_center";
    static final String CENTER_SUBSCRIBE = "center_subscribe", CENTER_PING = "center_ping", CENTER_ACK = "center_ack";
    static final String CENTER_ACTION = "center_action", CENTER_RETRY = "center_retry", CENTER_REPORT = "center_report";
    static final String DEVICES = "devices";
    static final String RECONNECT = "reconnect";
    static final String SERVICE = "com.xiaomi.smarthome.controls.MiControlsProviderService";
    static final String ACTION = MODULE + ".COMMAND";
    static final String PERMISSION = MODULE + ".permission.COMMAND";
    static final String PREFS = "bridge_settings";
    static final String ENABLED = "enabled";
    static final String REVISION = "revision";
    static final String SENT_AT = "sent_at";
    static final long COMMAND_TIMEOUT_MS = 2500;
    static final String BACKUP = "coloros_mihome_bridge_recovery_v1";
    /** Where the injected cards sit relative to the devices OPPO already shows. */
    static final String PLACEMENT = "placement";
    static final String PREPEND = "prepend", APPEND = "append";
    /** What a tap on a Device Space card does. */
    static final String TAP = "tap";
    static final String TAP_QUICK = "quick", TAP_TOGGLE = "toggle", TAP_DETAILS = "details";
    static final String STATUS = "status", ADD = "add", OPEN = "open", RESTORE = "restore";
    static final String SYNC = "sync";
    private BridgeContract() {}
}
