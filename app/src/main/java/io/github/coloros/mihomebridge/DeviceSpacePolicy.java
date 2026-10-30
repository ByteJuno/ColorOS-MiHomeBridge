package io.github.coloros.mihomebridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/** Pure policy shared by native-panel composition and its regression tests. */
final class DeviceSpacePolicy {
    static final String PREFIX = "coloros_mihome:";

    static <T> List<T> merge(List<T> original, List<T> mihome, Function<T, String> id) {
        LinkedHashMap<String, T> result = new LinkedHashMap<>();
        // The saved MiHome order leads; keep all unrelated native devices afterwards.
        for (T item : mihome) result.putIfAbsent(id.apply(item), item);
        for (T item : original) {
            String key = id.apply(item);
            if (!key.startsWith(PREFIX)) result.putIfAbsent(key, item);
        }
        return new ArrayList<>(result.values());
    }

    static boolean canAct(boolean enabled, boolean visible, boolean locked,
                          boolean currentUser, boolean ready, boolean pending) {
        return enabled && visible && !locked && currentUser && ready && !pending;
    }
    static boolean confirmsAction(Boolean expected, boolean actual, boolean available) {
        return expected != null && available && expected == actual;
    }
    static int capacity(int columns, int rows) {
        return columns == 2 ? 1 : Math.max(1, Math.min(15, 4 * Math.max(1, Math.min(4, rows)) - 1));
    }
    private DeviceSpacePolicy() {}
}
