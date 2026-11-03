package io.github.coloros.mihomebridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderPolicyTest {
    @Test public void explicitlyEnabledServiceWins() {
        assertTrue(ProviderPolicy.available(true, 1, false));
    }
    @Test public void defaultStateFollowsTheManifest() {
        assertTrue(ProviderPolicy.available(true, 0, true));
        assertFalse(ProviderPolicy.available(true, 0, false));
    }
    @Test public void disabledApplicationOrComponentNeverCountsAsAvailable() {
        assertFalse(ProviderPolicy.available(false, 1, true));
        assertFalse(ProviderPolicy.available(true, 2, true));
        assertFalse(ProviderPolicy.available(true, 3, true));
        assertFalse(ProviderPolicy.available(true, 4, true));
    }
}
