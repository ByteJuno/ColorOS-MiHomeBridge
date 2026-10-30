package io.github.coloros.mihomebridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Pure rules shared by the native center transport and its list adapter. */
final class DeviceCenterPolicy {
    static final long LEASE_MS = 6000;
    static final int MAX_ROWS = 32, MAX_ICON_BYTES = 16 * 1024, MAX_TOTAL_ICON_BYTES = 192 * 1024;

    static boolean authorized(int uid, String pkg, int installedUid, int currentUser, long sent, long now) {
        return installedUid >= 0 && currentUser >= 0 && uid >= 0
                && BridgeContract.MYDEVICES.equals(pkg)
                && uid / 100000 == currentUser && uid % 100000 == installedUid % 100000
                && BridgeState.timely(sent, now, BridgeContract.COMMAND_TIMEOUT_MS);
    }

    static <T> List<T> merge(List<T> original, List<T> additions, Function<T, String> id) {
        Map<String, T> rows = new LinkedHashMap<>();
        for (T item : original) if (!id.apply(item).startsWith(DeviceSpacePolicy.PREFIX)) rows.putIfAbsent(id.apply(item), item);
        for (T item : additions) if (id.apply(item).startsWith(DeviceSpacePolicy.PREFIX)) rows.putIfAbsent(id.apply(item), item);
        return new ArrayList<>(rows.values());
    }

    static List<String> nativeOrder(List<String> dragged) {
        return dragged.stream().filter(id -> !id.startsWith(DeviceSpacePolicy.PREFIX)).toList();
    }

    static final class Leases {
        private final Map<String, Long> deadlines = new LinkedHashMap<>();
        void update(String token, boolean visible, long now) {
            active(now);
            if (visible) {
                if (deadlines.size() >= 8 && !deadlines.containsKey(token)) deadlines.remove(deadlines.keySet().iterator().next());
                deadlines.put(token, now + LEASE_MS);
            } else deadlines.remove(token);
        }
        boolean active(long now) {
            deadlines.values().removeIf(end -> end <= now);
            return !deadlines.isEmpty();
        }
        void clear() { deadlines.clear(); }
    }
    private DeviceCenterPolicy() {}
}
