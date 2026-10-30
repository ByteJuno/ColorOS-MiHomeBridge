package io.github.coloros.mihomebridge;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeControlsTileTest {
    public record Spec(String value) { public String getSpec() { return value; } }
    public static class Host {
        final List<Spec> tiles = new ArrayList<>();
        int additions, removals;
        public List<Spec> getCurrentTilesSpecs() { return List.copyOf(tiles); }
        public void addTile(Spec tile, int position) { tiles.add(position, tile); additions++; }
        public void removeTiles(Collection<Spec> removed) { tiles.removeAll(removed); removals++; }
    }
    private static NativeControlsTile bridge(Host host) throws Exception {
        return new NativeControlsTile(host, Spec.class, new Spec("controls"));
    }
    @Test public void appendsThroughHostWithoutReordering() throws Exception {
        Host host = new Host(); host.tiles.addAll(List.of(new Spec("wifi"), new Spec("innerFake_device"), new Spec("custom(example/.Tile)")));
        bridge(host).add();
        assertEquals(List.of(new Spec("wifi"), new Spec("innerFake_device"), new Spec("custom(example/.Tile)"), new Spec("controls")), host.tiles);
    }
    @Test public void addingTwiceIsIdempotent() throws Exception {
        Host host = new Host(); NativeControlsTile tile = bridge(host); tile.add(); tile.add(); assertEquals(1, host.additions);
    }
    @Test public void unrelatedControlsNamesArePreserved() throws Exception {
        Host host = new Host(); host.tiles.add(new Spec("custom(example/.Controls)")); assertFalse(bridge(host).isPresent());
    }
    @Test public void removalPreservesOtherTilesAndEdits() throws Exception {
        Host host = new Host(); NativeControlsTile tile = bridge(host); tile.add(); host.tiles.add(new Spec("location")); tile.remove();
        assertEquals(List.of(new Spec("location")), host.tiles);
    }
    @Test public void missingTileDoesNotTriggerRemoval() throws Exception {
        Host host = new Host(); bridge(host).remove(); assertEquals(0, host.removals);
    }
    @Test public void snapshotsAreNotCached() throws Exception {
        Host host = new Host(); NativeControlsTile tile = bridge(host); assertFalse(tile.isPresent());
        host.tiles.add(new Spec("controls")); assertTrue(tile.isPresent());
    }
    @Test public void incompatibleHostIsRejected() {
        assertThrows(NoSuchMethodException.class, () -> new NativeControlsTile(new Object(), Spec.class, new Spec("controls")));
    }
    @Test public void nativeFailureIsNotReportedAsSuccess() throws Exception {
        Host host = new Host() { @Override public void addTile(Spec tile, int position) { throw new IllegalStateException("rejected"); } };
        assertThrows(InvocationTargetException.class, () -> bridge(host).add());
        assertFalse(bridge(host).isPresent());
    }
}
