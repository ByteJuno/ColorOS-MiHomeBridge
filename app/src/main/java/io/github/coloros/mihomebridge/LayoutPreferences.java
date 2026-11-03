package io.github.coloros.mihomebridge;

import android.content.SharedPreferences;
import java.util.Set;

final class LayoutPreferences {
    static String key(int user, String suffix) { return "layout.user." + user + "." + suffix; }
    static DeviceLayoutPolicy.Options read(SharedPreferences prefs, int user) {
        if (prefs == null || user < 0) return DeviceLayoutPolicy.Options.defaults();
        return new DeviceLayoutPolicy.Options(prefs.getStringSet(key(user, "hidden"), Set.of()),
                DeviceLayoutPolicy.decodeOrder(prefs.getString(key(user, "order"), "")),
                prefs.getBoolean(key(user, "rooms"), false));
    }
    private LayoutPreferences() {}
}
