package io.github.coloros.mihomebridge;

/** A connection retry budget; appliance commands are never retried. */
final class ReconnectPolicy {
    static final long INITIAL_TIMEOUT_MS = 10000, HEARTBEAT_MS = 10000;
    private static final long[] DELAYS = {1000, 2000, 4000, 8000, 16000};
    private int attempts;
    long nextDelay() { return attempts < DELAYS.length ? DELAYS[attempts++] : -1; }
    int attempts() { return attempts; }
    void reset() { attempts = 0; }
    static boolean current(int callbackEpoch, int epoch, boolean visible, boolean enabled, boolean sameUser) {
        return callbackEpoch == epoch && visible && enabled && sameUser;
    }
}
