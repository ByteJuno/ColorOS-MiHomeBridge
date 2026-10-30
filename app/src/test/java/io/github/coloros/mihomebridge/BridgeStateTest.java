package io.github.coloros.mihomebridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeStateTest {
    @Test public void newerDisableSurvivesStalePreferenceCallback() {
        BridgeState state = new BridgeState();
        assertTrue(state.update(0, true));
        assertTrue(state.update(1, false));
        assertFalse(state.update(0, true));
        assertFalse(state.enabled());
    }
    @Test public void oldRestoreCannotOverrideNewerEnable() {
        BridgeState state = new BridgeState();
        state.update(3, true);
        assertFalse(state.update(2, false));
        assertTrue(state.enabled());
    }
    @Test public void equalRevisionCannotChangeMeaning() {
        BridgeState state = new BridgeState();
        state.update(4, false);
        assertTrue(state.update(4, false));
        assertFalse(state.update(4, true));
        assertFalse(state.enabled());
    }
    @Test public void freshPreferenceCanAdvanceCommandSnapshot() {
        BridgeState state = new BridgeState();
        state.update(1, false);
        assertTrue(state.update(2, true));
        assertTrue(state.enabled());
        assertEquals(2, state.revision());
    }
    @Test public void expiredCommandIsRejectedAtTheReplyDeadline() {
        assertTrue(BridgeState.timely(100, 2599, 2500));
        assertFalse(BridgeState.timely(100, 2600, 2500));
        assertFalse(BridgeState.timely(100, 9999, 2500));
    }
    @Test public void absentOrFutureTimestampAndNegativeRevisionAreRejected() {
        assertFalse(BridgeState.timely(-1, 100, 2500));
        assertFalse(BridgeState.timely(101, 100, 2500));
        BridgeState state = new BridgeState();
        assertFalse(state.update(-1, true));
        assertFalse(state.enabled());
    }
}
