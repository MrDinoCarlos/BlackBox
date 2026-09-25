package es.mrdino.blackbox.report;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.inventory.InventorySnapshot;
import es.mrdino.blackbox.model.ServerSample;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record PdfReportData(
        Instant generated,
        Instant cutoff,
        Duration period,
        InventorySnapshot live,
        InventorySnapshot.FileInventory files,
        List<ServerSample> serverSamples,
        List<CorrelationRow> correlations,
        List<ChunkRow> chunks,
        List<PlayerRow> players,
        EventRow events,
        List<DiagnosticFinding> findings,
        List<CommandBlockRow> commandBlocks,
        List<HotspotRow> hotspots,
        List<ChunkLifecycleRow> chunkLifecycle,
        List<EntityLifecycleRow> entityLifecycle,
        List<WarningRow> warnings,
        List<RegionRow> regions,
        SessionSummary sessionSummary,
        Comparison comparison,
        List<IncidentRow> incidents,
        List<PluginAnalysisRow> pluginAnalysis,
        List<PerformanceEpisode> performanceEpisodes,
        List<ProfileIoRow> profileIo,
        BlackBoxSettings.ReportOptions reportOptions
) {
    public PdfReportData(Instant generated, Instant cutoff, Duration period, InventorySnapshot live,
                         InventorySnapshot.FileInventory files, List<ServerSample> serverSamples,
                         List<CorrelationRow> correlations, List<ChunkRow> chunks, List<PlayerRow> players,
                         EventRow events, List<DiagnosticFinding> findings, List<CommandBlockRow> commandBlocks,
                         List<HotspotRow> hotspots, List<ChunkLifecycleRow> chunkLifecycle,
                         List<EntityLifecycleRow> entityLifecycle, List<WarningRow> warnings,
                         List<RegionRow> regions) {
        this(generated, cutoff, period, live, files, serverSamples, correlations, chunks, players, events,
                findings, commandBlocks, hotspots, chunkLifecycle, entityLifecycle, warnings, regions,
                new SessionSummary(0, 0, 0, 0, List.of()),
                new Comparison("Periodo anterior", "Periodo actual", List.of(),
                        "No hay suficientes muestras para comparar.", List.of()), List.of(), List.of(), List.of(), List.of(),
                defaultOptions());
    }

    public PdfReportData(Instant generated, Instant cutoff, Duration period, InventorySnapshot live,
                         InventorySnapshot.FileInventory files, List<ServerSample> serverSamples,
                         List<CorrelationRow> correlations, List<ChunkRow> chunks, List<PlayerRow> players,
                         EventRow events, List<DiagnosticFinding> findings, List<CommandBlockRow> commandBlocks,
                         List<HotspotRow> hotspots, List<ChunkLifecycleRow> chunkLifecycle,
                         List<EntityLifecycleRow> entityLifecycle, List<WarningRow> warnings,
                         List<RegionRow> regions, SessionSummary sessionSummary, Comparison comparison,
                         List<IncidentRow> incidents, List<PluginAnalysisRow> pluginAnalysis) {
        this(generated, cutoff, period, live, files, serverSamples, correlations, chunks, players, events,
                findings, commandBlocks, hotspots, chunkLifecycle, entityLifecycle, warnings, regions,
                sessionSummary, comparison, incidents, pluginAnalysis, List.of(), List.of(), defaultOptions());
    }

    private static BlackBoxSettings.ReportOptions defaultOptions() {
        return new BlackBoxSettings.ReportOptions(true, 10, 50L * 1024 * 1024, 20_000,
                4_000, 500, 200, 50, 100, 20, 40, 50, 60, 50, 50, 40, 60, 40, 80, 100);
    }

    public record ChunkRow(String key, long samples, int entityMin, double entityAverage, int entityMax,
                           int blockMin, double blockAverage, int blockMax, int itemMax, int frameMax,
                           int hopperMax, int spawnerMax, int commandMax, double averageScanMicros,
                           long maxScanMicros, double risk, String entityTypes, String blockEntityTypes,
                           boolean forceLoaded, String pluginTickets) {}
    public record CorrelationRow(String signal, double correlationWithMspt, int samples, String interpretation) {}
    public record PlayerRow(String name, int samples, int pingMin, double pingAverage, int pingMax) {}
    public record EventRow(long chunkLoads, long chunkUnloads, long entitySpawns, long entityDeaths,
                           long redstone, long hoppers, long pistons, long explosions) {}
    public record HotspotRow(String event, String location, long count) {}
    public record ChunkLifecycleRow(String location, long loads, long unloads, long newChunks,
                                    double averageLoadedMs, long maxLoadedMs) {}
    public record CommandBlockRow(String position, String world, int x, int y, int z, String material,
                                  String name, String command, long observations, Instant firstSeen,
                                  Instant lastSeen, long executions, Instant firstExecution, Instant lastExecution,
                                  long poweredObservations, String conditional, String facing, String cadence,
                                  String risk, String impact, String recommendation) {}
    public record EntityLifecycleRow(String type, long spawns, long removals, long observedBalance,
                                     double averageTicksLived, int maxTicksLived,
                                     double averageObservedLifetimeMs, long maxObservedLifetimeMs,
                                     String topSpawnReason, String topRemoveCause, String topLocation,
                                     long topLocationEvents, long ticking, long persistent, long fromSpawner,
                                     long named, long passengers, long trackedPlayers, String topAttributedSource) {
        public EntityLifecycleRow(String type, long spawns, long removals, double averageTicksLived,
                                  int maxTicksLived, String topSpawnReason, String topRemoveCause) {
            this(type, spawns, removals, spawns - removals, averageTicksLived, maxTicksLived,
                    0, 0, topSpawnReason, topRemoveCause, "", 0, 0, 0, 0, 0, 0, 0, "");
        }
    }
    public record WarningRow(String level, String source, String message, long count) {}
    public record RegionRow(String kind, long files, long chunks, long bytes, long malformedEntries) {}
    public record SessionSummary(int starts, int cleanStops, int uncleanStarts, long observedDowntimeMs,
                                 List<EnvironmentChange> changes) {}
    public record EnvironmentChange(Instant timestamp, String added, String removed, String updated) {}
    public record Comparison(String beforeLabel, String afterLabel, List<ComparisonMetric> metrics,
                             String assessment, List<String> context) {}
    public record ComparisonMetric(String metric, String before, String after, String delta,
                                   String reading) {}
    public record IncidentRow(Instant timestamp, DiagnosticFinding.Severity severity, String signal,
                              String location, String detail) {}
    public record PluginAnalysisRow(String plugin, String version, int syncTasks, int asyncTasks,
                                    int listeners, long profileSamples, long totalProfileSamples,
                                    String assessment) {}
    public record PerformanceEpisode(Instant startedAt, Instant worstAt, Instant recoveredAt,
                                     DiagnosticFinding.Severity severity, String trigger,
                                     double minTps, double maxTickP95Ms, double baselineMspt,
                                     double incidentMspt, double recoveryMspt, long durationMs,
                                     String probableCause, String evidence, String location,
                                     String confidence, String recommendation) {}
    public record ProfileIoRow(Instant startedAt, Instant completedAt, String profileId, String trigger,
                               long socketReadBytes, long socketWriteBytes, long fileReadBytes,
                               long fileWriteBytes, long gcEvents, long socketIoNanos,
                               long maxSocketIoNanos, long fileIoNanos, long maxFileIoNanos) {}
}
