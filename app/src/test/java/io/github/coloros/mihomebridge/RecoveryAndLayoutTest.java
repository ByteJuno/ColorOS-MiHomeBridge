package io.github.coloros.mihomebridge;

import org.junit.Test;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class RecoveryAndLayoutTest {
    @Test public void retriesAreBoundedAndSuccessRestoresTheBudget() {
        ReconnectPolicy policy = new ReconnectPolicy();
        for (long expected : new long[]{1000, 2000, 4000, 8000, 16000, -1, -1}) assertEquals(expected, policy.nextDelay());
        policy.reset(); assertEquals(1000, policy.nextDelay());
    }
    @Test public void oldUserAndHiddenSessionCannotResumeFromLateCallbacks() {
        assertTrue(ReconnectPolicy.current(4, 4, true, true, true));
        assertFalse(ReconnectPolicy.current(3, 4, true, true, true));
        assertFalse(ReconnectPolicy.current(4, 4, false, true, true));
        assertFalse(ReconnectPolicy.current(4, 4, true, false, true));
        assertFalse(ReconnectPolicy.current(4, 4, true, true, false));
    }
    @Test public void transientFailuresKeepCardsButDisableAndUserSwitchPurgeThem() {
        for (var event : List.of(SnapshotPolicy.Event.PAUSED, SnapshotPolicy.Event.TIMEOUT, SnapshotPolicy.Event.CONNECTING)) assertFalse(SnapshotPolicy.clear(event));
        for (var event : List.of(SnapshotPolicy.Event.DISABLED, SnapshotPolicy.Event.USER_CHANGED, SnapshotPolicy.Event.AUTHORITATIVE_EMPTY)) assertTrue(SnapshotPolicy.clear(event));
    }
    record Device(String id, String room) {}
    @Test public void hidingOrderingAndRoomGroupingPreserveNewDevicesAndDoNotMutateInput() {
        var a = new Device("a", "卧室"); var b = new Device("b", "客厅"); var c = new Device("c", "卧室"); var d = new Device("d", "");
        List<Device> input = List.of(a, b, c, d);
        var options = new DeviceLayoutPolicy.Options(Set.of("d"), List.of("b", "a", "removed"), true);
        assertEquals(List.of(b, a, c), DeviceLayoutPolicy.arrange(input, options, Device::id, Device::room));
        assertEquals(4, input.size());
        assertEquals(List.of(a, c, b, d), DeviceLayoutPolicy.arrange(input,
                new DeviceLayoutPolicy.Options(Set.of(), List.of(), true), Device::id, Device::room));
    }
    @Test public void orderEncodingHandlesArbitraryProviderIdsAndRejectsBrokenSettings() {
        var ids = List.of("a\nb", "米家:插座/1", "", "x");
        assertEquals(ids, DeviceLayoutPolicy.decodeOrder(DeviceLayoutPolicy.encodeOrder(ids)));
        assertTrue(DeviceLayoutPolicy.decodeOrder("%%%broken").isEmpty());
    }
    @Test public void rangeValuesRespectTheProvidersStepAndRejectUnsafeNumbers() {
        assertEquals(22.5f, ControlValuePolicy.normalize(22.4f, 16, 30, .5f), 0);
        assertEquals(-2.5f, ControlValuePolicy.normalize(-2.4f, -10, 10, .5f), 0);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 101}) {
            try { ControlValuePolicy.normalize(invalid, 0, 100, 1); fail(); } catch (IllegalArgumentException expected) { }
        }
        assertTrue(ControlValuePolicy.confirms(22.5f, 22.5f, .5f));
        assertFalse(ControlValuePolicy.confirms(22.5f, 22, .5f));
    }
}
