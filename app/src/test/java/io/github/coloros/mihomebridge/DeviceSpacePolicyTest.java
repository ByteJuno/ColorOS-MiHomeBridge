package io.github.coloros.mihomebridge;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DeviceSpacePolicyTest {
    @Test public void prependModeKeepsSavedMiHomeOrderAboveNativeDevices() {
        assertEquals(List.of("coloros_mihome:b", "coloros_mihome:a", "headset", "watch"),
                DeviceSpacePolicy.merge(List.of("headset", "watch"),
                        List.of("coloros_mihome:b", "coloros_mihome:a"), x -> x, true));
    }
    @Test public void appendModeLeavesNativeDevicesUndisturbed() {
        assertEquals(List.of("headset", "watch", "coloros_mihome:b"),
                DeviceSpacePolicy.merge(List.of("headset", "watch"),
                        List.of("coloros_mihome:b"), x -> x, false));
    }
    @Test public void repeatedRefreshDoesNotDuplicateCardsOrKeepRemovedFavorites() {
        List<String> merged = DeviceSpacePolicy.merge(List.of("coloros_mihome:a", "coloros_mihome:b", "headset"),
                List.of("coloros_mihome:b", "coloros_mihome:b"), x -> x, true);
        assertEquals(List.of("coloros_mihome:b", "headset"), merged);
        assertEquals(merged, DeviceSpacePolicy.merge(merged, List.of("coloros_mihome:b"), x -> x, true));
    }
    @Test public void clearingBridgePreservesNativeDevices() {
        assertEquals(List.of("headset"), DeviceSpacePolicy.merge(
                List.of("coloros_mihome:a", "headset"), List.of(), x -> x, true));
        assertEquals(List.of("headset"), DeviceSpacePolicy.merge(
                List.of("headset", "coloros_mihome:a"), List.of(), x -> x, false));
    }
    @Test public void injectedIdsNeverLeakIntoTheNativeSection() {
        List<String> merged = DeviceSpacePolicy.merge(List.of("headset", "coloros_mihome:stale"),
                List.of("coloros_mihome:fresh"), x -> x, false);
        assertEquals(List.of("headset", "coloros_mihome:fresh"), merged);
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
    @Test public void unavailableDevicesFallBehindSoLimitedSlotsStayUseful() {
        List<String> merged = List.of("coloros_mihome:gateway", "coloros_mihome:socket", "pad", "headset");
        List<String> ordered = DeviceSpacePolicy.reachableFirst(merged, id -> switch (id) {
            case "coloros_mihome:socket", "headset" -> DeviceSpacePolicy.STATE_UNAVAILABLE;
            default -> 1;
        });
        assertEquals(List.of("coloros_mihome:gateway", "pad", "coloros_mihome:socket", "headset"), ordered);
    }
    @Test public void onlineDevicesKeepTheirExistingRelativeOrder() {
        List<String> merged = List.of("a", "b", "c", "d");
        assertEquals(merged, DeviceSpacePolicy.reachableFirst(merged, id -> 1));
        assertEquals(List.of("a", "c", "b", "d"),
                DeviceSpacePolicy.reachableFirst(merged, id -> "b".equals(id) || "d".equals(id)
                        ? DeviceSpacePolicy.STATE_UNAVAILABLE : 2));
    }
    @Test public void unknownStateIsTreatedAsReachable() {
        List<String> merged = List.of("a", "b");
        assertEquals(merged, DeviceSpacePolicy.reachableFirst(merged, id -> null));
    }
    @Test public void realProblemsGetTheNativeWarningGlyph() {
        Integer warning = DeviceSpacePolicy.EVENT_WARNING;
        assertEquals(warning, DeviceSpacePolicy.eventType(false, "操作超时，请重试", true, true));
        assertEquals(warning, DeviceSpacePolicy.eventType(false, null, true, false));
        assertEquals(warning, DeviceSpacePolicy.eventType(false, "设备已移除", true, false));
    }
    @Test public void normalStatesCarryNoWarningGlyph() {
        assertNull(DeviceSpacePolicy.eventType(false, null, true, true));
        assertNull(DeviceSpacePolicy.eventType(true, null, true, true));
        assertNull(DeviceSpacePolicy.eventType(false, "已发送", true, true));
        // No Control yet means MiHome is still answering, not that the device is broken.
        assertNull(DeviceSpacePolicy.eventType(false, null, false, false));
    }
    @Test public void batteryLevelIsReadFromTheStatusText() {
        assertEquals(80, DeviceSpacePolicy.batteryPercent("电量 80%"));
        assertEquals(55, DeviceSpacePolicy.batteryPercent("电池电量55％"));
        assertEquals(100, DeviceSpacePolicy.batteryPercent("电量100%"));
        assertEquals(9, DeviceSpacePolicy.batteryPercent("电量:9%"));
    }
    @Test public void brightnessPercentageIsNotABatteryLevel() {
        assertEquals(-1, DeviceSpacePolicy.batteryPercent("亮度 30%"));
        assertEquals(-1, DeviceSpacePolicy.batteryPercent("音量 100%"));
        assertEquals(-1, DeviceSpacePolicy.batteryPercent("连接设备数 3"));
        assertEquals(-1, DeviceSpacePolicy.batteryPercent(""));
        assertEquals(-1, DeviceSpacePolicy.batteryPercent(null));
        assertEquals(-1, DeviceSpacePolicy.batteryPercent("电量 180%"));
    }
}
