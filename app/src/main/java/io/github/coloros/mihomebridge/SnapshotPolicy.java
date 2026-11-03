package io.github.coloros.mihomebridge;

final class SnapshotPolicy {
    enum Event { PAUSED, TIMEOUT, CONNECTING, DISABLED, USER_CHANGED, AUTHORITATIVE_EMPTY }
    static boolean clear(Event event) {
        return event == Event.DISABLED || event == Event.USER_CHANGED || event == Event.AUTHORITATIVE_EMPTY;
    }
    private SnapshotPolicy() {}
}
