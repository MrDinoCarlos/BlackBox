package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.model.ChunkObservation;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class ChunkScanner {
    private static final String COMMAND_BLOCK_HEADER = "timestamp,world,x,y,z,material,name,command,conditional,facing,powered";
    private final Plugin plugin;
    private final TelemetryStore store;
    private final ServerSampler serverSampler;
    private final int intervalSeconds;
    private final int chunksPerTick;
    private final boolean detailedEntities;
    private final boolean commandBlockDetails;
    private final Queue<Chunk> pending = new ArrayDeque<>();
    private final AtomicReference<ScanSummary> latest = new AtomicReference<>(ScanSummary.empty());
    private BukkitTask task;
    private long nextScanMillis;
    private int cycleChunks;
    private int cycleEntities;
    private int cycleBlockEntities;
    private long cycleWorkMicros;
    private long cycleMaxChunkMicros;
    private Instant cycleStarted;

    public ChunkScanner(Plugin plugin, TelemetryStore store, ServerSampler serverSampler,
                        int intervalSeconds, int chunksPerTick, boolean detailedEntities,
                        boolean commandBlockDetails) {
        this.plugin = plugin;
        this.store = store;
        this.serverSampler = serverSampler;
        this.intervalSeconds = intervalSeconds;
        this.chunksPerTick = chunksPerTick;
        this.detailedEntities = detailedEntities;
        this.commandBlockDetails = commandBlockDetails;
    }

    public void start() {
        nextScanMillis = System.currentTimeMillis() + 5_000;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() { if (task != null) task.cancel(); }
    public ScanSummary latest() { return latest.get(); }

    public boolean requestScan() {
        if (!pending.isEmpty()) return false;
        nextScanMillis = 0;
        return true;
    }

    private void tick() {
        if (pending.isEmpty() && System.currentTimeMillis() >= nextScanMillis) beginCycle();
        int processed = 0;
        while (processed++ < chunksPerTick && !pending.isEmpty()) {
            Chunk chunk = pending.poll();
            if (chunk != null && chunk.isLoaded()) scan(chunk);
        }
        if (pending.isEmpty() && cycleStarted != null) finishCycle();
    }

    private void beginCycle() {
        cycleStarted = Instant.now();
        cycleChunks = 0;
        cycleEntities = 0;
        cycleBlockEntities = 0;
        cycleWorkMicros = 0;
        cycleMaxChunkMicros = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) pending.offer(chunk);
        }
        if (pending.isEmpty()) finishCycle();
    }

    private void scan(Chunk chunk) {
        long scanStarted = System.nanoTime();
        Instant now = Instant.now();
        Entity[] entities = chunk.isEntitiesLoaded() ? chunk.getEntities() : new Entity[0];
        BlockState[] states = chunk.getTileEntities(false);
        Map<EntityType, Integer> entityTypes = new EnumMap<>(EntityType.class);
        Map<Material, Integer> blockEntityTypes = new EnumMap<>(Material.class);
        int living = 0, items = 0, frames = 0, players = 0;
        int ticking = 0, persistent = 0, spawnerOrigin = 0, unaware = 0, named = 0, passengers = 0, tracked = 0;
        long totalTicksLived = 0;
        int maxTicksLived = 0;
        for (Entity entity : entities) {
            entityTypes.merge(entity.getType(), 1, Integer::sum);
            if (entity instanceof LivingEntity) living++;
            if (entity instanceof Item) items++;
            if (entity instanceof ItemFrame) frames++;
            if (entity instanceof Player) players++;
            if (detailedEntities) {
                if (entity.isTicking()) ticking++;
                if (entity.isPersistent()) persistent++;
                if (entity.fromMobSpawner()) spawnerOrigin++;
                if (entity instanceof Mob mob && !mob.isAware()) unaware++;
                if (entity.getCustomName() != null) named++;
                passengers += entity.getPassengers().size();
                tracked += entity.getTrackedBy().size();
                totalTicksLived += entity.getTicksLived();
                maxTicksLived = Math.max(maxTicksLived, entity.getTicksLived());
            }
        }
        int hoppers = 0, spawners = 0, commandBlocks = 0;
        for (BlockState state : states) {
            Material type = state.getType();
            blockEntityTypes.merge(type, 1, Integer::sum);
            if (type == Material.HOPPER) hoppers++;
            if (type == Material.SPAWNER || type == Material.TRIAL_SPAWNER) spawners++;
            if (state instanceof CommandBlock commandBlock) {
                commandBlocks++;
                if (commandBlockDetails) {
                    org.bukkit.block.data.BlockData blockData = state.getBlockData();
                    boolean conditional = blockData instanceof org.bukkit.block.data.type.CommandBlock data
                            && data.isConditional();
                    String facing = blockData instanceof org.bukkit.block.data.type.CommandBlock data
                            ? data.getFacing().name() : "";
                    store.append("command-blocks", COMMAND_BLOCK_HEADER, now,
                            Csv.row(now.toEpochMilli(), chunk.getWorld().getName(), state.getX(), state.getY(), state.getZ(),
                                    type.name(), commandBlock.getName(), commandBlock.getCommand(), conditional, facing,
                                    state.getBlock().isBlockPowered() || state.getBlock().isBlockIndirectlyPowered()));
                }
            }
        }
        long scanMicros = Math.max(0, (System.nanoTime() - scanStarted) / 1_000L);
        ChunkObservation observation = new ChunkObservation(now, chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                entities.length, living, items, frames, players, states.length, hoppers, spawners, commandBlocks,
                chunk.isForceLoaded(), formatCounts(entityTypes), formatCounts(blockEntityTypes), ticking, persistent,
                spawnerOrigin, unaware, named, passengers, tracked, totalTicksLived, maxTicksLived,
                chunk.getInhabitedTime(), chunk.getLoadLevel().name(),
                chunk.getPluginChunkTickets().stream().map(Plugin::getName).sorted().collect(Collectors.joining(";")),
                scanMicros);
        store.append("chunks", ChunkObservation.HEADER, now, observation.toCsv());
        cycleChunks++;
        cycleEntities += entities.length;
        cycleBlockEntities += states.length;
        cycleWorkMicros += scanMicros;
        cycleMaxChunkMicros = Math.max(cycleMaxChunkMicros, scanMicros);
    }

    private void finishCycle() {
        if (cycleStarted == null) return;
        ScanSummary summary = new ScanSummary(cycleStarted, Instant.now(), cycleChunks, cycleEntities,
                cycleBlockEntities, cycleWorkMicros, cycleMaxChunkMicros);
        latest.set(summary);
        serverSampler.setLatestScannedEntities(cycleEntities);
        cycleStarted = null;
        nextScanMillis = System.currentTimeMillis() + intervalSeconds * 1000L;
    }

    private static String formatCounts(Map<?, Integer> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Object::toString)))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    public record ScanSummary(Instant started, Instant finished, int chunks, int entities, int blockEntities,
                              long workMicros, long maxChunkMicros) {
        static ScanSummary empty() { return new ScanSummary(Instant.EPOCH, Instant.EPOCH, 0, 0, 0, 0, 0); }
        public long durationMillis() { return Math.max(0, finished.toEpochMilli() - started.toEpochMilli()); }
    }
}
