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
        List<String> merged = DeviceCenterPolicy.merge(originals, List.of("coloros_mihome:a", "coloros_mihome:a"), x -> x);
        assertEquals(List.of("pad", "headset", "coloros_mihome:a"), merged);
        assertEquals(merged, DeviceCenterPolicy.merge(merged, List.of("coloros_mihome:a"), x -> x));
        assertEquals(List.of("pad", "headset"), DeviceCenterPolicy.merge(merged, List.of(), x -> x));
    }
    @Test public void deadPageExpiresAndRenewalExtendsItsLease() {
        var leases = new DeviceCenterPolicy.Leases();
        long ttl = DeviceCenterPolicy.LEASE_MS;
        leases.update("page", true, 0);
        assertTrue(leases.active(ttl - 1));
        leases.update("page", true, ttl - 1000);
        assertTrue(leases.active(ttl * 2 - 1001));
        assertFalse(leases.active(ttl * 2 - 1000));
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
}
