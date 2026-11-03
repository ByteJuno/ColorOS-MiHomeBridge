package io.github.coloros.mihomebridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Pure rules shared by the native center transport and its list adapter. */
final class DeviceCenterPolicy {
    /**
     * How long a page keeps SystemUI's MiHome subscription alive without checking in.
     * Must exceed {@link RefreshPolicy#HEARTBEAT_MS}, otherwise a push can be dropped
     * while the page is open, and stay short enough that a crashed page frees the
     * subscription quickly.
     */
    static final long LEASE_MS = 30_000;
    static final int MAX_ROWS = 32;
    /** One card icon at native card size; encoded for the screen so it is never upscaled. */
    static final int MAX_ICON_BYTES = 16 * 1024, MAX_TOTAL_ICON_BYTES = 192 * 1024;
    /** Accepted decode size; keeps a malformed payload from allocating a huge bitmap. */
    static final int MAX_ICON_PX = 256;
    static final int MAX_ICON_EDGE = MAX_ICON_PX;

    static boolean authorized(int uid, String pkg, int installedUid, int currentUser, long sent, long now) {
        return installedUid >= 0 && currentUser >= 0 && uid >= 0
                && BridgeContract.MYDEVICES.equals(pkg)
                && uid / 100000 == currentUser && uid % 100000 == installedUid % 100000
                && BridgeState.timely(sent, now, BridgeContract.COMMAND_TIMEOUT_MS);
    }

    static <T> List<T> merge(List<T> original, List<T> additions, Function<T, String> id, boolean prepend) {
        return DeviceSpacePolicy.merge(original, additions, id, prepend);
    }
    static <T> List<T> merge(List<T> original, List<T> additions, Function<T, String> id) {
        return merge(original, additions, id, false);
    }

    static List<String> nativeOrder(List<String> dragged) {
        return dragged.stream().filter(id -> !id.startsWith(DeviceSpacePolicy.PREFIX)).toList();
    }

    /** Card icons render at roughly 48 dp; scale for the panel density, then clamp. */
    static int iconSizePx(float density) {
        return Math.max(64, Math.min(MAX_ICON_PX, Math.round(48f * Math.max(1f, density))));
    }

    /** Descending encode attempts: the largest that fits the byte budget wins. */
    static int[] iconSteps(float density) {
        int target = iconSizePx(density);
        int[] steps = new int[4];
        for (int i = 0; i < steps.length; i++) steps[i] = Math.max(24, Math.round(target * (float) Math.pow(0.75, i)));
        return steps;
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
        boolean contains(String token, long now) { active(now); return deadlines.containsKey(token); }
        void clear() { deadlines.clear(); }
    }
    private DeviceCenterPolicy() {}
}
