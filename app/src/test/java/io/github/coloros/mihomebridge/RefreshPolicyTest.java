package io.github.coloros.mihomebridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshPolicyTest {
    @Test public void healthyPageUsesTheSlowHeartbeatOnly() {
        assertEquals(RefreshPolicy.HEARTBEAT_MS, RefreshPolicy.heartbeatDelay(0));
        assertTrue(RefreshPolicy.HEARTBEAT_MS >= 10_000);
    }
    @Test public void repeatedFailuresBackOffButStayBounded() {
        long previous = RefreshPolicy.heartbeatDelay(0);
        for (int problems = 1; problems <= RefreshPolicy.MAX_BACKOFF; problems++) {
            long next = RefreshPolicy.heartbeatDelay(problems);
            assertTrue("delay must grow", next > previous);
            previous = next;
        }
        assertEquals(RefreshPolicy.heartbeatDelay(RefreshPolicy.MAX_BACKOFF),
                RefreshPolicy.heartbeatDelay(RefreshPolicy.MAX_BACKOFF + 5));
    }
    @Test public void pushNeverOverlapsAnInFlightRequest() {
        assertTrue(RefreshPolicy.fetchOnPush(true, false));
        assertFalse(RefreshPolicy.fetchOnPush(true, true));
        assertFalse(RefreshPolicy.fetchOnPush(false, false));
    }
    @Test public void coalescingWindowStaysBelowOneHeartbeat() {
        assertTrue(RefreshPolicy.COALESCE_MS < RefreshPolicy.HEARTBEAT_MS);
    }
}
