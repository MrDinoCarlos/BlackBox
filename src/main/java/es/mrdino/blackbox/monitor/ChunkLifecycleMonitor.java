package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class ChunkLifecycleMonitor implements Listener {
    private static final String HEADER = "timestamp,event,world,chunk_x,chunk_z,new_chunk,save_chunk,observed_loaded_ms,inhabited_ticks,entities,block_entities,force_loaded,load_level,plugin_tickets";
    private final TelemetryStore store;
    private final Map<Key, Long> loadedSince = new HashMap<>();

    public ChunkLifecycleMonitor(Plugin plugin, TelemetryStore store) {
        this.store = store;
        long now = System.currentTimeMillis();
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) loadedSince.put(key(chunk), now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(ChunkLoadEvent event) {
        loadedSince.put(key(event.getChunk()), System.currentTimeMillis());
        record("load", event.getChunk(), event.isNewChunk(), false, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent event) {
        Long start = loadedSince.remove(key(event.getChunk()));
        long duration = start == null ? -1 : Math.max(0, System.currentTimeMillis() - start);
        record("unload", event.getChunk(), false, event.isSaveChunk(), duration);
    }

    private void record(String event, Chunk chunk, boolean newChunk, boolean saveChunk, long duration) {
        Instant now = Instant.now();
        int entities = chunk.isEntitiesLoaded() ? chunk.getEntities().length : 0;
        store.append("chunk-lifecycle", HEADER, now, Csv.row(now.toEpochMilli(), event,
                chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), newChunk, saveChunk, duration,
                chunk.getInhabitedTime(), entities, chunk.getTileEntities(false).length, chunk.isForceLoaded(),
                chunk.getLoadLevel().name(), chunk.getPluginChunkTickets().stream().map(Plugin::getName)
                        .sorted().collect(Collectors.joining(";"))));
    }

    private static Key key(Chunk chunk) { return new Key(chunk.getWorld().getUID().toString(), chunk.getX(), chunk.getZ()); }
    private record Key(String world, int x, int z) {}
}
