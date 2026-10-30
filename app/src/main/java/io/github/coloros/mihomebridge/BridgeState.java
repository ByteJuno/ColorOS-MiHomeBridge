package io.github.coloros.mihomebridge;

/** Keeps a delayed preference callback from overriding a newer signed command. */
final class BridgeState {
    private long revision = -1;
    private boolean enabled;

    synchronized boolean update(long nextRevision, boolean nextEnabled) {
        if (nextRevision < 0 || nextRevision < revision) return false;
        if (nextRevision == revision) return nextEnabled == enabled;
        revision = nextRevision;
        enabled = nextEnabled;
        return true;
    }

    synchronized boolean enabled() { return enabled; }
    synchronized long revision() { return revision; }

    static boolean timely(long sentAt, long now, long timeout) {
        return sentAt >= 0 && now >= sentAt && now - sentAt < timeout;
    }
}
