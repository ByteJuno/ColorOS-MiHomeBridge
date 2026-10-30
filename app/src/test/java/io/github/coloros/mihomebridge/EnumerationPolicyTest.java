package io.github.coloros.mihomebridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnumerationPolicyTest {
    @Test public void nonMiuiFullEnumerationIncludesScenes() {
        assertTrue(EnumerationPolicy.useAllDevices(true, Integer.MAX_VALUE, 0));
    }
    @Test public void preservesBoundedSuggestions() {
        assertFalse(EnumerationPolicy.useAllDevices(true, 12, 4));
        assertFalse(EnumerationPolicy.useAllDevices(true, 12, 0));
    }
    @Test public void doesNotChangeCallsOutsideProvider() {
        assertFalse(EnumerationPolicy.useAllDevices(false, Integer.MAX_VALUE, 0));
    }
}
