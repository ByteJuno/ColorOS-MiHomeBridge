package io.github.coloros.mihomebridge;

/**
 * Timing rules for the Device Center snapshot channel.
 *
 * <p>SystemUI pushes an event whenever MiHome publishes a new state, so the page no longer
 * polls. A heartbeat only exists to heal a dropped broadcast, and repeated failures back off
 * instead of hammering a frozen provider.
 */
final class RefreshPolicy {
    /** Upper bound between two snapshots while the page is visible. */
    static final long HEARTBEAT_MS = 15_000;
    /** Extra wait per consecutive failed snapshot. */
    static final long RETRY_MS = 3_000;
    /** Most pushes seen while one request is in flight are folded into one follow-up request. */
    static final long COALESCE_MS = 250;
    static final int MAX_BACKOFF = 4;

    static long heartbeatDelay(int consecutiveProblems) {
        int level = Math.max(0, Math.min(MAX_BACKOFF, consecutiveProblems));
        return HEARTBEAT_MS + level * RETRY_MS;
    }

    /** A push only starts a request when the previous one already finished. */
    static boolean fetchOnPush(boolean visible, boolean inFlight) {
        return visible && !inFlight;
    }

    private RefreshPolicy() {}
}
