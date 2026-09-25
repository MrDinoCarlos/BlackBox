package es.mrdino.blackbox.monitor;

import com.sun.management.OperatingSystemMXBean;
import es.mrdino.blackbox.model.ServerSample;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;

public final class ServerSampler {
    private static final String EVENT_HEADER = "timestamp,interval_seconds,chunk_loads,chunk_unloads,entity_spawns,entity_deaths,redstone_changes,hopper_moves,piston_actions,explosions,block_breaks,block_places";
    private static final String HOTSPOT_HEADER = "timestamp,interval_seconds,event,world,chunk_x,chunk_z,count";
    private final Plugin plugin;
    private final TelemetryStore store;
    private final EventCounters counters;
    private final int intervalSeconds;
    private final OperatingSystemMXBean osBean;
    private volatile ServerSample latest;
    private volatile int latestScannedEntities;
    private volatile Consumer<ServerSample> sampleListener = ignored -> {};
    private BukkitTask task;

    public ServerSampler(Plugin plugin, TelemetryStore store, EventCounters counters, int intervalSeconds) {
        this.plugin = plugin;
        this.store = store;
        this.counters = counters;
        this.intervalSeconds = intervalSeconds;
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.osBean = bean instanceof OperatingSystemMXBean extended ? extended : null;
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample, 20L, intervalSeconds * 20L);
    }

    public void stop() { if (task != null) task.cancel(); }
    public void captureNow() { sample(); }
    public ServerSample latest() { return latest; }
    public void setLatestScannedEntities(int value) { latestScannedEntities = value; }
    public void setSampleListener(Consumer<ServerSample> listener) {
        sampleListener = listener == null ? ignored -> {} : listener;
    }

    private void sample() {
        Server server = plugin.getServer();
        Instant now = Instant.now();
        double[] tps = server.getTPS();
        long[] tickNanos = server.getTickTimes().clone();
        Arrays.sort(tickNanos);
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        int loadedChunks = 0;
        for (World world : server.getWorlds()) loadedChunks += world.getLoadedChunks().length;
        long directBytes = 0, mappedBytes = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equalsIgnoreCase("direct")) directBytes = pool.getMemoryUsed();
            else if (pool.getName().toLowerCase().startsWith("mapped")) mappedBytes += pool.getMemoryUsed();
        }
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        com.sun.management.UnixOperatingSystemMXBean unix = osBean instanceof com.sun.management.UnixOperatingSystemMXBean u ? u : null;
        latest = new ServerSample(now, at(tps, 0), at(tps, 1), at(tps, 2), server.getAverageTickTime(),
                quantile(tickNanos, .50), quantile(tickNanos, .95), quantile(tickNanos, .99),
                quantile(tickNanos, 1), heapUsed, runtime.maxMemory(),
                osBean == null ? -1 : percent(osBean.getProcessCpuLoad()),
                osBean == null ? -1 : percent(osBean.getCpuLoad()),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage(),
                ManagementFactory.getThreadMXBean().getThreadCount(), gcCount, gcTime,
                server.getOnlinePlayers().size(), loadedChunks, latestScannedEntities,
                plugin.getDataFolder().getUsableSpace(),
                ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(), directBytes, mappedBytes,
                osBean == null ? -1 : osBean.getTotalMemorySize(), osBean == null ? -1 : osBean.getFreeMemorySize(),
                osBean == null ? -1 : osBean.getTotalSwapSpaceSize(), osBean == null ? -1 : osBean.getFreeSwapSpaceSize(),
                osBean == null ? -1 : osBean.getCommittedVirtualMemorySize(),
                ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors(),
                ManagementFactory.getRuntimeMXBean().getUptime(), deadlocked == null ? 0 : deadlocked.length,
                unix == null ? -1 : unix.getOpenFileDescriptorCount(), unix == null ? -1 : unix.getMaxFileDescriptorCount(),
                store.droppedRows(), store.queuedRows());
        store.append("server", ServerSample.HEADER, now, latest.toCsv());

        EventCounters.Snapshot e = counters.snapshotAndReset();
        store.append("events", EVENT_HEADER, now, Csv.row(now.toEpochMilli(), intervalSeconds,
                e.chunkLoads(), e.chunkUnloads(), e.entitySpawns(), e.entityDeaths(), e.redstoneChanges(),
                e.hopperMoves(), e.pistonActions(), e.explosions(), e.blockBreaks(), e.blockPlaces()));
        for (EventCounters.Hotspot hotspot : e.hotspots()) {
            store.append("hotspots", HOTSPOT_HEADER, now, Csv.row(now.toEpochMilli(), intervalSeconds,
                    hotspot.event(), hotspot.world(), hotspot.chunkX(), hotspot.chunkZ(), hotspot.count()));
        }
        try {
            sampleListener.accept(latest);
        } catch (RuntimeException error) {
            plugin.getLogger().warning("Fallo al evaluar diagnostico automatico: " + error.getMessage());
        }
    }

    private static double at(double[] values, int index) { return index < values.length ? values[index] : -1; }
    private static double percent(double ratio) { return ratio < 0 ? -1 : ratio * 100; }
    private static double quantile(long[] sortedNanos, double q) {
        if (sortedNanos.length == 0) return 0;
        int index = Math.min(sortedNanos.length - 1, Math.max(0, (int) Math.ceil(q * sortedNanos.length) - 1));
        return sortedNanos[index] / 1_000_000.0;
    }
}
