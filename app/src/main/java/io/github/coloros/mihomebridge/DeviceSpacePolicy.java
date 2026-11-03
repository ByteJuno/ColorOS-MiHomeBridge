package io.github.coloros.mihomebridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure policy shared by native-panel composition and its regression tests. */
final class DeviceSpacePolicy {
    static final String PREFIX = "coloros_mihome:";
    static <T> List<T> merge(List<T> original, List<T> mihome, Function<T, String> id) {
        return merge(original, mihome, id, true);
    }

    /**
     * One ordering rule for both the Device Space panel and the native device list, so a
     * user never sees the same set of cards in two different orders.
     *
     * @param prepend true keeps the MiHome cards above the devices OPPO already shows
     */
    static <T> List<T> merge(List<T> original, List<T> mihome, Function<T, String> id, boolean prepend) {
        LinkedHashMap<String, T> ours = new LinkedHashMap<>();
        for (T item : mihome) ours.putIfAbsent(id.apply(item), item);
        LinkedHashMap<String, T> native_ = new LinkedHashMap<>();
        for (T item : original) {
            String key = id.apply(item);
            // Drop ids this module injected earlier so repeated refreshes cannot duplicate a card.
            if (!key.startsWith(PREFIX)) native_.putIfAbsent(key, item);
        }
        List<T> result = new ArrayList<>(native_.size() + ours.size());
        if (prepend) { result.addAll(ours.values()); result.addAll(native_.values()); }
        else { result.addAll(native_.values()); result.addAll(ours.values()); }
        return result;
    }

    static boolean canAct(boolean enabled, boolean visible, boolean locked,
                          boolean currentUser, boolean ready, boolean pending) {
        return enabled && visible && !locked && currentUser && ready && !pending;
    }

    /** Panel device state that means "cannot be acted on right now". */
    static final int STATE_UNAVAILABLE = 3;

    /**
     * The control centre panel only has 1-3 card slots and trims the rest. Stable-partition
     * the merged list so the devices a user can actually act on keep their slots, instead of
     * offline ones. Relative order inside each group is untouched.
     */
    static <T> List<T> reachableFirst(List<T> merged, Function<T, Integer> state) {
        List<T> reachable = new ArrayList<>(merged.size());
        List<T> unavailable = new ArrayList<>();
        for (T item : merged) {
            Integer value = state.apply(item);
            if (value != null && value == STATE_UNAVAILABLE) unavailable.add(item);
            else reachable.add(item);
        }
        if (unavailable.isEmpty()) return merged;
        reachable.addAll(unavailable);
        return reachable;
    }
    /**
     * DeviceStatus.eventMessageType. The host shows its own warning glyph
     * (drawable/icon_warning) beside the main status text for any value that is set and not 1,
     * so only real problems are flagged: an action in flight is normal, a rejected one is not.
     *
     * @param known true when MiHome returned a Control for this device at all
     */
    static Integer eventType(boolean pending, String message, boolean known, boolean ready) {
        if (pending) return null;
        if (message != null) return "已发送".equals(message) ? null : EVENT_WARNING;
        return known && !ready ? EVENT_WARNING : null;
    }

    /** Native event badge shown next to the status text. */
    static final int EVENT_WARNING = 2;

    /**
     * MiHome only publishes a status string through the Controls API, so a percentage in that
     * string is the only battery data the bridge can hand to the native battery ring.
     */
    static int batteryPercent(String text) {
        if (text == null) return -1;
        Matcher matcher = BATTERY.matcher(text);
        if (!matcher.find()) return -1;
        try {
            int value = Integer.parseInt(matcher.group(1));
            return value >= 0 && value <= 100 ? value : -1;
        } catch (NumberFormatException error) { return -1; }
    }

    // Brightness and volume also use percentages; only battery wording counts.
    private static final Pattern BATTERY = Pattern.compile("(?:电量|电池)[^0-9]{0,4}(\\d{1,3})\\s*[%％]");

    static boolean confirmsAction(Boolean expected, boolean actual, boolean available) {
        return expected != null && available && expected == actual;
    }
    static int capacity(int columns, int rows) {
        return columns == 2 ? 1 : Math.max(1, Math.min(15, 4 * Math.max(1, Math.min(4, rows)) - 1));
    }
    private DeviceSpacePolicy() {}
}
