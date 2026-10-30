package io.github.coloros.mihomebridge;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DeviceCenterPolicyTest {
    @Test public void acceptsOnlyRealSameUserSenderIdentity() {
        assertTrue(DeviceCenterPolicy.authorized(110042, BridgeContract.MYDEVICES, 10042, 1, 1000, 1100));
        assertFalse(DeviceCenterPolicy.authorized(110043, BridgeContract.MYDEVICES, 10042, 1, 1000, 1100));
        assertFalse(DeviceCenterPolicy.authorized(110042, "another.app", 10042, 1, 1000, 1100));
        assertFalse(DeviceCenterPolicy.authorized(110042, null, 10042, 1, 1000, 1100));
        assertFalse(DeviceCenterPolicy.authorized(10042, BridgeContract.MYDEVICES, 10042, 1, 1000, 1100));
        assertFalse(DeviceCenterPolicy.authorized(110042, BridgeContract.MYDEVICES, -1, 1, 1000, 1100));
    }
    @Test public void deferredAndFutureRequestsCannotRenewSubscription() {
        assertFalse(DeviceCenterPolicy.authorized(10042, BridgeContract.MYDEVICES, 10042, 0, 1000, 4000));
        assertFalse(DeviceCenterPolicy.authorized(10042, BridgeContract.MYDEVICES, 10042, 0, 2000, 1000));
    }
    @Test public void originalOrderAndFavoritesSurviveRepeatedMerges() {
        List<String> originals = List.of("pad", "headset", "coloros_mihome:removed");
        List<String> merged = DeviceCenterPolicy.merge(originals,
                List.of("coloros_mihome:a", "coloros_mihome:a"), x -> x, false);
        assertEquals(List.of("pad", "headset", "coloros_mihome:a"), merged);
        assertEquals(merged, DeviceCenterPolicy.merge(merged, List.of("coloros_mihome:a"), x -> x, false));
        assertEquals(List.of("pad", "headset"), DeviceCenterPolicy.merge(merged, List.of(), x -> x, false));
    }
    @Test public void placementSettingDrivesTheSameOrderAsDeviceSpace() {
        List<String> originals = List.of("pad", "headset");
        List<String> additions = List.of("coloros_mihome:a", "coloros_mihome:b");
        assertEquals(List.of("pad", "headset", "coloros_mihome:a", "coloros_mihome:b"),
                DeviceCenterPolicy.merge(originals, additions, x -> x, false));
        assertEquals(DeviceSpacePolicy.merge(originals, additions, x -> x, true),
                DeviceCenterPolicy.merge(originals, additions, x -> x, true));
    }
    @Test public void deadPageExpiresAndRenewalExtendsItsLease() {
        var leases = new DeviceCenterPolicy.Leases();
        long lease = DeviceCenterPolicy.LEASE_MS;
        assertTrue("lease must outlive one heartbeat", lease > RefreshPolicy.HEARTBEAT_MS);
        leases.update("page", true, 0);
        assertTrue(leases.active(lease - 1));
        leases.update("page", true, 5000);
        assertTrue(leases.active(5000 + lease - 1));
        assertFalse(leases.active(5000 + lease));
    }
    @Test public void nativeReorderNeverPersistsInjectedIds() {
        assertEquals(List.of("headset", "pad"), DeviceCenterPolicy.nativeOrder(
                List.of("coloros_mihome:a", "headset", "coloros_mihome:b", "pad")));
    }
    @Test public void oldActivityCloseCannotReleaseReplacementActivity() {
        var leases = new DeviceCenterPolicy.Leases();
        leases.update("old", true, 0);
        leases.update("new", true, 100);
        leases.update("old", false, 200);
        assertTrue(leases.active(300));
        leases.clear();
        assertFalse(leases.active(300));
    }
    @Test public void iconSizeFollowsDensityAndStaysDecodable() {
        assertEquals(64, DeviceCenterPolicy.iconSizePx(1f));
        assertEquals(144, DeviceCenterPolicy.iconSizePx(3f));
        assertEquals(DeviceCenterPolicy.MAX_ICON_PX, DeviceCenterPolicy.iconSizePx(8f));
        for (float density : new float[]{1f, 1.5f, 2f, 2.75f, 3f, 3.5f, 4f}) {
            int[] steps = DeviceCenterPolicy.iconSteps(density);
            assertEquals(4, steps.length);
            assertTrue(steps[0] <= DeviceCenterPolicy.MAX_ICON_PX);
            assertTrue(steps[0] >= 64);
            for (int i = 1; i < steps.length; i++) {
                assertTrue("steps must shrink", steps[i] < steps[i - 1]);
                assertTrue(steps[i] >= 24);
            }
        }
    }
}
