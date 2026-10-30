package io.github.coloros.mihomebridge;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DeviceSpacePolicyTest {
    @Test public void keepsNativeDevicesAfterSavedMiHomeOrder() {
        assertEquals(List.of("coloros_mihome:b", "coloros_mihome:a", "headset", "watch"),
                DeviceSpacePolicy.merge(List.of("headset", "watch"),
                        List.of("coloros_mihome:b", "coloros_mihome:a"), x -> x));
    }
    @Test public void repeatedRefreshDoesNotDuplicateCardsOrKeepRemovedFavorites() {
        assertEquals(List.of("coloros_mihome:b", "headset"),
                DeviceSpacePolicy.merge(List.of("coloros_mihome:a", "coloros_mihome:b", "headset"),
                        List.of("coloros_mihome:b", "coloros_mihome:b"), x -> x));
    }
    @Test public void clearingBridgePreservesNativeDevices() {
        assertEquals(List.of("headset"), DeviceSpacePolicy.merge(
                List.of("coloros_mihome:a", "headset"), List.of(), x -> x));
    }
    @Test public void onlyReadyUnlockedCurrentUserCanAct() {
        assertTrue(DeviceSpacePolicy.canAct(true, true, false, true, true, false));
        assertFalse(DeviceSpacePolicy.canAct(false, true, false, true, true, false));
        assertFalse(DeviceSpacePolicy.canAct(true, false, false, true, true, false));
        assertFalse(DeviceSpacePolicy.canAct(true, true, true, true, true, false));
        assertFalse(DeviceSpacePolicy.canAct(true, true, false, false, true, false));
        assertFalse(DeviceSpacePolicy.canAct(true, true, false, true, false, false));
        assertFalse(DeviceSpacePolicy.canAct(true, true, false, true, true, true));
    }
    @Test public void staleStatusCannotConfirmToggleOrScene() {
        assertFalse(DeviceSpacePolicy.confirmsAction(true, false, true));
        assertFalse(DeviceSpacePolicy.confirmsAction(true, true, false));
        assertFalse(DeviceSpacePolicy.confirmsAction(null, true, true));
        assertTrue(DeviceSpacePolicy.confirmsAction(true, true, true));
        assertTrue(DeviceSpacePolicy.confirmsAction(false, false, true));
    }
    @Test public void reservesNativeEntranceAndBoundsLayoutCapacity() {
        assertEquals(1, DeviceSpacePolicy.capacity(2, 1));
        assertEquals(3, DeviceSpacePolicy.capacity(4, 1));
        assertEquals(7, DeviceSpacePolicy.capacity(4, 2));
        assertEquals(15, DeviceSpacePolicy.capacity(4, 99));
        assertEquals(3, DeviceSpacePolicy.capacity(4, 0));
    }
}
