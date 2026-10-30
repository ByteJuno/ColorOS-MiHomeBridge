package io.github.coloros.mihomebridge;

final class BridgeContract {
    static final String MODULE = "io.github.coloros.mihomebridge";
    static final String MIHOME = "com.xiaomi.smarthome";
    static final String SYSTEMUI = "com.android.systemui";
    static final String MYDEVICES = "com.heytap.mydevices";
    static final String HOST_ACTION = MODULE + ".DEVICE_CENTER";
    static final String CENTER_SNAPSHOT = "center_snapshot", CENTER_DETAIL = "center_detail", CENTER_CLOSE = "center_close";
    static final String CENTER_OPEN = "open_center";
    static final String SERVICE = "com.xiaomi.smarthome.controls.MiControlsProviderService";
    static final String ACTION = MODULE + ".COMMAND";
    static final String PERMISSION = MODULE + ".permission.COMMAND";
    static final String PREFS = "bridge_settings";
    static final String ENABLED = "enabled";
    static final String REVISION = "revision";
    static final String SENT_AT = "sent_at";
    static final long COMMAND_TIMEOUT_MS = 2500;
    static final String BACKUP = "coloros_mihome_bridge_recovery_v1";
    static final String QS_SETTING = "sysui_qs_tiles";
    static final String STATUS = "status", ADD = "add", OPEN = "open", RESTORE = "restore";
    static final String SYNC = "sync";
    private BridgeContract() {}
}
