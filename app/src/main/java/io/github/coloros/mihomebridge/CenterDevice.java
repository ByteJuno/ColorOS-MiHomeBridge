package io.github.coloros.mihomebridge;

import android.graphics.Bitmap;
import android.os.Bundle;

record CenterDevice(String id, String title, String room, String status, boolean online, boolean stale,
                    boolean pending, Bundle capabilities, String wantedIcon, String iconHash, Bitmap image, int eventType) {
    CenterDevice(String id, String title, String room, String status, boolean online, boolean stale,
                 boolean pending, Bundle capabilities, String wantedIcon, String iconHash, Bitmap image) {
        this(id, title, room, status, online, stale, pending, capabilities, wantedIcon, iconHash, image, 0);
    }
    boolean toggle() { return capabilities.getBoolean("toggle"); }
    boolean checked() { return capabilities.getBoolean("checked"); }
    boolean range() { return capabilities.getBoolean("range"); }
    boolean command() { return capabilities.getBoolean("command"); }
    boolean controllable() { return toggle() || range() || command(); }
    boolean ready() { return online && !stale && !pending; }
    CenterDevice staleCopy() { return new CenterDevice(id, title, room, status, online, true, false, capabilities, wantedIcon, iconHash, image, eventType); }
    CenterDevice image(String hash, Bitmap bitmap) { return new CenterDevice(id, title, room, status, online, stale, pending, capabilities, wantedIcon, hash, bitmap, eventType); }
    String displayStatus(boolean busy) {
        if (pending || busy) return "正在执行…";
        if (stale && !status.contains("重连") && !status.contains("中断")) return status + " · 正在重连";
        return status;
    }
    String contentKey() { return id + "|" + title + "|" + room + "|" + status + "|" + online + "|" + stale + "|" + pending + "|" + ControlCapabilities.fingerprint(capabilities) + "|" + iconHash + "|" + eventType; }
}
