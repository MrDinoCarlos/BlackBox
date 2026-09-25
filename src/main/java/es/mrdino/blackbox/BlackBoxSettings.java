package es.mrdino.blackbox;

import es.mrdino.blackbox.i18n.Language;
import org.bukkit.configuration.file.FileConfiguration;

public record BlackBoxSettings(
        Language language, int serverSampleSeconds, int chunkScanSeconds, int chunksPerTick, int playerSampleSeconds,
        Monitoring monitoring, InventoryOptions inventory, int retentionDays, int queueCapacity,
        int shutdownFlushSeconds, long maxTelemetryBytes, int storageSegmentMinutes,
        int maxRowsPerStreamMinute, int maxTelemetryRowChars, long maxProfileBytes, long maxReportBytes,
        long maxHashFileBytes, Privacy privacy, Thresholds thresholds, ReportOptions reports, Profiling profiling
) {
    public static BlackBoxSettings load(FileConfiguration config) {
        return new BlackBoxSettings(
                Language.parse(config.getString("language", "en")),
                bounded(config.getInt("sampling.server-seconds", 5), 1, 300),
                bounded(config.getInt("sampling.chunk-scan-seconds", 60), 10, 3600),
                bounded(config.getInt("sampling.chunks-per-tick", 8), 1, 128),
                bounded(config.getInt("sampling.player-seconds", 15), 1, 600),
                new Monitoring(
                        config.getBoolean("monitoring.player-samples", true),
                        config.getBoolean("monitoring.player-connections", true),
                        config.getBoolean("monitoring.entity-lifecycle", true),
                        config.getBoolean("monitoring.chunk-lifecycle", true),
                        config.getBoolean("monitoring.event-hotspots", true),
                        config.getBoolean("monitoring.server-warnings", true),
                        bounded(config.getInt("monitoring.warning-message-max-chars", 4000), 256, 100000),
                        bounded(config.getInt("monitoring.warning-stack-max-chars", 16000), 1024, 1_000_000),
                        config.getBoolean("monitoring.command-block-details", true),
                        config.getBoolean("monitoring.offline-region-census", true),
                        bounded(config.getInt("monitoring.region-census-minutes", 360), 10, 10080),
                        config.getBoolean("monitoring.region-parse-nbt", true),
                        config.getBoolean("monitoring.region-include-poi", true),
                        config.getBoolean("monitoring.detailed-entities", true),
                        new EventOptions(
                                config.getBoolean("monitoring.events.chunk-loads", true),
                                config.getBoolean("monitoring.events.entity-spawns", true),
                                config.getBoolean("monitoring.events.redstone", true),
                                config.getBoolean("monitoring.events.inventory-moves", true),
                                config.getBoolean("monitoring.events.pistons", true),
                                config.getBoolean("monitoring.events.explosions", true),
                                config.getBoolean("monitoring.events.block-changes", true))) ,
                new InventoryOptions(
                        config.getBoolean("inventory.scan-config-values", true),
                        config.getBoolean("inventory.analyze-pack-archives", true),
                        config.getBoolean("inventory.analyze-functions", true),
                        bounded(config.getInt("inventory.max-files", 20000), 100, 1_000_000),
                        bounded(config.getLong("inventory.max-config-file-mib", 1), 0, 64) * 1024L * 1024L,
                        bounded(config.getInt("inventory.max-config-entries", 200), 1, 10000),
                        bounded(config.getInt("inventory.max-zip-entries", 200000), 100, 2_000_000),
                        bounded(config.getInt("inventory.max-function-commands", 100000), 100, 1_000_000),
                        bounded(config.getLong("inventory.max-function-file-mib", 2), 1, 64) * 1024L * 1024L),
                bounded(config.getInt("storage.retention-days", 14), 1, 3650),
                bounded(config.getInt("storage.queue-capacity", 25000), 1000, 1_000_000),
                bounded(config.getInt("storage.shutdown-flush-seconds", 30), 5, 300),
                bounded(config.getLong("storage.max-telemetry-mib", 256), 64, 10240) * 1024L * 1024L,
                bounded(config.getInt("storage.segment-minutes", 5), 1, 60),
                bounded(config.getInt("storage.max-rows-per-stream-minute", 1000), 100, 1_000_000),
                bounded(config.getInt("storage.max-row-chars", 16384), 1024, 1_000_000),
                bounded(config.getLong("storage.max-profiles-mib", 256), 64, 10240) * 1024L * 1024L,
                bounded(config.getLong("storage.max-reports-mib", 256), 64, 10240) * 1024L * 1024L,
                bounded(config.getLong("storage.max-hash-file-mib", 25), 0, 1024) * 1024L * 1024L,
                new Privacy(AddressMode.parse(config.getString("privacy.player-addresses", "hash")),
                        config.getBoolean("privacy.store-player-names", true),
                        config.getBoolean("privacy.store-player-uuids", true),
                        config.getBoolean("privacy.store-player-locations", true),
                        config.getBoolean("privacy.store-entity-uuids", true)),
                new Thresholds(
                        bounded(config.getDouble("diagnostics.low-tps", 18.5), 0.0, 20.0),
                        bounded(config.getDouble("diagnostics.critical-tps", 15.0), 0.0, 20.0),
                        bounded(config.getDouble("diagnostics.high-mspt", 50.0), 1.0, 60000.0),
                        bounded(config.getDouble("diagnostics.heap-percent", 90.0), 1.0, 100.0),
                        bounded(config.getDouble("diagnostics.process-cpu-percent", 90.0), 1.0, 100.0),
                        bounded(config.getInt("diagnostics.high-ping-ms", 200), 0, 60000),
                        bounded(config.getInt("diagnostics.entities-per-chunk", 100), 1, 1_000_000),
                        bounded(config.getInt("diagnostics.block-entities-per-chunk", 80), 1, 1_000_000),
                        bounded(config.getInt("diagnostics.hoppers-per-chunk", 32), 1, 1_000_000),
                        bounded(config.getInt("diagnostics.command-blocks-per-chunk", 16), 1, 1_000_000),
                        bounded(config.getLong("diagnostics.high-redstone-events", 100000), 1, Long.MAX_VALUE),
                        bounded(config.getLong("diagnostics.high-hopper-events", 50000), 1, Long.MAX_VALUE),
                        bounded(config.getLong("diagnostics.entity-churn-events", 10000), 1, Long.MAX_VALUE),
                        bounded(config.getInt("diagnostics.short-entity-life-ticks", 1200), 1, 72_000_000),
                        bounded(config.getLong("diagnostics.hotspot-events", 50000), 1, Long.MAX_VALUE),
                        bounded(config.getInt("diagnostics.function-risk-score", 1), 0, 1_000_000),
                        bounded(config.getInt("diagnostics.correlation-min-samples", 12), 3, 100000),
                        bounded(config.getDouble("diagnostics.correlation-warning", 0.70), 0.0, 1.0),
                        bounded(config.getDouble("diagnostics.correlation-weak", 0.30), 0.0, 1.0),
                        bounded(config.getInt("diagnostics.max-findings-per-category", 5), 1, 100)),
                new ReportOptions(
                        config.getBoolean("reports.automatic-session-on-shutdown", true),
                        bounded(config.getInt("reports.shutdown-timeout-seconds", 10), 1, 60),
                        bounded(config.getLong("reports.latest-log-read-mib", 50), 0, 2048) * 1024L * 1024L,
                        bounded(config.getInt("reports.latest-log-findings", 20000), 0, 1_000_000),
                        bounded(config.getInt("reports.latest-log-line-max-chars", 4000), 256, 100000),
                        bounded(config.getInt("reports.aggregate-hotspots", 500), 1, 100000),
                        bounded(config.getInt("reports.aggregate-warnings", 200), 1, 100000),
                        bounded(config.getInt("reports.markdown-chunks", 50), 1, 10000),
                        bounded(config.getInt("reports.markdown-command-blocks", 100), 1, 10000),
                        bounded(config.getInt("reports.pdf-correlations", 20), 1, 1000),
                        bounded(config.getInt("reports.pdf-chunks", 40), 1, 10000),
                        bounded(config.getInt("reports.pdf-players", 50), 1, 10000),
                        bounded(config.getInt("reports.pdf-command-blocks", 60), 1, 10000),
                        bounded(config.getInt("reports.pdf-functions", 50), 1, 10000),
                        bounded(config.getInt("reports.pdf-hotspots", 50), 1, 10000),
                        bounded(config.getInt("reports.pdf-chunk-lifecycle", 40), 1, 10000),
                        bounded(config.getInt("reports.pdf-entity-types", 60), 1, 10000),
                        bounded(config.getInt("reports.pdf-warnings", 40), 1, 10000),
                        bounded(config.getInt("reports.pdf-incidents", 80), 1, 10000),
                        bounded(config.getInt("reports.pdf-plugins", 100), 1, 10000)),
                new Profiling(bounded(config.getInt("profiling.max-seconds", 300), 10, 3600),
                        bounded(config.getInt("profiling.top-methods", 100), 10, 10000),
                        config.getBoolean("profiling.automatic.enabled", true),
                        bounded(config.getDouble("profiling.automatic.tps-threshold", 16.0), 0.0, 20.0),
                        bounded(config.getDouble("profiling.automatic.tick-p95-ms", 75.0), 1.0, 60000.0),
                        bounded(config.getInt("profiling.automatic.consecutive-samples", 3), 1, 120),
                        bounded(config.getInt("profiling.automatic.seconds", 45), 10, 3600),
                        bounded(config.getInt("profiling.automatic.cooldown-minutes", 30), 1, 10080)));
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static long bounded(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static double bounded(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    public enum AddressMode {
        HASH, PLAIN, OFF;
        static AddressMode parse(String raw) {
            try { return valueOf(raw == null ? "HASH" : raw.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { return HASH; }
        }
    }

    public record Privacy(AddressMode playerAddresses, boolean storePlayerNames, boolean storePlayerUuids,
                          boolean storePlayerLocations, boolean storeEntityUuids) {}
    public record EventOptions(boolean chunkLoads, boolean entitySpawns, boolean redstone,
                               boolean inventoryMoves, boolean pistons, boolean explosions, boolean blockChanges) {}
    public record Monitoring(boolean playerSamples, boolean playerConnections, boolean entityLifecycle,
                             boolean chunkLifecycle, boolean eventHotspots, boolean serverWarnings,
                             int warningMessageMaxChars, int warningStackMaxChars, boolean commandBlockDetails,
                             boolean offlineRegionCensus, int regionCensusMinutes,
                             boolean regionParseNbt, boolean regionIncludePoi, boolean detailedEntities,
                             EventOptions events) {}
    public record InventoryOptions(boolean scanConfigValues, boolean analyzePackArchives, boolean analyzeFunctions,
                                   int maxFiles, long maxConfigReadBytes, int maxConfigEntries,
                                   int maxZipEntries, int maxFunctionCommands, long maxFunctionFileBytes) {}
    public record Thresholds(double lowTps, double criticalTps, double highMspt, double heapPercent,
                             double processCpuPercent, int highPingMs, int entitiesPerChunk,
                             int blockEntitiesPerChunk, int hoppersPerChunk, int commandBlocksPerChunk,
                             long highRedstoneEvents, long highHopperEvents, long entityChurnEvents,
                             int shortEntityLifeTicks, long hotspotEvents, int functionRiskScore,
                             int correlationMinSamples, double correlationWarning, double correlationWeak,
                             int maxFindingsPerCategory) {}
    public record ReportOptions(boolean automaticSessionOnShutdown, int shutdownTimeoutSeconds,
                                long latestLogReadBytes,
                                int latestLogFindings, int latestLogLineMaxChars,
                                int aggregateHotspots, int aggregateWarnings, int markdownChunks,
                                int markdownCommandBlocks, int pdfCorrelations,
                                int pdfChunks, int pdfPlayers, int pdfCommandBlocks, int pdfFunctions,
                                int pdfHotspots, int pdfChunkLifecycle, int pdfEntityTypes, int pdfWarnings,
                                int pdfIncidents, int pdfPlugins) {}
    public record Profiling(int maxSeconds, int topMethods, boolean automaticEnabled,
                            double automaticTpsThreshold, double automaticTickP95Ms,
                            int automaticConsecutiveSamples, int automaticSeconds,
                            int automaticCooldownMinutes) {}
}
