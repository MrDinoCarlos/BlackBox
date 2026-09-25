package es.mrdino.blackbox.monitor;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class EventCounters {
    private final boolean hotspotEnabled;
    final LongAdder chunkLoads = new LongAdder();
    final LongAdder chunkUnloads = new LongAdder();
    final LongAdder entitySpawns = new LongAdder();
    final LongAdder entityDeaths = new LongAdder();
    final LongAdder redstoneChanges = new LongAdder();
    final LongAdder hopperMoves = new LongAdder();
    final LongAdder pistonActions = new LongAdder();
    final LongAdder explosions = new LongAdder();
    final LongAdder blockBreaks = new LongAdder();
    final LongAdder blockPlaces = new LongAdder();
    private final Map<HotspotKey, Long> hotspots = new HashMap<>();

    public EventCounters(boolean hotspotEnabled) { this.hotspotEnabled = hotspotEnabled; }

    public void hotspot(String event, Location location) {
        if (!hotspotEnabled) return;
        if (location == null || location.getWorld() == null) return;
        HotspotKey key = new HotspotKey(event, location.getWorld().getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
        hotspots.merge(key, 1L, Long::sum);
    }

    public void hotspot(String event, String world, int chunkX, int chunkZ) {
        if (!hotspotEnabled) return;
        hotspots.merge(new HotspotKey(event, world, chunkX, chunkZ), 1L, Long::sum);
    }

    public Snapshot snapshotAndReset() {
        List<Hotspot> hotspotSnapshot = new ArrayList<>(hotspots.size());
        hotspots.forEach((key, count) -> hotspotSnapshot.add(new Hotspot(key.event, key.world, key.chunkX, key.chunkZ, count)));
        hotspots.clear();
        return new Snapshot(chunkLoads.sumThenReset(), chunkUnloads.sumThenReset(),
                entitySpawns.sumThenReset(), entityDeaths.sumThenReset(), redstoneChanges.sumThenReset(),
                hopperMoves.sumThenReset(), pistonActions.sumThenReset(), explosions.sumThenReset(),
                blockBreaks.sumThenReset(), blockPlaces.sumThenReset(), hotspotSnapshot);
    }

    public record Snapshot(long chunkLoads, long chunkUnloads, long entitySpawns, long entityDeaths,
                           long redstoneChanges, long hopperMoves, long pistonActions, long explosions,
                           long blockBreaks, long blockPlaces, List<Hotspot> hotspots) {}
    public record Hotspot(String event, String world, int chunkX, int chunkZ, long count) {}
    private record HotspotKey(String event, String world, int chunkX, int chunkZ) {}
}
