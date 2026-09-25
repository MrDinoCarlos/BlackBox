package es.mrdino.blackbox.report;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.i18n.Messages;
import es.mrdino.blackbox.inventory.FileInventoryService;
import es.mrdino.blackbox.inventory.InventorySnapshot;
import es.mrdino.blackbox.model.ChunkObservation;
import es.mrdino.blackbox.model.ServerSample;
import es.mrdino.blackbox.storage.DiskQuota;
import es.mrdino.blackbox.util.Csv;
import es.mrdino.blackbox.util.ChunkLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public final class ReportService implements AutoCloseable {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private final Path telemetryRoot;
    private final Path reportsRoot;
    private final Path inventoryRoot;
    private final Path startupDiagnosticsFile;
    private final Path serverRoot;
    private final Path clientCrashReportsRoot;
    private final FileInventoryService fileInventory;
    private final BlackBoxSettings.Thresholds thresholds;
    private final BlackBoxSettings.ReportOptions reportOptions;
    private final long maxDiskBytes;
    private final Messages messages;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "blackbox-report");
        thread.setDaemon(true);
        return thread;
    });

    public ReportService(Path dataFolder, Path serverRoot, long maxHashBytes,
                         BlackBoxSettings.InventoryOptions inventoryOptions,
                         BlackBoxSettings.Thresholds thresholds,
                         BlackBoxSettings.ReportOptions reportOptions, long maxDiskBytes) throws IOException {
        this(dataFolder, serverRoot, maxHashBytes, inventoryOptions, thresholds, reportOptions, maxDiskBytes,
                Messages.spanish());
    }

    public ReportService(Path dataFolder, Path serverRoot, long maxHashBytes,
                         BlackBoxSettings.InventoryOptions inventoryOptions,
                         BlackBoxSettings.Thresholds thresholds,
                         BlackBoxSettings.ReportOptions reportOptions, long maxDiskBytes,
                         Messages messages) throws IOException {
        this.telemetryRoot = dataFolder.resolve("telemetry");
        this.reportsRoot = dataFolder.resolve("reports");
        this.inventoryRoot = dataFolder.resolve("inventory");
        this.startupDiagnosticsFile = dataFolder.resolve("startup-diagnostics.log");
        this.serverRoot = serverRoot;
        this.clientCrashReportsRoot = dataFolder.resolve("client-crash-reports");
        this.fileInventory = new FileInventoryService(serverRoot, maxHashBytes, inventoryOptions);
        this.thresholds = thresholds;
        this.reportOptions = reportOptions;
        this.maxDiskBytes = maxDiskBytes;
        this.messages = messages == null ? Messages.english() : messages;
        Files.createDirectories(reportsRoot);
        Files.createDirectories(clientCrashReportsRoot);
        DiskQuota.directories(reportsRoot, maxDiskBytes, null);
    }

    public CompletableFuture<Path> generate(Duration period, InventorySnapshot live) {
        return CompletableFuture.supplyAsync(() -> {
            try { return generateSync(period, live, null); }
            catch (IOException error) { throw new RuntimeException(error); }
        }, executor);
    }

    public Path generateSession(Instant sessionStarted, InventorySnapshot live) throws IOException {
        return generateSync(Duration.between(sessionStarted, Instant.now()), live, sessionStarted);
    }

    public Future<Path> generateSessionAsync(Instant sessionStarted, InventorySnapshot live) {
        return executor.submit(() -> generateSession(sessionStarted, live));
    }

    private synchronized Path generateSync(Duration requestedPeriod, InventorySnapshot live,
                                           Instant sessionStarted) throws IOException {
        checkCancelled();
        Instant generated = Instant.now();
        Instant cutoff = sessionStarted == null ? generated.minus(requestedPeriod) : sessionStarted;
        Duration period = Duration.between(cutoff, generated);
        List<ServerSample> serverSamples = read("server-", cutoff, ServerSample::parse);
        Instant previousCutoff = cutoff.minus(period);
        List<ServerSample> previousSamples = read("server-", previousCutoff, ServerSample::parse).stream()
                .filter(s -> s.timestamp().isBefore(cutoff)).toList();
        List<ChunkObservation> chunkSamples = read("chunks-", cutoff, ChunkObservation::parse);
        List<List<String>> playerRows = readRows("players-", cutoff);
        List<List<String>> eventRows = readRows("events-", cutoff);
        List<List<String>> commandBlockRows = readRows("command-blocks-", cutoff);
        List<List<String>> commandExecutionRows = readRows("command-executions-", cutoff);
        List<List<String>> hotspotRows = readRows("hotspots-", cutoff);
        List<List<String>> chunkLifecycleRows = readRows("chunk-lifecycle-", cutoff);
        List<List<String>> lifecycleRows = readRows("entity-lifecycle-", cutoff);
        List<List<String>> warningRows = readRows("warnings-", cutoff);
        List<List<String>> allSessionRows = readRows("sessions-", Instant.EPOCH);
        List<List<String>> profileRows = readRows("profiles-", cutoff);
        List<List<String>> profilePluginRows = readRows("profile-plugins-", cutoff);
        List<List<String>> profileIoRows = readRows("profile-io-", cutoff);
        List<String> serverLogFindings = readServerLogFindings(cutoff);
        List<StartupDiagnosticEntry> startupDiagnostics = readStartupDiagnostics(cutoff);
        List<List<String>> regionRows = readCsv(inventoryRoot.resolve("regions-latest.csv"));
        checkCancelled();
        InventorySnapshot.FileInventory files = fileInventory.scan();
        checkCancelled();

        Map<String, ChunkAggregate> chunks = aggregateChunks(chunkSamples);
        Map<String, PlayerAggregate> players = aggregatePlayers(playerRows);
        EventAggregate events = aggregateEvents(eventRows);
        List<PdfReportData.CorrelationRow> correlations = correlations(serverSamples, eventRows);
        List<PdfReportData.HotspotRow> hotspots = aggregateHotspots(hotspotRows);
        List<PdfReportData.ChunkLifecycleRow> chunkLifecycle = aggregateChunkLifecycle(chunkLifecycleRows);
        List<PdfReportData.EntityLifecycleRow> lifecycle = aggregateLifecycle(lifecycleRows, commandExecutionRows);
        List<PdfReportData.WarningRow> warnings = new ArrayList<>(aggregateWarnings(warningRows));
        warnings.addAll(aggregateLogFindings(serverLogFindings));
        warnings.sort(Comparator.comparingLong(PdfReportData.WarningRow::count).reversed());
        List<PdfReportData.RegionRow> regions = aggregateRegions(regionRows);
        List<PdfReportData.CommandBlockRow> commandBlocks = aggregateCommandBlocks(commandBlockRows, commandExecutionRows);
        PdfReportData.SessionSummary sessionSummary = sessionSummary(allSessionRows, cutoff, generated);
        PdfReportData.Comparison comparison = comparison(previousSamples, serverSamples, previousCutoff, cutoff,
                generated, sessionSummary);
        List<PdfReportData.IncidentRow> incidents = incidents(serverSamples, hotspotRows, warningRows,
                allSessionRows, profileRows, cutoff);
        List<PdfReportData.PluginAnalysisRow> pluginAnalysis = pluginAnalysis(live, profilePluginRows);
        List<PdfReportData.PerformanceEpisode> performanceEpisodes = new PerformanceAnalyzer(thresholds)
                .analyze(serverSamples, eventRows, hotspotRows, lifecycleRows, warningRows,
                        profileRows, profilePluginRows);
        List<PdfReportData.ProfileIoRow> profileIo = profileIo(profileIoRows);
        List<DiagnosticFinding> crashFindings = crashReportFindings(cutoff);
        List<DiagnosticFinding> findings = new ArrayList<>(diagnose(serverSamples, chunks, players, events,
                commandBlocks, hotspots, lifecycle, warnings, regions, files, correlations));
        findings.addAll(pluginDependencyFindings(live));
        findings.addAll(crashFindings);
        int visibleStartupDiagnostics = Math.min(startupDiagnostics.size(), reportOptions.pdfWarnings());
        startupDiagnostics.stream().limit(visibleStartupDiagnostics)
                .map(ReportService::startupFinding).forEach(findings::add);
        if (startupDiagnostics.size() > visibleStartupDiagnostics) {
            findings.add(new DiagnosticFinding(DiagnosticFinding.Severity.INFO,
                    "Diagnósticos de inicio adicionales",
                    (startupDiagnostics.size() - visibleStartupDiagnostics)
                            + " incidencias adicionales están disponibles en startup-diagnostics.csv.",
                    "El PDF limita el detalle para mantener un tamaño manejable.",
                    "Revisa el CSV anexo para consultar todas las causas y soluciones sugeridas."));
        }

        String stem = sessionStarted == null
                ? "blackbox-" + FILE_TIME.format(generated) + "-" + compact(period)
                : "blackbox-session-" + FILE_TIME.format(sessionStarted) + "-to-" + FILE_TIME.format(generated);
        Path directory = reportsRoot.resolve(stem);
        Files.createDirectories(directory);
        writeCommands(directory.resolve("commands.csv"), live.commands());
        writeCommandAliases(directory.resolve("command-aliases.csv"), live.commands());
        writePlugins(directory.resolve("plugins.csv"), live.plugins());
        writePluginAuthors(directory.resolve("plugin-authors.csv"), live.plugins());
        writePluginDependencies(directory.resolve("plugin-dependencies.csv"), live.plugins());
        writeWorlds(directory.resolve("worlds.csv"), live.worlds());
        writeDataPacks(directory.resolve("datapacks.csv"), live.dataPacks());
        writeDataPackFeatures(directory.resolve("datapack-features.csv"), live.dataPacks());
        writeResourcePack(directory.resolve("resource-pack.csv"), live.resourcePack());
        writeFiles(directory.resolve("files.csv"), files.files());
        writeConfigEntries(directory.resolve("file-config-entries.csv"), files.files());
        writePacks(directory.resolve("packs.csv"), files.packs());
        writeFunctions(directory.resolve("functions.csv"), files.functions());
        writeCommandBlocks(directory.resolve("command-blocks.csv"), latestCommandBlocks(commandBlockRows));
        writeRows(directory.resolve("command-executions.csv"), "timestamp,world,x,y,z,name,command", commandExecutionRows);
        writeCommandBlockAnalysis(directory.resolve("command-block-analysis.csv"), commandBlocks);
        writeRows(directory.resolve("hotspots.csv"), "timestamp,interval_seconds,event,world,chunk_x,chunk_z,count,center_x,center_z,tp_command",
                enrichChunkRows(hotspotRows, 3, 4, 5));
        writeRows(directory.resolve("chunk-lifecycle.csv"), "timestamp,event,world,chunk_x,chunk_z,new_chunk,save_chunk,observed_loaded_ms,inhabited_ticks,entities,block_entities,force_loaded,load_level,center_x,center_z,tp_command",
                enrichChunkRows(withoutColumn(chunkLifecycleRows, 13), 2, 3, 4));
        writeChunkLifecycleTickets(directory.resolve("chunk-lifecycle-plugin-tickets.csv"), chunkLifecycleRows);
        writeRows(directory.resolve("entity-lifecycle.csv"), "timestamp,event,uuid,type,spawn_reason,remove_cause,world,x,y,z,chunk_x,chunk_z,ticks_lived,observed_lifetime_ms,ticking,persistent,from_spawner,custom_name,passengers,tracked_players,origin,attributed_source,attribution_confidence", lifecycleRows);
        writeEntitySummary(directory.resolve("entity-summary.csv"), lifecycle);
        writeRows(directory.resolve("warnings.csv"), "timestamp,level,logger,message,thrown_type,stack", warningRows);
        List<List<String>> reportSessionRows = allSessionRows.stream()
                .filter(r -> timestamp(r).map(t -> !t.isBefore(previousCutoff)).orElse(false)).toList();
        writeSessionHistory(directory.resolve("session-history.csv"), reportSessionRows);
        writeSessionPlugins(directory.resolve("session-plugins.csv"), reportSessionRows);
        writeSessionDataPacks(directory.resolve("session-datapacks.csv"), reportSessionRows);
        writeRows(directory.resolve("profiles.csv"), "timestamp,event,profile_id,trigger,reason,seconds,path", profileRows);
        writeIncidents(directory.resolve("incidents.csv"), incidents);
        writePluginAnalysis(directory.resolve("plugin-analysis.csv"), pluginAnalysis);
        writePerformanceEpisodes(directory.resolve("performance-episodes.csv"), performanceEpisodes);
        writeProfileIo(directory.resolve("profile-io.csv"), profileIo);
        writeDiagnosticFindings(directory.resolve("crash-analysis.csv"), crashFindings);
        Files.write(directory.resolve("server-log-findings.txt"), serverLogFindings, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        writeRows(directory.resolve("startup-diagnostics.csv"),
                "timestamp,level,category,stage,origin,logger,error,probable_cause,suggested_solution",
                startupDiagnostics.stream().map(StartupDiagnosticEntry::toRow).toList());
        copyIfPresent(inventoryRoot.resolve("regions-latest.csv"), directory.resolve("regions.csv"));
        copyIfPresent(inventoryRoot.resolve("disk-chunks-latest.csv"), directory.resolve("disk-chunks.csv"));
        Path markdown = directory.resolve("report.md");
        Files.writeString(markdown, messages.text(markdown(generated, cutoff, period, live, files, serverSamples, chunks,
                players, events, findings, commandBlocks, sessionSummary, comparison, incidents, pluginAnalysis,
                performanceEpisodes, profileIo)), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        List<PdfReportData.ChunkRow> pdfChunks = chunks.values().stream().map(c -> new PdfReportData.ChunkRow(
                c.key, c.samples, c.entityMin, c.entityAverage(), c.entityMax, c.blockMin, c.blockAverage(),
                c.blockMax, c.itemMax, c.frameMax, c.hopperMax, c.spawnerMax, c.commandMax,
                c.scanAverage(), c.scanMax, c.risk(), c.entityTypes(), c.blockEntityTypes(),
                c.forceLoaded, String.join(";", c.pluginTickets))).toList();
        writeChunkAnalysis(directory.resolve("chunk-analysis.csv"), pdfChunks);
        writeChunkEntityTypes(directory.resolve("chunk-entity-types.csv"), pdfChunks);
        writeChunkBlockEntityTypes(directory.resolve("chunk-block-entity-types.csv"), pdfChunks);
        writeChunkPluginTickets(directory.resolve("chunk-plugin-tickets.csv"), pdfChunks);
        List<PdfReportData.PlayerRow> pdfPlayers = players.values().stream().map(p -> new PdfReportData.PlayerRow(
                p.name, p.samples, p.pingMin, p.pingAverage(), p.pingMax)).toList();
        PdfReportData.EventRow pdfEvents = new PdfReportData.EventRow(events.chunkLoads, events.chunkUnloads,
                events.entitySpawns, events.entityDeaths, events.redstone, events.hoppers, events.pistons, events.explosions);
        Path pdf = directory.resolve(sessionStarted == null ? "BlackBox-report.pdf" : "BlackBox-session-report.pdf");
        checkCancelled();
        ProfessionalPdfReport.write(pdf, new PdfReportData(generated, cutoff, period, live, files,
                serverSamples, correlations, pdfChunks, pdfPlayers, pdfEvents, findings, commandBlocks,
                hotspots, chunkLifecycle, lifecycle, warnings, regions, sessionSummary, comparison,
                incidents, pluginAnalysis, performanceEpisodes, profileIo, reportOptions), messages);
        DiskQuota.directories(reportsRoot, maxDiskBytes, directory);
        return pdf;
    }

    private String markdown(Instant generated, Instant cutoff, Duration period, InventorySnapshot live,
                            InventorySnapshot.FileInventory files, List<ServerSample> samples,
                             Map<String, ChunkAggregate> chunks, Map<String, PlayerAggregate> players,
                             EventAggregate events, List<DiagnosticFinding> findings,
                             List<PdfReportData.CommandBlockRow> commandBlocks, PdfReportData.SessionSummary sessions,
                             PdfReportData.Comparison comparison, List<PdfReportData.IncidentRow> incidents,
                             List<PdfReportData.PluginAnalysisRow> pluginAnalysis,
                             List<PdfReportData.PerformanceEpisode> performanceEpisodes,
                             List<PdfReportData.ProfileIoRow> profileIo) {
        StringBuilder out = new StringBuilder(32_768);
        out.append("# BlackBox — informe del servidor\n\n")
                .append("Generado: **").append(DISPLAY_TIME.format(generated)).append("**  \n")
                .append("Periodo solicitado: **").append(human(period)).append("** (desde ")
                .append(DISPLAY_TIME.format(cutoff)).append(")  \n")
                .append("Muestras del servidor: **").append(samples.size()).append("** · observaciones de chunks: **")
                .append(chunks.values().stream().mapToLong(c -> c.samples).sum()).append("**\n\n");

        out.append("## Diagnostico\n\n");
        if (findings.isEmpty()) out.append("No se superaron los umbrales configurados durante las muestras disponibles.\n\n");
        for (DiagnosticFinding finding : findings) {
            out.append("### ").append(finding.severity()).append(" — ").append(md(finding.title())).append("\n\n")
                    .append("**Donde:** ").append(md(finding.location())).append("  \n")
                    .append("**Evidencia:** ").append(md(finding.evidence())).append("  \n")
                    .append("**Causa probable:** ").append(md(finding.probableCause())).append("  \n")
                    .append("**Impacto:** ").append(md(finding.impact())).append("  \n")
                    .append("**Tiempo observado:** ").append(md(finding.observedTime())).append("  \n")
                    .append("**Solucion:** ").append(md(finding.action())).append("  \n")
                    .append("**Verificacion:** ").append(md(finding.verification())).append("\n\n");
        }

        out.append("## Historial y comparativa\n\n")
                .append("Arranques: **").append(sessions.starts()).append("** · apagados limpios: **")
                .append(sessions.cleanStops()).append("** · transiciones sin cierre: **")
                .append(sessions.uncleanStarts()).append("** · tiempo apagado observado: **")
                .append(humanDurationMillis(sessions.observedDowntimeMs())).append("**  \n\n")
                .append(comparison.assessment()).append("\n\n");
        if (!comparison.metrics().isEmpty()) {
            out.append("| Metrica | Anterior | Actual | Cambio | Lectura |\n|---|---:|---:|---:|---|\n");
            for (var metric : comparison.metrics()) out.append('|').append(md(metric.metric())).append('|')
                    .append(md(metric.before())).append('|').append(md(metric.after())).append('|')
                    .append(md(metric.delta())).append('|').append(md(metric.reading())).append("|\n");
            out.append('\n');
        }

        out.append("## Cronologia de incidentes\n\n| Momento | Severidad | Senal | Localizacion | TP al centro | Detalle |\n")
                .append("|---|---|---|---|---|---|\n");
        incidents.stream().limit(reportOptions.pdfIncidents()).forEach(i -> out.append('|')
                .append(DISPLAY_TIME.format(i.timestamp())).append('|').append(i.severity()).append('|')
                .append(md(i.signal())).append('|').append(md(i.location())).append('|')
                .append(md(tpCommand(i.location()))).append('|').append(md(i.detail())).append("|\n"));
        out.append('\n');

        out.append("## Analisis causa-efecto de degradaciones\n\n");
        if (performanceEpisodes.isEmpty()) {
            out.append("No se detectaron ventanas con TPS bajo o tick p95 por encima del umbral.\n\n");
        } else {
            out.append("| Inicio | Peor punto | Recuperacion | Duracion | TPS min | p95 max | Causa probable | Confianza | Evidencia |\n")
                    .append("|---|---|---|---:|---:|---:|---|---|---|\n");
            for (var episode : performanceEpisodes) out.append('|').append(DISPLAY_TIME.format(episode.startedAt()))
                    .append('|').append(DISPLAY_TIME.format(episode.worstAt())).append('|')
                    .append(episode.recoveredAt() == null ? "Sin recuperar en la ventana" : DISPLAY_TIME.format(episode.recoveredAt()))
                    .append('|').append(humanDurationMillis(episode.durationMs())).append('|')
                    .append(one(episode.minTps())).append('|').append(one(episode.maxTickP95Ms())).append(" ms|")
                    .append(md(episode.probableCause())).append('|').append(md(episode.confidence())).append('|')
                    .append(md(episode.evidence())).append("|\n");
            out.append("\nEl CSV `performance-episodes.csv` incluye MSPT antes, durante y despues, localizacion y accion recomendada.\n\n");
        }
        if (!profileIo.isEmpty()) {
            out.append("### E/S medida durante perfiles JFR\n\n| Perfil | Inicio | Socket R/W | Archivo R/W | Latencia max socket/archivo | GC |\n|---|---|---:|---:|---:|---:|\n");
            for (var io : profileIo) out.append('|').append(md(io.profileId())).append('|')
                    .append(DISPLAY_TIME.format(io.startedAt())).append('|').append(bytes(io.socketReadBytes()))
                    .append(" / ").append(bytes(io.socketWriteBytes())).append('|').append(bytes(io.fileReadBytes()))
                    .append(" / ").append(bytes(io.fileWriteBytes())).append('|')
                    .append(one(io.maxSocketIoNanos() / 1_000_000d)).append(" / ")
                    .append(one(io.maxFileIoNanos() / 1_000_000d)).append(" ms|")
                    .append(io.gcEvents()).append("|\n");
            out.append('\n');
        }

        out.append("## Rendimiento global\n\n");
        if (samples.isEmpty()) {
            out.append("No hay muestras en este periodo. BlackBox puede haber arrancado despues del inicio solicitado.\n\n");
        } else {
            metricTable(out, samples);
        }

        out.append("## Actividad observada\n\n")
                .append("| Evento | Total | Por minuto |\n|---|---:|---:|\n");
        addEvent(out, "Carga de chunks", events.chunkLoads, period);
        addEvent(out, "Descarga de chunks", events.chunkUnloads, period);
        addEvent(out, "Spawns de entidades", events.entitySpawns, period);
        addEvent(out, "Muertes de entidades", events.entityDeaths, period);
        addEvent(out, "Cambios de redstone", events.redstone, period);
        addEvent(out, "Movimientos de hopper", events.hoppers, period);
        addEvent(out, "Acciones de pistones", events.pistons, period);
        addEvent(out, "Explosiones", events.explosions, period);
        out.append('\n');

        out.append("## Chunks con mayor densidad\n\n")
                .append("Los minimos, medias y maximos se calculan sobre cada observacion del periodo.\n\n")
                .append("| Mundo:chunk | Centro X/Z | TP | Muestras | Entidades min/media/max | Block entities min/media/max | Items max | Frames max | Hoppers max | Spawners max | Command blocks max |\n")
                .append("|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        chunks.values().stream().sorted(Comparator.comparingDouble(ChunkAggregate::risk).reversed())
                .limit(reportOptions.markdownChunks())
                .forEach(c -> out.append('|').append(md(c.key)).append('|').append(md(center(c.key))).append('|')
                        .append(md(tpCommand(c.key))).append('|').append(c.samples).append('|')
                        .append(c.entityMin).append('/').append(one(c.entityAverage())).append('/').append(c.entityMax).append('|')
                        .append(c.blockMin).append('/').append(one(c.blockAverage())).append('/').append(c.blockMax).append('|')
                        .append(c.itemMax).append('|').append(c.frameMax).append('|').append(c.hopperMax).append('|')
                        .append(c.spawnerMax).append('|').append(c.commandMax).append("|\n"));
        out.append('\n');

        out.append("## Command blocks observados\n\n")
                .append("| Posicion / TP | Tipo | Comando | Ejecuciones | Tiempo observado | Riesgo | Impacto | Solucion |\n")
                .append("|---|---|---|---:|---|---|---|---|\n");
        commandBlocks.stream().limit(reportOptions.markdownCommandBlocks()).forEach(row -> out.append('|')
                .append(md(row.position() + " / " + commandTp(row))).append('|').append(md(row.material())).append('|')
                .append(md(row.command())).append('|').append(row.executions()).append('|')
                .append(md(observed(row.firstSeen(), row.lastSeen()))).append('|').append(md(row.risk())).append('|')
                .append(md(row.impact())).append('|').append(md(row.recommendation())).append("|\n"));
        if (commandBlocks.size() > 100) out.append("\nLa tabla muestra 100 de ").append(commandBlocks.size()).append("; el CSV contiene el listado completo.\n");
        out.append('\n');

        out.append("## Jugadores y conexion\n\n")
                .append("El ping es estimado por el servidor. Minecraft no envia el FPS del cliente, por lo que BlackBox no inventa ese dato.\n\n")
                .append("| Jugador | Muestras | Ping min/media/max (ms) |\n|---|---:|---:|\n");
        players.values().stream().sorted(Comparator.comparingInt((PlayerAggregate p) -> p.pingMax).reversed())
                .forEach(p -> out.append('|').append(md(p.name)).append('|').append(p.samples).append('|')
                        .append(p.pingMin).append('/').append(one(p.pingAverage())).append('/').append(p.pingMax).append("|\n"));
        out.append('\n');

        appendInventory(out, live, files, commandBlocks.size());
        out.append("## Analisis operativo de plugins\n\n")
                .append("| Plugin | Version | Tasks S/A | Listeners | JFR plugin/total | Lectura |\n")
                .append("|---|---|---:|---:|---:|---|\n");
        pluginAnalysis.stream().limit(reportOptions.pdfPlugins()).forEach(p -> out.append('|')
                .append(md(p.plugin())).append('|').append(md(p.version())).append('|')
                .append(p.syncTasks()).append('/').append(p.asyncTasks()).append('|').append(p.listeners()).append('|')
                .append(p.profileSamples()).append('/').append(p.totalProfileSamples()).append('|')
                .append(md(p.assessment())).append("|\n"));
        out.append('\n');
        appendLimits(out);
        return out.toString();
    }

    private void metricTable(StringBuilder out, List<ServerSample> samples) {
        out.append("| Metrica | Min | Media | Max |\n|---|---:|---:|---:|\n");
        metric(out, "TPS (1 min)", samples, ServerSample::tps1m);
        metric(out, "MSPT medio de Paper", samples, ServerSample::averageMspt);
        metric(out, "Tick p95 (ms)", samples, ServerSample::tickP95Ms);
        metric(out, "Tick max (ms)", samples, ServerSample::tickMaxMs);
        metric(out, "CPU del proceso (%)", samples, ServerSample::processCpuPercent);
        metric(out, "CPU del sistema (%)", samples, ServerSample::systemCpuPercent);
        metric(out, "Heap usado (%)", samples, s -> 100.0 * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()));
        metric(out, "RAM fisica usada (%)", samples,
                s -> s.physicalMemoryBytes() > 0 ? 100.0 * (s.physicalMemoryBytes() - s.freePhysicalMemoryBytes()) / s.physicalMemoryBytes() : -1);
        metric(out, "Memoria no-heap (MiB)", samples, s -> s.nonHeapUsedBytes() / (1024d * 1024d));
        metric(out, "Buffer directo (MiB)", samples, s -> s.directBufferBytes() / (1024d * 1024d));
        metric(out, "Hilos", samples, s -> s.threadCount());
        metric(out, "Descriptores abiertos", samples, s -> s.openFileDescriptors());
        metric(out, "Cola de telemetria", samples, s -> s.telemetryQueuedRows());
        metric(out, "Deadlocks", samples, s -> s.deadlockedThreads());
        metric(out, "Jugadores", samples, s -> s.players());
        metric(out, "Chunks cargados", samples, s -> s.loadedChunks());
        metric(out, "Entidades (ultimo escaneo)", samples, s -> s.scannedEntities());
        out.append('\n');
    }

    private void appendInventory(StringBuilder out, InventorySnapshot live,
                                 InventorySnapshot.FileInventory files, int commandBlocks) {
        var s = live.server();
        out.append("## Inventario tecnico\n\n")
                .append("Servidor: **").append(md(s.name())).append("** · ").append(md(s.version()))
                .append(" · Java ").append(md(s.javaVersion())).append(" (").append(md(s.javaVendor())).append(")")
                .append(" · ").append(md(s.os())).append("\n\n")
                .append("### Mundos\n\n| Mundo | Entorno | Dificultad | Chunks | Jugadores | View/simulation | Borde | Spawn cargado |\n")
                .append("|---|---|---|---:|---:|---:|---:|---|\n");
        for (var world : live.worlds()) {
            out.append('|').append(md(world.name())).append('|').append(world.environment()).append('|')
                    .append(world.difficulty()).append('|').append(world.loadedChunks()).append('|').append(world.players())
                    .append('|').append(world.viewDistance()).append('/').append(world.simulationDistance()).append('|')
                    .append(one(world.borderSize())).append('|').append(world.keepSpawnLoaded()).append("|\n");
        }
        out.append("\n### Plugins\n\n| Plugin | Version | Estado | Tasks sync/async | Listeners | Clase principal | Dependencias |\n|---|---|---|---:|---:|---|---|\n");
        for (var p : live.plugins()) {
            List<String> deps = new ArrayList<>(p.depend());
            deps.addAll(p.softDepend().stream().map(d -> d + " (soft)").toList());
            out.append('|').append(md(p.name())).append('|').append(md(p.version())).append('|')
                    .append(p.enabled() ? "enabled" : "disabled").append('|')
                    .append(p.syncTasks()).append('/').append(p.asyncTasks()).append('|')
                    .append(p.registeredListeners()).append('|').append(md(p.mainClass())).append('|')
                    .append(md(String.join(", ", deps))).append("|\n");
        }
        out.append("\n### Datapacks y resource pack\n\n")
                .append("| Datapack | Fuente | Estado | Formato | Compatibilidad |\n|---|---|---|---:|---|\n");
        for (var pack : live.dataPacks()) {
            out.append('|').append(md(pack.key())).append('|').append(pack.source()).append('|')
                    .append(pack.enabled() ? "enabled" : "disabled").append('|')
                    .append(pack.packFormat() < 0 ? "n/a" : pack.packFormat()).append('|')
                    .append(pack.compatibility()).append("|\n");
        }
        var resource = live.resourcePack();
        out.append("\nResource pack del servidor: ").append(resource.url().isBlank() ? "no configurado" : "configurado")
                .append(" · requerido: ").append(resource.required()).append(" · hash: ")
                .append(resource.hash().isBlank() ? "ausente" : "presente").append(".\n\n")
                .append("### Packs encontrados en disco\n\n")
                .append("| Archivo | Entradas | Tamano expandido | Modelos | Texturas | Blockstates | Funciones | Tags | Recetas | Loot tables |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var pack : files.packs()) {
            out.append('|').append(md(pack.path())).append('|').append(pack.entries()).append('|')
                    .append(bytes(pack.uncompressedBytes())).append('|').append(pack.models()).append('|')
                    .append(pack.textures()).append('|').append(pack.blockStates()).append('|').append(pack.functions())
                    .append('|').append(pack.tags()).append('|').append(pack.recipes()).append('|')
                    .append(pack.lootTables()).append("|\n");
        }
        out.append("\nInventario de archivos: **").append(files.files().size()).append("**")
                .append(files.truncated() ? " (truncado por limite de seguridad)" : "")
                .append(". Metadatos y hashes: [files.csv](files.csv). Claves sanitizadas normalizadas: [file-config-entries.csv](file-config-entries.csv).  \n")
                .append("Comandos registrados: **").append(live.commands().size())
                .append("**. Comandos: [commands.csv](commands.csv). Aliases normalizados: [command-aliases.csv](command-aliases.csv).  \n")
                .append("Command blocks observados en el periodo: **").append(commandBlocks)
                .append("**. Listado: [command-blocks.csv](command-blocks.csv).\n\n");
    }

    private void appendLimits(StringBuilder out) {
        out.append("## Alcance y limites de precision\n\n")
                .append("- Los TPS, MSPT y tiempos de tick proceden directamente de Paper. La densidad por chunk es una correlacion, no tiempo CPU atribuido a ese chunk.\n")
                .append("- El FPS, los mods, los shaders y el uso de GPU/RAM del cliente no forman parte del protocolo normal de Minecraft. Se necesita un mod cliente complementario para medirlos.\n")
                .append("- Las particulas son paquetes transitorios y Paper no conserva un registro global de las emitidas. Un perfil JFR puede detectar codigo que las genera, pero no reconstruir las ya enviadas.\n")
                .append("- La atribucion exacta de CPU a un plugin requiere `/blackbox profile <segundos>` durante el problema. El perfil JFR resultante contiene stacks reales para IntelliJ o JDK Mission Control.\n")
                .append("- BlackBox mide CPU, memoria, GC, disco, ping y actividad de sockets durante un perfil. La red fisica del hosting fuera del proceso requiere metricas del panel o del sistema anfitrion.\n");
    }

    private List<DiagnosticFinding> diagnose(List<ServerSample> samples, Map<String, ChunkAggregate> chunks,
                                             Map<String, PlayerAggregate> players, EventAggregate events,
                                             List<PdfReportData.CommandBlockRow> commandBlocks,
                                             List<PdfReportData.HotspotRow> hotspots,
                                             List<PdfReportData.EntityLifecycleRow> lifecycle,
                                             List<PdfReportData.WarningRow> warnings,
                                             List<PdfReportData.RegionRow> regions,
                                             InventorySnapshot.FileInventory files,
                                             List<PdfReportData.CorrelationRow> correlations) {
        List<DiagnosticFinding> result = new ArrayList<>();
        if (samples.isEmpty()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.INFO, "Periodo sin telemetria",
                    "No hay muestras del servidor en el intervalo solicitado.", "BlackBox arranco recientemente o los archivos no estan disponibles.",
                    "Espera al menos un intervalo de muestreo y genera de nuevo el informe."));
        }
        double minTps = samples.stream().mapToDouble(ServerSample::tps1m).min().orElse(20);
        double maxP95 = samples.stream().mapToDouble(ServerSample::tickP95Ms).max().orElse(0);
        double maxTick = samples.stream().mapToDouble(ServerSample::tickMaxMs).max().orElse(0);
        double maxHeap = samples.stream().mapToDouble(s -> 100.0 * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes())).max().orElse(0);
        double maxCpu = samples.stream().mapToDouble(ServerSample::processCpuPercent).max().orElse(0);
        long droppedRows = samples.stream().mapToLong(ServerSample::telemetryDroppedRows).max().orElse(0);
        int deadlocks = samples.stream().mapToInt(ServerSample::deadlockedThreads).max().orElse(0);
        long minDisk = samples.stream().mapToLong(ServerSample::diskUsableBytes).filter(v -> v >= 0).min().orElse(Long.MAX_VALUE);
        double maxFileDescriptors = samples.stream().filter(s -> s.openFileDescriptors() >= 0 && s.maxFileDescriptors() > 0)
                .mapToDouble(s -> 100d * s.openFileDescriptors() / s.maxFileDescriptors()).max().orElse(0);
        double maxPhysicalMemory = samples.stream().filter(s -> s.physicalMemoryBytes() > 0 && s.freePhysicalMemoryBytes() >= 0)
                .mapToDouble(s -> 100d * (s.physicalMemoryBytes() - s.freePhysicalMemoryBytes()) / s.physicalMemoryBytes())
                .max().orElse(0);
        if (minTps < thresholds.criticalTps()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL, "Caida severa de TPS",
                    "TPS minimo " + one(minTps) + "; tick p95 maximo " + one(maxP95) + " ms; tick maximo " + one(maxTick) + " ms.",
                    "El hilo de ticks estuvo bloqueado o sobrecargado.",
                    "Captura un perfil JFR durante el episodio y revisa primero los stacks con mas muestras."));
        } else if (minTps < thresholds.lowTps()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "TPS por debajo del objetivo",
                    "TPS minimo " + one(minTps) + ".", "Carga intermitente o sostenida cercana al presupuesto de 50 ms por tick.",
                    "Compara los instantes de MSPT alto con eventos, jugadores y chunks densos; perfila si se repite."));
        }
        if (maxP95 > thresholds.highMspt()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Ticks lentos frecuentes",
                    "El p95 de tick alcanzo " + one(maxP95) + " ms.", "Al menos el 5% de los ticks de alguna ventana supero el presupuesto configurado.",
                    "Usa el perfil y reduce primero la fuente dominante; los picos aislados suelen ser guardado, generacion, GC o plugins."));
        }
        if (maxHeap > thresholds.heapPercent()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Heap cerca del limite",
                    "Uso maximo " + one(maxHeap) + "%.", "Retencion de objetos, cache grande o heap insuficiente puede forzar pausas de GC.",
                    "Compara GC antes de ampliar memoria; si sigue creciendo, captura un heap dump desde el hosting."));
        }
        if (maxCpu > thresholds.processCpuPercent()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "CPU del proceso saturada",
                    "CPU maxima del proceso " + one(maxCpu) + "%.", "Uno o varios hilos de Java consumieron casi un nucleo o el limite disponible del contenedor.",
                    "Genera un perfil JFR en carga y comprueba tambien el limite de CPU asignado por el hosting."));
        }
        if (droppedRows > 0) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL, "Telemetria descartada",
                    droppedRows + " filas no entraron en la cola de escritura.",
                    "La tasa de eventos supero la capacidad configurada; bloquear el hilo principal habria empeorado el servidor.",
                    "Aumenta storage.queue-capacity, amplía sampling o desactiva temporalmente el registro mas voluminoso tras conservar este informe."));
        }
        if (deadlocks > 0) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL, "Hilos bloqueados mutuamente",
                    "Se detectaron hasta " + deadlocks + " hilos en deadlock.",
                    "Dos o mas hilos esperan recursos que los otros retienen; una tarea puede quedar detenida indefinidamente.",
                    "Conserva el JFR y un thread dump; identifica los locks y el plugin presente en esos stacks antes de reiniciar."));
        }
        if (minDisk < 2L * 1024 * 1024 * 1024) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Poco espacio libre en disco",
                    "El espacio util bajo hasta " + bytes(minDisk) + ".",
                    "Logs, mundos, copias o perfiles pueden agotar el volumen y provocar fallos de guardado.",
                    "Libera espacio y revisa el crecimiento por directorio; conserva margen para regiones y backups."));
        }
        if (maxFileDescriptors >= 85) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Descriptores de archivo cerca del limite",
                    "Uso maximo " + one(maxFileDescriptors) + "% del limite del proceso.",
                    "Archivos o sockets abiertos que no se cierran pueden impedir nuevas conexiones y escrituras.",
                    "Usa el JFR para revisar File/Socket IO y comprueba los limites del sistema operativo."));
        }
        if (maxPhysicalMemory >= 95) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Memoria fisica del host casi llena",
                    "Uso maximo estimado " + one(maxPhysicalMemory) + "%.",
                    "Otros procesos, cache del sistema o un limite de contenedor pueden forzar swap y pausas.",
                    "Compara heap, memoria no-heap y swap; revisa tambien las metricas del panel del hosting."));
        }
        chunks.values().stream().filter(c -> c.entityMax >= thresholds.entitiesPerChunk()
                        || c.blockMax >= thresholds.blockEntitiesPerChunk() || c.hopperMax >= thresholds.hoppersPerChunk()
                        || c.commandMax >= thresholds.commandBlocksPerChunk())
                .sorted(Comparator.comparingDouble(ChunkAggregate::risk).reversed())
                .limit(thresholds.maxFindingsPerCategory()).forEach(c ->
                        result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Chunk denso " + c.key,
                                c.key + " centro " + center(c.key) + " " + tpCommand(c.key),
                                "Maximos: " + c.entityMax + " entidades, " + c.blockMax + " block entities, "
                                        + c.hopperMax + " hoppers y " + c.commandMax + " command blocks.",
                                "La concentracion puede aumentar IA, colisiones, inventarios, redstone y paquetes.",
                                "Puede consumir tick, memoria y ancho de banda de tracking incluso si cada elemento individual parece pequeno.",
                                c.samples + " observaciones dentro de " + observedWindow(samples),
                                "Usa el /tp, identifica el tipo dominante en entity-summary.csv y los block entities del chunk. Reduce solo el componente que coincida con actividad y MSPT.",
                                "Repite la misma carga y confirma que bajan densidad, MSPT p95 y paquetes/entidades rastreadas.")));
        players.values().stream().filter(p -> p.pingMax >= thresholds.highPingMs()).max(Comparator.comparingInt(p -> p.pingMax))
                .ifPresent(p -> result.add(new DiagnosticFinding(DiagnosticFinding.Severity.INFO, "Latencia alta de jugador",
                        p.name + " alcanzo " + p.pingMax + " ms.", "Ruta de red, Wi-Fi, saturacion del enlace o tick lento que retrasa respuestas.",
                        "Compara varios jugadores: si suben juntos revisa hosting/ticks; si es uno solo, revisa su ruta y red local.")));
        if (events.hoppers > thresholds.highHopperEvents() || events.redstone > thresholds.highRedstoneEvents()) {
            List<PdfReportData.HotspotRow> technical = hotspots.stream()
                    .filter(h -> h.event().contains("hopper") || h.event().contains("redstone")
                            || h.event().contains("piston")).limit(8).toList();
            String locations = technical.isEmpty() ? "No hubo detalle geografico suficiente en esta ventana."
                    : technical.stream().map(h -> h.location() + " centro " + center(h.location()) + " "
                    + tpCommand(h.location()) + " (" + h.event() + ": " + h.count() + ")")
                    .collect(java.util.stream.Collectors.joining("; "));
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Actividad tecnica elevada",
                    locations, events.hoppers + " movimientos de hopper y " + events.redstone
                    + " cambios de redstone durante " + observedWindow(samples) + ".",
                    "Los chunks indicados concentran hoppers, redstone o pistones; suelen corresponder a granjas, relojes o clasificadores activos.",
                    "Cada transferencia y actualizacion puede despertar block entities y vecinos. Si coincide con MSPT alto, reduce la capacidad de ticks disponible.",
                    observedWindow(samples),
                    "Usa los /tp anteriores, inspecciona relojes rapidos y lineas de hoppers. Apaga una instalacion cada vez, agrupa items, usa corrientes de agua cuando proceda y evita relojes de 1 tick innecesarios.",
                    "Mide el mismo numero de jugadores durante 10-15 minutos. Deben bajar eventos/min, MSPT p95 y la correlacion de hoppers/redstone con MSPT."));
        }
        if (events.entitySpawns >= thresholds.entityChurnEvents()) {
            List<PdfReportData.EntityLifecycleRow> topEntities = lifecycle.stream()
                    .sorted(Comparator.comparingLong(PdfReportData.EntityLifecycleRow::spawns).reversed()).limit(8).toList();
            String locations = topEntities.stream().map(e -> e.type() + " @ " + e.topLocation() + " "
                    + tpCommand(e.topLocation()) + " (" + e.spawns() + " spawns, fuente "
                    + empty(e.topAttributedSource(), e.topSpawnReason()) + ")")
                    .collect(java.util.stream.Collectors.joining("; "));
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Volumen elevado de spawns",
                    locations, events.entitySpawns + " spawns observados. Principales tipos: "
                    + topEntities.stream().map(e -> e.type() + "=" + e.spawns()).collect(java.util.stream.Collectors.joining(", ")),
                    "Granjas, spawners, comandos, plugins o mecanicas naturales pueden crear entidades mas rapido de lo esperado.",
                    "Aumenta IA, colisiones, eventos, memoria y paquetes; las entidades de vida corta tambien elevan asignaciones y GC.",
                    observedWindow(samples),
                    "Usa los /tp, revisa topAttributedSource y spawn reason en entity-summary.csv. Corrige primero la fuente con mas spawns/min y menor vida media.",
                    "Tras el cambio deben bajar spawns/min y balance sin desplazar el problema a otro chunk; confirma tambien MSPT y GC."));
        }
        long displaySpawns = lifecycle.stream().filter(e -> e.type().contains("DISPLAY") || e.type().equals("INTERACTION"))
                .mapToLong(PdfReportData.EntityLifecycleRow::spawns).sum();
        if (displaySpawns > 0) {
            String displayDetails = lifecycle.stream().filter(e -> e.type().contains("DISPLAY") || e.type().equals("INTERACTION"))
                    .map(e -> e.type() + "=" + e.spawns() + " @ " + e.topLocation() + " " + tpCommand(e.topLocation()))
                    .collect(java.util.stream.Collectors.joining("; "));
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.INFO, "Display entities observadas",
                    displayDetails, displaySpawns + " apariciones de display/interaction entities.",
                    "Suelen proceder de plugins, datapacks o comandos para hologramas, modelos y elementos interactivos.",
                    "No tienen IA de mob, pero muchas instancias o transformaciones frecuentes pueden aumentar tracking, memoria y paquetes.",
                    observedWindow(samples),
                    "Revisa la fuente atribuida, agrupa hologramas, evita actualizaciones cada tick y oculta o retira displays fuera de uso.",
                    "Compara cantidad, tracked players y trafico/JFR antes y despues manteniendo la misma distancia de vision."));
        }
        long riskyCommands = commandBlocks.stream().filter(r -> !r.risk().equals("INFO")).count();
        if (riskyCommands > 0) {
            String locations = commandBlocks.stream().filter(r -> !r.risk().equals("INFO")).limit(5)
                    .map(r -> r.position() + " (" + commandTp(r) + ", " + r.risk() + ")").collect(java.util.stream.Collectors.joining("; "));
            String commands = commandBlocks.stream().filter(r -> !r.risk().equals("INFO")).limit(3)
                    .map(r -> shorten(r.command(), 100)).collect(java.util.stream.Collectors.joining(" | "));
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Command blocks que requieren revision",
                    locations, riskyCommands + " de " + commandBlocks.size() + " bloques presentan patrones costosos. Comandos: " + commands,
                    "Selectores globales, bloques repetitivos, forceload, fill/clone, summons o cadenas execute pueden multiplicar trabajo.",
                    "Pueden consumir tiempo de tick, mantener chunks cargados o crear entidades/paquetes de forma sostenida.",
                    commandBlocks.stream().mapToLong(PdfReportData.CommandBlockRow::executions).sum() + " ejecuciones capturadas; "
                            + commandObservationWindow(commandBlocks),
                    "Ve a cada posicion con el /tp indicado. Desactiva primero el bloque, limita @e con type/distance/limit, reduce la frecuencia y evita forceload o mutaciones masivas salvo necesidad.",
                    "Reactiva un bloque cada vez, reproduce la carga y compara MSPT, ejecuciones/min y spawns antes/despues."));
        }
        long severeLogs = warnings.stream().filter(w -> w.level().equals("SEVERE")
                        || w.message().toLowerCase(Locale.ROOT).matches(".*(error|exception|watchdog|server thread dump).*") )
                .mapToLong(PdfReportData.WarningRow::count).sum();
        List<PdfReportData.WarningRow> ticksBehind = warnings.stream().filter(w -> {
            String message = w.message().toLowerCase(Locale.ROOT);
            return message.contains("ticks behind") || message.contains("can't keep up")
                    || message.contains("server is overloaded");
        }).toList();
        if (!ticksBehind.isEmpty()) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING,
                    "Servidor atrasado respecto al reloj", "Hilo principal / Server thread",
                    ticksBehind.stream().map(w -> w.count() + "x " + shorten(w.message(), 150))
                            .limit(5).collect(java.util.stream.Collectors.joining(" | ")),
                    "Uno o varios ticks necesitaron mas tiempo del disponible y Paper confirmo que el servidor acumulo retraso.",
                    "Las acciones de jugadores, entidades, redstone y plugins se procesan tarde hasta recuperar la cola.",
                    observedWindow(samples),
                    "Cruza la hora exacta con Causa y efecto; abre el JFR automatico de esa ventana y corrige su metodo o foco principal.",
                    "Repite la misma carga: no deben aparecer nuevos avisos y p95 debe permanecer por debajo de "
                            + one(thresholds.highMspt()) + " ms."));
        }
        if (severeLogs > 0) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL, "Errores severos registrados",
                    severeLogs + " entradas SEVERE agrupadas en " + warnings.size() + " firmas de warning/error.",
                    "Excepciones de plugins, fallos de IO o errores del servidor pueden romper tareas y provocar efectos secundarios.",
                    "Revisa la tabla de errores y warnings.csv; corrige primero la primera excepcion causal de cada stack."));
        }
        long malformedRegions = regions.stream().mapToLong(PdfReportData.RegionRow::malformedEntries).sum();
        if (malformedRegions > 0) {
            result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL, "Entradas de region inconsistentes",
                    malformedRegions + " cabeceras .mca apuntan fuera del archivo o usan sectores invalidos.",
                    "El archivo puede estar truncado, parcialmente escrito o danado.",
                    "Deten el servidor antes de reparar; conserva una copia y valida las regiones indicadas en regions.csv."));
        }
        lifecycle.stream().filter(e -> e.spawns() + e.removals() >= thresholds.entityChurnEvents()
                        && e.averageTicksLived() < thresholds.shortEntityLifeTicks())
                .limit(thresholds.maxFindingsPerCategory()).forEach(e -> result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING,
                        "Rotacion elevada de " + e.type(), e.topLocation() + " centro " + center(e.topLocation())
                        + " " + tpCommand(e.topLocation()), e.spawns() + " spawns y " + e.removals()
                        + " retiradas; vida media " + one(e.averageTicksLived() / 20d) + " s.",
                        "Entidades creadas y retiradas rapidamente generan asignaciones, eventos, busquedas y paquetes.",
                        "El churn eleva CPU y GC; si son displays o entidades rastreadas tambien incrementa paquetes a jugadores.",
                        observedWindow(samples) + "; balance observado " + e.observedBalance() + "; fuente principal " + e.topAttributedSource(),
                        "Ve al chunk indicado, revisa la fuente " + e.topSpawnReason() + "/" + e.topAttributedSource()
                                + " y limita frecuencia, cantidad o vida solo en esa fuente.",
                        "Comprueba que bajan spawns/min, balance, tracking y MSPT sin romper la mecanica esperada.")));
        hotspots.stream().filter(h -> h.count() >= thresholds.hotspotEvents())
                .limit(thresholds.maxFindingsPerCategory()).forEach(h ->
                result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Hotspot de " + h.event(),
                        h.location() + " centro " + center(h.location()) + " " + tpCommand(h.location()),
                        h.location() + " (centro " + center(h.location()) + ", " + tpCommand(h.location())
                                + ") acumulo " + h.count() + " eventos.",
                        "La concentracion geografica indica automatizacion o churn sostenido.",
                        "Puede degradar el tick local y global si el evento activa IA, vecinos, inventarios o paquetes.",
                        observedWindow(samples),
                        "Inspecciona ese chunk, identifica la mecanica exacta y captura un JFR mientras este activa antes de modificarla.",
                        "Desactiva una sola fuente y confirma una reduccion proporcional de eventos/min y MSPT.")));
        files.functions().stream().filter(f -> f.riskScore() >= thresholds.functionRiskScore())
                .limit(thresholds.maxFindingsPerCategory()).forEach(f ->
                result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING,
                        "Funcion de datapack a revisar", f.path(), f.path() + ": " + f.notes() + " (riesgo " + f.riskScore() + ").",
                        "Selectores globales, reprogramacion, mutaciones masivas, summons o particulas pueden multiplicar trabajo por tick.",
                        "Su impacto depende de cuantas veces se invoque y de cuantos objetivos seleccione.",
                        observedWindow(samples),
                        "Revisa functions.csv, limita selectores y schedules, y confirma la frecuencia real con JFR antes de modificarla.",
                        "Compara muestras JFR y MSPT con la funcion activa e inactiva bajo la misma carga.")));
        correlations.stream().filter(c -> c.samples() >= thresholds.correlationMinSamples()
                        && c.correlationWithMspt() >= thresholds.correlationWarning())
                .limit(thresholds.maxFindingsPerCategory()).forEach(c ->
                result.add(new DiagnosticFinding(DiagnosticFinding.Severity.INFO,
                        "Correlacion temporal: " + c.signal(), "Pearson r=" + one(c.correlationWithMspt())
                        + " con MSPT sobre " + c.samples() + " intervalos.",
                        "Cuando aumento esta senal tambien aumento el tiempo de tick; la correlacion no demuestra causalidad por si sola.",
                        "Reproduce el episodio, captura JFR y comprueba si la relacion se mantiene al cambiar solo esa carga.")));
        String window = observedWindow(samples);
        return result.stream().map(f -> f.observedTime().equals("Observado dentro de la ventana seleccionada.")
                ? new DiagnosticFinding(f.severity(), f.title(), f.location(), f.evidence(), f.probableCause(),
                f.impact(), window, f.action(), f.verification()) : f).toList();
    }

    private List<PdfReportData.CorrelationRow> correlations(List<ServerSample> samples, List<List<String>> eventRows) {
        List<PdfReportData.CorrelationRow> result = new ArrayList<>();
        addCorrelation(result, "Jugadores online", samples.stream().map(s -> new Pair(s.players(), s.averageMspt())).toList());
        addCorrelation(result, "Chunks cargados", samples.stream().map(s -> new Pair(s.loadedChunks(), s.averageMspt())).toList());
        addCorrelation(result, "Entidades escaneadas", samples.stream().map(s -> new Pair(s.scannedEntities(), s.averageMspt())).toList());
        addCorrelation(result, "CPU del proceso", samples.stream().map(s -> new Pair(s.processCpuPercent(), s.averageMspt())).toList());
        addCorrelation(result, "Heap usado", samples.stream().map(s -> new Pair((double) s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()), s.averageMspt())).toList());
        Map<Long, ServerSample> byTime = new HashMap<>();
        for (ServerSample sample : samples) byTime.put(sample.timestamp().toEpochMilli(), sample);
        String[] names = {"Cargas de chunks", "Descargas de chunks", "Spawns", "Muertes", "Redstone", "Hoppers", "Pistones", "Explosiones"};
        int[] columns = {2, 3, 4, 5, 6, 7, 8, 9};
        for (int n = 0; n < names.length; n++) {
            List<Pair> pairs = new ArrayList<>();
            for (List<String> row : eventRows) {
                if (row.size() <= columns[n]) continue;
                try {
                    ServerSample sample = byTime.get(Long.parseLong(row.get(0)));
                    if (sample != null) pairs.add(new Pair(Double.parseDouble(row.get(columns[n])), sample.averageMspt()));
                } catch (NumberFormatException ignored) {}
            }
            addCorrelation(result, names[n], pairs);
        }
        return result.stream().sorted(Comparator.comparingDouble((PdfReportData.CorrelationRow c) -> Math.abs(c.correlationWithMspt())).reversed()).toList();
    }

    private void addCorrelation(List<PdfReportData.CorrelationRow> result, String signal, List<Pair> raw) {
        List<Pair> pairs = raw.stream().filter(p -> Double.isFinite(p.x) && Double.isFinite(p.y) && p.x >= 0).toList();
        if (pairs.size() < 3) return;
        double meanX = pairs.stream().mapToDouble(Pair::x).average().orElse(0);
        double meanY = pairs.stream().mapToDouble(Pair::y).average().orElse(0);
        double covariance = 0, varianceX = 0, varianceY = 0;
        for (Pair pair : pairs) {
            double dx = pair.x - meanX, dy = pair.y - meanY;
            covariance += dx * dy; varianceX += dx * dx; varianceY += dy * dy;
        }
        double denominator = Math.sqrt(varianceX * varianceY);
        double r = denominator == 0 ? 0 : covariance / denominator;
        String interpretation = Math.abs(r) < thresholds.correlationWeak()
                ? "relacion debil" : r > 0 ? "sube junto al MSPT" : "baja cuando sube el MSPT";
        result.add(new PdfReportData.CorrelationRow(signal, r, pairs.size(), interpretation));
    }

    private List<PdfReportData.HotspotRow> aggregateHotspots(List<List<String>> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 7) continue;
            try { counts.merge(row.get(2) + "|" + row.get(3) + ":" + row.get(4) + ":" + row.get(5), Long.parseLong(row.get(6)), Long::sum); }
            catch (NumberFormatException ignored) {}
        }
        return counts.entrySet().stream().map(e -> {
            int split = e.getKey().indexOf('|');
            return new PdfReportData.HotspotRow(e.getKey().substring(0, split), e.getKey().substring(split + 1), e.getValue());
        }).sorted(Comparator.comparingLong(PdfReportData.HotspotRow::count).reversed())
                .limit(reportOptions.aggregateHotspots()).toList();
    }

    private List<PdfReportData.ChunkLifecycleRow> aggregateChunkLifecycle(List<List<String>> rows) {
        Map<String, ChunkLifecycleAggregate> values = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 8) continue;
            ChunkLifecycleAggregate value = values.computeIfAbsent(row.get(2) + ":" + row.get(3) + ":" + row.get(4),
                    ignored -> new ChunkLifecycleAggregate());
            if (row.get(1).equals("load")) {
                value.loads++;
                if (Boolean.parseBoolean(row.get(5))) value.newChunks++;
            }
            if (row.get(1).equals("unload")) {
                value.unloads++;
                try {
                    long duration = Long.parseLong(row.get(7));
                    if (duration >= 0) {
                        value.durationSum += duration;
                        value.durationSamples++;
                        value.maxDuration = Math.max(value.maxDuration, duration);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return values.entrySet().stream().map(e -> new PdfReportData.ChunkLifecycleRow(e.getKey(), e.getValue().loads,
                        e.getValue().unloads, e.getValue().newChunks,
                        e.getValue().durationSamples == 0 ? 0 : (double) e.getValue().durationSum / e.getValue().durationSamples,
                        e.getValue().maxDuration))
                .sorted(Comparator.comparingLong((PdfReportData.ChunkLifecycleRow c) -> c.loads() + c.unloads()).reversed())
                .toList();
    }

    private List<PdfReportData.EntityLifecycleRow> aggregateLifecycle(List<List<String>> rows,
                                                                       List<List<String>> commandRows) {
        List<CommandExecution> commandExecutions = commandRows.stream().map(ReportService::commandExecution)
                .filter(java.util.Objects::nonNull).sorted(Comparator.comparing(CommandExecution::timestamp)).toList();
        Map<String, EntityLifecycleAggregate> values = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 13) continue;
            EntityLifecycleAggregate value = values.computeIfAbsent(row.get(3), ignored -> new EntityLifecycleAggregate());
            String event = row.get(1);
            if (!event.equals("spawn") && !event.equals("remove")) continue;
            if (row.size() > 11) value.locations.merge(row.get(6) + ":" + row.get(10) + ":" + row.get(11), 1L, Long::sum);
            if (event.equals("spawn")) {
                value.spawns++; value.spawnReasons.merge(row.get(4), 1L, Long::sum);
                if (bool(row, 14)) value.ticking++;
                if (bool(row, 15)) value.persistent++;
                if (bool(row, 16)) value.fromSpawner++;
                if (row.size() > 17 && !row.get(17).isBlank()) value.named++;
                value.passengers += number(row, 18);
                value.trackedPlayers += number(row, 19);
                String attributed = row.size() > 21 ? row.get(21) : "";
                if (row.get(4).equals("COMMAND")) {
                    try {
                        String command = nearbyCommand(commandExecutions,
                                Instant.ofEpochMilli(Long.parseLong(row.get(0))), row.get(3));
                        if (!command.isBlank()) attributed = command;
                    } catch (RuntimeException ignored) {}
                }
                if (!attributed.isBlank()) value.attributedSources.merge(attributed, 1L, Long::sum);
            }
            if (event.equals("remove")) {
                value.removals++; value.removeCauses.merge(row.get(5), 1L, Long::sum);
                try {
                    int ticks = Integer.parseInt(row.get(12)); value.ticks += ticks; value.maxTicks = Math.max(value.maxTicks, ticks);
                    long observedMs = row.size() > 13 ? Long.parseLong(row.get(13)) : -1;
                    if (observedMs >= 0) {
                        value.observedLifetimeMs += observedMs; value.observedLifetimeSamples++;
                        value.maxObservedLifetimeMs = Math.max(value.maxObservedLifetimeMs, observedMs);
                    }
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return values.entrySet().stream().map(e -> new PdfReportData.EntityLifecycleRow(e.getKey(), e.getValue().spawns,
                        e.getValue().removals, e.getValue().spawns - e.getValue().removals,
                        e.getValue().removals == 0 ? 0 : (double) e.getValue().ticks / e.getValue().removals,
                        e.getValue().maxTicks,
                        e.getValue().observedLifetimeSamples == 0 ? 0 : (double) e.getValue().observedLifetimeMs / e.getValue().observedLifetimeSamples,
                        e.getValue().maxObservedLifetimeMs, top(e.getValue().spawnReasons), top(e.getValue().removeCauses),
                        top(e.getValue().locations), topCount(e.getValue().locations), e.getValue().ticking,
                        e.getValue().persistent, e.getValue().fromSpawner, e.getValue().named,
                        e.getValue().passengers, e.getValue().trackedPlayers, top(e.getValue().attributedSources)))
                .sorted(Comparator.comparingLong((PdfReportData.EntityLifecycleRow e) -> e.spawns() + e.removals()).reversed())
                .toList();
    }

    private static CommandExecution commandExecution(List<String> row) {
        if (row.size() < 7) return null;
        try { return new CommandExecution(Instant.ofEpochMilli(Long.parseLong(row.get(0))),
                row.get(1) + ":" + row.get(2) + ":" + row.get(3) + ":" + row.get(4), row.get(6)); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String nearbyCommand(List<CommandExecution> commands, Instant spawn, String entityType) {
        String type = entityType.toLowerCase(Locale.ROOT);
        return commands.stream().filter(command -> Math.abs(Duration.between(command.timestamp(), spawn).toMillis()) <= 2_000)
                .sorted(Comparator.comparingLong(command -> Math.abs(Duration.between(command.timestamp(), spawn).toMillis())))
                .filter(command -> command.command().toLowerCase(Locale.ROOT).contains(type)
                        || command.command().toLowerCase(Locale.ROOT).contains("summon"))
                .findFirst().map(command -> "Command block " + command.position() + " :: " + shorten(command.command(), 120))
                .orElse("");
    }

    private List<PdfReportData.WarningRow> aggregateWarnings(List<List<String>> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 4) continue;
            String message = shorten(row.get(3).replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}", "<uuid>"), 220);
            counts.merge(row.get(1) + "|" + row.get(2) + "|" + message, 1L, Long::sum);
        }
        return counts.entrySet().stream().map(e -> {
            String[] parts = e.getKey().split("\\|", 3);
            return new PdfReportData.WarningRow(parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : "", e.getValue());
        }).sorted(Comparator.comparingLong(PdfReportData.WarningRow::count).reversed())
                .limit(reportOptions.aggregateWarnings()).toList();
    }

    private List<PdfReportData.WarningRow> aggregateLogFindings(List<String> lines) {
        Map<String, Long> counts = new HashMap<>();
        for (String line : lines) counts.merge(shorten(line, 220), 1L, Long::sum);
        return counts.entrySet().stream().map(e -> new PdfReportData.WarningRow("SERVER_LOG", "logs/latest.log",
                        e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(PdfReportData.WarningRow::count).reversed())
                .limit(reportOptions.aggregateWarnings()).toList();
    }

    private List<String> readServerLogFindings(Instant cutoff) {
        Path log = serverRoot.resolve("logs").resolve("latest.log");
        try {
            if (!Files.isRegularFile(log) || Files.getLastModifiedTime(log).toInstant().isBefore(cutoff)) return List.of();
            long size = Files.size(log);
            long start = Math.max(0, size - reportOptions.latestLogReadBytes());
            List<String> result = new ArrayList<>();
            try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(log.toFile(), "r")) {
                input.seek(start);
                if (start > 0) input.readLine();
                String raw;
                while ((raw = input.readLine()) != null && result.size() < reportOptions.latestLogFindings()) {
                    String line = new String(raw.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.contains(" warn]") || lower.contains(" error]") || lower.contains("exception")
                            || lower.contains("can't keep up") || lower.contains("watchdog") || lower.contains("server thread dump")) {
                        result.add(shorten(line, reportOptions.latestLogLineMaxChars()));
                    }
                }
            }
            return result;
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private List<StartupDiagnosticEntry> readStartupDiagnostics(Instant cutoff) {
        if (!Files.isRegularFile(startupDiagnosticsFile)) return List.of();
        List<StartupDiagnosticEntry> result = new ArrayList<>();
        StartupDiagnosticBuilder current = null;
        try {
            for (String line : Files.readAllLines(startupDiagnosticsFile, StandardCharsets.UTF_8)) {
                StartupHeader header = startupHeader(line);
                if (header != null) {
                    if (current != null) addStartupDiagnostic(result, current, cutoff);
                    current = new StartupDiagnosticBuilder(header.timestamp(), header.level());
                    continue;
                }
                if (current == null) continue;
                if (line.startsWith("Tipo: ")) current.category = line.substring(6);
                else if (line.startsWith("Etapa: ")) current.stage = line.substring(7);
                else if (line.startsWith("Origen: ")) current.origin = line.substring(8);
                else if (line.startsWith("Logger: ")) current.logger = line.substring(8);
                else if (line.startsWith("Error: ")) current.error = line.substring(7);
                else if (line.startsWith("Causa probable: ")) current.cause = line.substring(16);
                else if (line.startsWith("Solución sugerida: ")) current.solution = line.substring(19);
            }
            if (current != null) addStartupDiagnostic(result, current, cutoff);
        } catch (IOException ignored) {
            return List.of();
        }
        return result;
    }

    private static StartupHeader startupHeader(String line) {
        int separator = line.indexOf(" [");
        if (separator <= 0 || !line.endsWith("]")) return null;
        try {
            Instant timestamp = Instant.parse(line.substring(0, separator));
            String level = line.substring(separator + 2, line.length() - 1);
            return new StartupHeader(timestamp, level);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void addStartupDiagnostic(List<StartupDiagnosticEntry> result,
                                             StartupDiagnosticBuilder value, Instant cutoff) {
        if (!value.timestamp.isBefore(cutoff) && !value.error.isBlank()) result.add(value.build());
    }

    private static DiagnosticFinding startupFinding(StartupDiagnosticEntry entry) {
        DiagnosticFinding.Severity severity = entry.level.equalsIgnoreCase("SEVERE")
                ? DiagnosticFinding.Severity.CRITICAL : DiagnosticFinding.Severity.WARNING;
        String observed = "Detectado durante el inicio: " + DISPLAY_TIME.format(entry.timestamp)
                + (entry.stage.isBlank() ? "" : " (" + entry.stage + ")");
        return new DiagnosticFinding(severity, "Inicio: " + value(entry.category, "ERROR SIN CLASIFICAR"),
                value(entry.origin, entry.logger), entry.error,
                value(entry.cause, "El registro original no permite determinar una causa única."),
                "Puede impedir la carga de un mundo, plugin o servicio, o dejarlo parcialmente disponible.",
                observed, value(entry.solution, "Revisa el stack y la configuración del componente indicado."),
                "Reinicia tras aplicar el cambio y confirma que la incidencia ya no aparece en el siguiente informe.");
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<PdfReportData.RegionRow> aggregateRegions(List<List<String>> rows) {
        Map<String, RegionAggregate> values = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 10) continue;
            try {
                RegionAggregate value = values.computeIfAbsent(row.get(1), ignored -> new RegionAggregate());
                value.files++; value.chunks += Long.parseLong(row.get(5)); value.bytes += Long.parseLong(row.get(7));
                value.malformed += Long.parseLong(row.get(9));
                if (row.size() > 10) value.malformed += Long.parseLong(row.get(10));
            } catch (NumberFormatException ignored) {}
        }
        return values.entrySet().stream().map(e -> new PdfReportData.RegionRow(e.getKey(), e.getValue().files,
                e.getValue().chunks, e.getValue().bytes, e.getValue().malformed)).toList();
    }

    private PdfReportData.SessionSummary sessionSummary(List<List<String>> rows, Instant cutoff, Instant generated) {
        List<SessionEvent> events = rows.stream().map(ReportService::sessionEvent)
                .filter(java.util.Objects::nonNull).sorted(Comparator.comparing(SessionEvent::timestamp)).toList();
        int starts = 0, cleanStops = 0, uncleanStarts = 0;
        long downtimeMs = 0;
        Instant lastStop = null;
        Set<String> open = new HashSet<>();
        Map<String, String> previousPlugins = null;
        List<PdfReportData.EnvironmentChange> changes = new ArrayList<>();
        for (SessionEvent event : events) {
            boolean inWindow = !event.timestamp().isBefore(cutoff) && !event.timestamp().isAfter(generated);
            if (event.event().equals("start")) {
                if (inWindow) {
                    starts++;
                    if (!open.isEmpty()) uncleanStarts++;
                    if (lastStop != null && !lastStop.isAfter(event.timestamp()))
                        downtimeMs += Duration.between(lastStop, event.timestamp()).toMillis();
                }
                open.clear();
                open.add(event.sessionId());
                Map<String, String> current = pluginMap(event.plugins());
                if (previousPlugins != null && inWindow && !previousPlugins.equals(current)) {
                    Map<String, String> previous = previousPlugins;
                    String added = names(current.keySet().stream().filter(p -> !previous.containsKey(p)).toList());
                    String removed = names(previous.keySet().stream().filter(p -> !current.containsKey(p)).toList());
                    String updated = names(current.keySet().stream().filter(p -> previous.containsKey(p)
                            && !java.util.Objects.equals(previous.get(p), current.get(p)))
                            .map(p -> p + " " + previous.get(p) + " -> " + current.get(p)).toList());
                    changes.add(new PdfReportData.EnvironmentChange(event.timestamp(), added, removed, updated));
                }
                previousPlugins = current;
            } else if (event.event().equals("stop")) {
                if (inWindow) cleanStops++;
                lastStop = event.timestamp();
                open.remove(event.sessionId());
            }
        }
        return new PdfReportData.SessionSummary(starts, cleanStops, uncleanStarts, downtimeMs, List.copyOf(changes));
    }

    private PdfReportData.Comparison comparison(List<ServerSample> before, List<ServerSample> after,
                                                Instant beforeStart, Instant afterStart, Instant generated,
                                                PdfReportData.SessionSummary sessions) {
        String beforeLabel = DISPLAY_TIME.format(beforeStart) + " - " + DISPLAY_TIME.format(afterStart);
        String afterLabel = DISPLAY_TIME.format(afterStart) + " - " + DISPLAY_TIME.format(generated);
        List<String> context = new ArrayList<>();
        for (PdfReportData.EnvironmentChange change : sessions.changes()) {
            if (!change.added().isBlank()) context.add("Anadidos: " + change.added());
            if (!change.removed().isBlank()) context.add("Retirados: " + change.removed());
            if (!change.updated().isBlank()) context.add("Actualizados: " + change.updated());
        }
        if (before.size() < 2 || after.size() < 2) {
            return new PdfReportData.Comparison(beforeLabel, afterLabel, List.of(),
                    "No hay suficientes muestras en ambos periodos para calcular una comparativa fiable.", context);
        }
        List<ComparisonSpec> specs = List.of(
                new ComparisonSpec("TPS medio", ServerSample::tps1m, "", 1),
                new ComparisonSpec("MSPT medio", ServerSample::averageMspt, " ms", -1),
                new ComparisonSpec("Tick p95 medio", ServerSample::tickP95Ms, " ms", -1),
                new ComparisonSpec("CPU del proceso", ServerSample::processCpuPercent, "%", -1),
                new ComparisonSpec("Heap usado", s -> 100d * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()), "%", -1),
                new ComparisonSpec("Jugadores", s -> s.players(), "", 0),
                new ComparisonSpec("Chunks cargados", s -> s.loadedChunks(), "", 0),
                new ComparisonSpec("Entidades escaneadas", s -> s.scannedEntities(), "", 0));
        List<PdfReportData.ComparisonMetric> metrics = new ArrayList<>();
        for (ComparisonSpec spec : specs) {
            double oldValue = average(before, spec.value());
            double newValue = average(after, spec.value());
            if (!Double.isFinite(oldValue) || !Double.isFinite(newValue)) continue;
            double delta = Math.abs(oldValue) < .0001 ? 0 : 100d * (newValue - oldValue) / Math.abs(oldValue);
            String reading;
            if (spec.direction() == 0) reading = Math.abs(delta) < 5 ? "Contexto estable" : "Cambio de contexto";
            else if (Math.abs(delta) < 5) reading = "Estable";
            else reading = delta * spec.direction() > 0 ? "Mejora" : "Empeora";
            metrics.add(new PdfReportData.ComparisonMetric(spec.name(), one(oldValue) + spec.suffix(),
                    one(newValue) + spec.suffix(), (delta >= 0 ? "+" : "") + one(delta) + "%", reading));
        }
        double tpsDelta = average(after, ServerSample::tps1m) - average(before, ServerSample::tps1m);
        double msptBefore = average(before, ServerSample::averageMspt);
        double msptAfter = average(after, ServerSample::averageMspt);
        double msptDelta = msptAfter - msptBefore;
        String assessment;
        if (tpsDelta > .3 && msptDelta < -2) assessment = "El periodo actual mejora: sube el TPS y baja el MSPT medio.";
        else if (tpsDelta < -.3 && msptDelta > 2) assessment = "El periodo actual empeora: baja el TPS y sube el MSPT medio.";
        else if (msptDelta < -2) assessment = "El coste de tick baja en el periodo actual.";
        else if (msptDelta > 2) assessment = "El coste de tick sube en el periodo actual.";
        else assessment = "El rendimiento global permanece estable entre ambos periodos.";
        if (!context.isEmpty()) assessment += " Hay cambios de software cercanos; son contexto temporal, no prueba causal.";
        return new PdfReportData.Comparison(beforeLabel, afterLabel, List.copyOf(metrics), assessment, List.copyOf(context));
    }

    private List<PdfReportData.IncidentRow> incidents(List<ServerSample> samples, List<List<String>> hotspotRows,
                                                       List<List<String>> warningRows, List<List<String>> sessionRows,
                                                       List<List<String>> profileRows, Instant cutoff) {
        List<PdfReportData.IncidentRow> result = new ArrayList<>();
        Set<String> buckets = new HashSet<>();
        for (ServerSample sample : samples) {
            List<String> details = new ArrayList<>();
            DiagnosticFinding.Severity severity = null;
            if (sample.tps1m() <= thresholds.criticalTps()) {
                severity = DiagnosticFinding.Severity.CRITICAL; details.add("TPS " + one(sample.tps1m()));
            } else if (sample.tps1m() <= thresholds.lowTps()) {
                severity = DiagnosticFinding.Severity.WARNING; details.add("TPS " + one(sample.tps1m()));
            }
            if (sample.tickP95Ms() >= thresholds.highMspt()) {
                severity = sample.tickP95Ms() >= thresholds.highMspt() * 2
                        ? DiagnosticFinding.Severity.CRITICAL : moreSevere(severity, DiagnosticFinding.Severity.WARNING);
                details.add("tick p95 " + one(sample.tickP95Ms()) + " ms");
            }
            double heap = 100d * sample.heapUsedBytes() / Math.max(1, sample.heapMaxBytes());
            if (heap >= thresholds.heapPercent()) {
                severity = moreSevere(severity, DiagnosticFinding.Severity.WARNING);
                details.add("heap " + one(heap) + "%");
            }
            if (sample.deadlockedThreads() > 0) {
                severity = DiagnosticFinding.Severity.CRITICAL; details.add(sample.deadlockedThreads() + " hilos bloqueados");
            }
            if (sample.telemetryDroppedRows() > 0) {
                severity = DiagnosticFinding.Severity.CRITICAL; details.add(sample.telemetryDroppedRows() + " filas descartadas");
            }
            String key = sample.timestamp().toEpochMilli() / 300_000L + "|server";
            if (severity != null && buckets.add(key)) result.add(new PdfReportData.IncidentRow(sample.timestamp(), severity,
                    "Rendimiento/JVM", "Servidor", String.join("; ", details)));
        }
        for (PdfReportData.HotspotRow hotspot : aggregateHotspots(hotspotRows)) {
            long threshold = hotspot.event().contains("hopper") ? thresholds.highHopperEvents()
                    : hotspot.event().contains("redstone") ? thresholds.highRedstoneEvents() : thresholds.hotspotEvents();
            DiagnosticFinding.Severity severity = hotspot.count() >= threshold * 2
                    ? DiagnosticFinding.Severity.CRITICAL : hotspot.count() >= threshold
                    ? DiagnosticFinding.Severity.WARNING : DiagnosticFinding.Severity.INFO;
            result.add(new PdfReportData.IncidentRow(samples.isEmpty() ? Instant.now() : samples.getLast().timestamp(),
                    severity, hotspot.event(), hotspot.location(), hotspot.count() + " eventos acumulados"));
        }
        Set<String> warningSignatures = new HashSet<>();
        for (List<String> row : warningRows) {
            if (row.size() < 4) continue;
            Instant time = timestamp(row).orElse(null);
            if (time == null) continue;
            String level = row.get(1).toUpperCase(Locale.ROOT);
            DiagnosticFinding.Severity severity = level.equals("SEVERE")
                    ? DiagnosticFinding.Severity.CRITICAL : DiagnosticFinding.Severity.WARNING;
            String signature = level + "|" + row.get(2) + "|" + shorten(row.get(3), 120);
            if (warningSignatures.add(signature)) result.add(new PdfReportData.IncidentRow(time, severity,
                    level, row.get(2), shorten(row.get(3), 180)));
        }
        for (List<String> row : sessionRows) {
            Instant time = timestamp(row).orElse(null);
            if (time == null || time.isBefore(cutoff) || row.size() < 2) continue;
            result.add(new PdfReportData.IncidentRow(time, DiagnosticFinding.Severity.INFO,
                    row.get(1).equals("start") ? "Arranque" : "Apagado", "Servidor",
                    row.size() > 3 ? shorten(row.get(3), 140) : "Cambio de sesion"));
        }
        for (List<String> row : profileRows) {
            Instant time = timestamp(row).orElse(null);
            if (time == null || row.size() < 5 || !row.get(1).equals("started")) continue;
            DiagnosticFinding.Severity severity = row.get(3).equals("automatic")
                    ? DiagnosticFinding.Severity.WARNING : DiagnosticFinding.Severity.INFO;
            result.add(new PdfReportData.IncidentRow(time, severity, "Perfil JFR", "Servidor", shorten(row.get(4), 180)));
        }
        result.sort(Comparator.comparing(PdfReportData.IncidentRow::timestamp));
        return List.copyOf(result);
    }

    private List<PdfReportData.PluginAnalysisRow> pluginAnalysis(InventorySnapshot live,
                                                                  List<List<String>> profileRows) {
        Map<String, Long> samples = new HashMap<>(), totals = new HashMap<>();
        for (List<String> row : profileRows) {
            if (row.size() < 6) continue;
            try {
                samples.merge(row.get(3), Long.parseLong(row.get(4)), Long::sum);
                totals.merge(row.get(3), Long.parseLong(row.get(5)), Long::sum);
            } catch (NumberFormatException ignored) {}
        }
        return live.plugins().stream().map(plugin -> {
            long attributed = samples.getOrDefault(plugin.name(), 0L);
            long total = totals.getOrDefault(plugin.name(), 0L);
            double share = total == 0 ? 0 : 100d * attributed / total;
            String assessment;
            if (attributed > 0 && share >= 25) assessment = "Presencia alta en stacks JFR (" + one(share) + "%); revisar metodos concretos.";
            else if (attributed > 0) assessment = "Aparece en stacks JFR (" + one(share) + "%); evidencia de ejecucion, no causalidad.";
            else if (plugin.syncTasks() >= 5 || plugin.registeredListeners() >= 100)
                assessment = "Huella operativa alta; conviene perfilar durante carga.";
            else if (plugin.syncTasks() > 0 || plugin.registeredListeners() > 0)
                assessment = "Actividad registrada; sin coste atribuible en los perfiles disponibles.";
            else assessment = "Sin actividad atribuible con la evidencia disponible.";
            return new PdfReportData.PluginAnalysisRow(plugin.name(), plugin.version(), plugin.syncTasks(),
                    plugin.asyncTasks(), plugin.registeredListeners(), attributed, total, assessment);
        }).sorted(Comparator.comparingLong(PdfReportData.PluginAnalysisRow::profileSamples).reversed()
                .thenComparing(PdfReportData.PluginAnalysisRow::plugin, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static List<PdfReportData.ProfileIoRow> profileIo(List<List<String>> rows) {
        List<PdfReportData.ProfileIoRow> result = new ArrayList<>();
        for (List<String> row : rows) {
            if (row.size() < 10) continue;
            try {
                result.add(new PdfReportData.ProfileIoRow(Instant.ofEpochMilli(Long.parseLong(row.get(1))),
                        Instant.ofEpochMilli(Long.parseLong(row.get(2))), row.get(3), row.get(4),
                        Long.parseLong(row.get(5)), Long.parseLong(row.get(6)), Long.parseLong(row.get(7)),
                        Long.parseLong(row.get(8)), Long.parseLong(row.get(9)), number(row, 10),
                        number(row, 11), number(row, 12), number(row, 13)));
            } catch (RuntimeException ignored) {}
        }
        result.sort(Comparator.comparing(PdfReportData.ProfileIoRow::startedAt));
        return List.copyOf(result);
    }

    private static List<DiagnosticFinding> pluginDependencyFindings(InventorySnapshot live) {
        Map<String, InventorySnapshot.PluginInfo> installed = new HashMap<>();
        for (var plugin : live.plugins()) installed.put(plugin.name().toLowerCase(Locale.ROOT), plugin);
        List<DiagnosticFinding> result = new ArrayList<>();
        for (var plugin : live.plugins()) {
            List<String> missing = plugin.depend().stream()
                    .filter(dependency -> !installed.containsKey(dependency.toLowerCase(Locale.ROOT))).toList();
            List<String> disabled = plugin.depend().stream().filter(dependency -> {
                var target = installed.get(dependency.toLowerCase(Locale.ROOT));
                return target != null && !target.enabled();
            }).toList();
            if (!missing.isEmpty() || !disabled.isEmpty()) {
                String evidence = (!missing.isEmpty() ? "Dependencias ausentes: " + String.join(", ", missing) + ". " : "")
                        + (!disabled.isEmpty() ? "Dependencias desactivadas: " + String.join(", ", disabled) + "." : "");
                result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL,
                        "Dependencias incompatibles de " + plugin.name(), "Plugin " + plugin.name(), evidence,
                        "El plugin declara dependencias obligatorias que no estan disponibles y puede cargar parcialmente o fallar.",
                        "Comandos, listeners o datos de ese plugin pueden quedar sin registrar.",
                        "Inventario capturado " + DISPLAY_TIME.format(live.capturedAt()),
                        "Instala una version compatible de cada dependencia o retira el plugin dependiente; no uses copias duplicadas.",
                        "Reinicia y confirma que todos aparecen activos y que desaparecen NoClassDefFoundError/UnknownDependency del log."));
            } else if (!plugin.enabled()) {
                result.add(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING,
                        "Plugin desactivado: " + plugin.name(), "Plugin " + plugin.name(),
                        "Paper lo inventario, pero su estado final es desactivado.",
                        "Puede haberse desactivado por configuracion, dependencia, version o una excepcion durante el arranque.",
                        "Sus funciones no estan disponibles y otros plugins que lo integran pueden degradarse.",
                        "Inventario capturado " + DISPLAY_TIME.format(live.capturedAt()),
                        "Busca la primera excepcion de " + plugin.name() + " en startup-diagnostics.csv y corrige esa causa antes de reactivarlo.",
                        "Reinicia y confirma estado Activo sin errores nuevos."));
            }
        }
        return List.copyOf(result);
    }

    private List<DiagnosticFinding> crashReportFindings(Instant cutoff) {
        List<Path> roots = List.of(serverRoot.resolve("crash-reports"), clientCrashReportsRoot);
        List<DiagnosticFinding> result = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.list(root)) {
                reports.addAll(stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                        .filter(path -> {
                            try { return Files.getLastModifiedTime(path).toInstant().compareTo(cutoff) >= 0; }
                            catch (IOException ignored) { return false; }
                        }).toList());
            } catch (IOException ignored) { }
        }
        reports.sort(Comparator.comparing((Path path) -> {
            try { return Files.getLastModifiedTime(path).toInstant(); }
            catch (IOException ignored) { return Instant.EPOCH; }
        }).reversed());
        if (reports.size() > 20) reports = reports.subList(0, 20);
        try {
            for (Path report : reports) {
                String text = readTail(report, Math.min(reportOptions.latestLogReadBytes(), 4L * 1024 * 1024));
                String description = match(text, "(?m)^Description:\\s*(.+)$", "Crash del servidor");
                String causedBy = lastMatch(text, "(?m)^(?:Caused by:\\s*)?([\\w.$]+(?:Exception|Error)(?::[^\\r\\n]*)?)$");
                String frame = firstPluginFrame(text);
                String combined = (description + " " + causedBy).toLowerCase(Locale.ROOT);
                String category;
                String solution;
                if (combined.contains("outofmemory")) {
                    category = "Memoria JVM agotada";
                    solution = "Revisa heap, GC y entidades/caches antes del crash; captura heap dump si el crecimiento no se recupera.";
                } else if (combined.contains("watchdog") || combined.contains("single server tick")) {
                    category = "Watchdog: tick bloqueado";
                    solution = "Abre el stack completo y corrige el primer metodo de plugin del hilo Server thread; reproduce con JFR automatico.";
                } else if (combined.contains("noclassdeffound") || combined.contains("nosuchmethod")
                        || combined.contains("unsupportedclassversion")) {
                    category = "Incompatibilidad de version o dependencia";
                    solution = "Alinea Java, Paper, el plugin indicado y sus dependencias; elimina JAR duplicados o antiguos.";
                } else {
                    category = "Excepcion no controlada";
                    solution = "Corrige la ultima causa 'Caused by' y la primera linea propia del plugin; actualiza ese componente y reproduce.";
                }
                Instant observed;
                try { observed = Files.getLastModifiedTime(report).toInstant(); }
                catch (IOException ignored) { observed = Instant.EPOCH; }
                String evidence = description + (causedBy.isBlank() ? "" : "; " + causedBy)
                        + (frame.isBlank() ? "" : "; primer frame atribuible: " + frame);
                boolean clientReport = report.toAbsolutePath().normalize().startsWith(clientCrashReportsRoot.toAbsolutePath().normalize());
                result.add(new DiagnosticFinding(DiagnosticFinding.Severity.CRITICAL,
                        (clientReport ? "Crash de cliente analizado: " : "Crash del servidor analizado: ") + category,
                        report.toAbsolutePath().toString(), evidence,
                        frame.isBlank() ? "El informe no contiene un frame de plugin atribuible; revisa el stack completo."
                                : "La cadena causal alcanza " + frame + ". Es el primer punto atribuible, no necesariamente el origen ultimo.",
                        "El proceso o el hilo principal dejo de prestar servicio.", DISPLAY_TIME.format(observed), solution,
                        "Conserva el crash report, aplica un cambio y verifica un arranque/carga equivalente sin nuevo archivo."));
            }
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private static String match(String text, String expression, String fallback) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    private static String lastMatch(String text, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        String result = "";
        while (matcher.find()) result = matcher.group(1).strip();
        return result;
    }

    private static String firstPluginFrame(String text) {
        Matcher matcher = Pattern.compile("(?m)^\\s*at\\s+([^\\s(]+\\([^\\r\\n]+\\))").matcher(text);
        while (matcher.find()) {
            String frame = matcher.group(1);
            if (!frame.startsWith("java.") && !frame.startsWith("jdk.") && !frame.startsWith("sun.")
                    && !frame.startsWith("net.minecraft.") && !frame.startsWith("org.bukkit.")
                    && !frame.startsWith("io.papermc.") && !frame.startsWith("com.mojang.")) return frame;
        }
        return "";
    }

    private static String readTail(Path path, long maxBytes) throws IOException {
        long size = Files.size(path);
        long start = Math.max(0, size - Math.max(1, maxBytes));
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(start);
            var buffer = java.nio.ByteBuffer.allocate(Math.toIntExact(size - start));
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            if (start > 0) {
                int line = text.indexOf('\n');
                return line < 0 ? "" : text.substring(line + 1);
            }
            return text;
        }
    }

    private static SessionEvent sessionEvent(List<String> row) {
        if (row.size() < 6) return null;
        try { return new SessionEvent(Instant.ofEpochMilli(Long.parseLong(row.get(0))), row.get(1), row.get(2), row.get(5)); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Map<String, String> pluginMap(String encoded) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : encoded.split(";")) {
            if (item.isBlank()) continue;
            int split = item.lastIndexOf('@');
            result.put(split < 0 ? item : item.substring(0, split), split < 0 ? "" : item.substring(split + 1));
        }
        return result;
    }

    private static String names(List<String> values) { return values.isEmpty() ? "" : String.join(", ", values); }
    private static double average(List<ServerSample> samples, ToDoubleFunction<ServerSample> value) {
        return samples.stream().mapToDouble(value).filter(v -> Double.isFinite(v) && v >= 0).average().orElse(Double.NaN);
    }
    private static DiagnosticFinding.Severity moreSevere(DiagnosticFinding.Severity current,
                                                           DiagnosticFinding.Severity candidate) {
        return current == null || candidate.ordinal() < current.ordinal() ? candidate : current;
    }
    private static java.util.Optional<Instant> timestamp(List<String> row) {
        try { return java.util.Optional.of(Instant.ofEpochMilli(Long.parseLong(row.getFirst()))); }
        catch (RuntimeException ignored) { return java.util.Optional.empty(); }
    }
    private static String center(String location) {
        ChunkLocation chunk = ChunkLocation.parse(location);
        return chunk == null ? "" : chunk.center();
    }
    private static String centerX(String location) {
        ChunkLocation chunk = ChunkLocation.parse(location);
        return chunk == null ? "" : Long.toString(chunk.centerX());
    }
    private static String centerZ(String location) {
        ChunkLocation chunk = ChunkLocation.parse(location);
        return chunk == null ? "" : Long.toString(chunk.centerZ());
    }
    private static String tpCommand(String location) {
        ChunkLocation chunk = ChunkLocation.parse(location);
        return chunk == null ? "" : chunk.tpCommand();
    }
    private static String commandTp(PdfReportData.CommandBlockRow row) {
        return "/tp @s " + row.x() + " " + (row.y() + 1) + " " + row.z();
    }
    private static String observed(Instant first, Instant last) {
        if (first == null || last == null) return "sin intervalo suficiente";
        return DISPLAY_TIME.format(first) + " a " + DISPLAY_TIME.format(last) + " ("
                + humanDurationMillis(Math.max(0, Duration.between(first, last).toMillis())) + ")";
    }
    private static String observedWindow(List<ServerSample> samples) {
        if (samples.isEmpty()) return "sin muestras temporales";
        List<ServerSample> ordered = samples.stream().sorted(Comparator.comparing(ServerSample::timestamp)).toList();
        return observed(ordered.getFirst().timestamp(), ordered.getLast().timestamp());
    }
    static String commandObservationWindow(List<PdfReportData.CommandBlockRow> rows) {
        Instant first = rows.stream().map(PdfReportData.CommandBlockRow::firstSeen)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant last = rows.stream().map(PdfReportData.CommandBlockRow::lastSeen)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (first == null) {
            first = rows.stream().map(PdfReportData.CommandBlockRow::firstExecution)
                    .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        }
        if (last == null) {
            last = rows.stream().map(PdfReportData.CommandBlockRow::lastExecution)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }
        return observed(first, last);
    }
    private static Instant earliest(Instant current, Instant candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }
    private static Instant latest(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
    private static String empty(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static int riskRank(String risk) { return risk.equals("CRITICAL") ? 3 : risk.equals("WARNING") ? 2 : 1; }

    private List<List<String>> latestCommandBlocks(List<List<String>> rows) {
        Map<String, List<String>> latest = new LinkedHashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 8) continue;
            latest.put(row.get(1) + ":" + row.get(2) + ":" + row.get(3) + ":" + row.get(4), row);
        }
        return new ArrayList<>(latest.values());
    }

    private List<PdfReportData.CommandBlockRow> aggregateCommandBlocks(List<List<String>> rows,
                                                                        List<List<String>> executions) {
        Map<String, CommandBlockAggregate> values = new LinkedHashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 8) continue;
            try {
                String key = row.get(1) + ":" + row.get(2) + ":" + row.get(3) + ":" + row.get(4);
                CommandBlockAggregate value = values.computeIfAbsent(key, ignored -> new CommandBlockAggregate());
                value.position = key; value.world = row.get(1); value.x = Integer.parseInt(row.get(2));
                value.y = Integer.parseInt(row.get(3)); value.z = Integer.parseInt(row.get(4));
                value.material = row.get(5); value.name = row.get(6); value.command = row.get(7);
                value.conditional = row.size() > 8 ? row.get(8) : "";
                value.facing = row.size() > 9 ? row.get(9) : "";
                if (row.size() > 10 && Boolean.parseBoolean(row.get(10))) value.powered++;
                Instant time = Instant.ofEpochMilli(Long.parseLong(row.get(0)));
                value.firstSeen = earliest(value.firstSeen, time); value.lastSeen = latest(value.lastSeen, time);
                value.observations++;
            } catch (RuntimeException ignored) {}
        }
        for (List<String> row : executions) {
            if (row.size() < 7) continue;
            try {
                String key = row.get(1) + ":" + row.get(2) + ":" + row.get(3) + ":" + row.get(4);
                CommandBlockAggregate value = values.computeIfAbsent(key, ignored -> new CommandBlockAggregate());
                value.position = key; value.world = row.get(1); value.x = Integer.parseInt(row.get(2));
                value.y = Integer.parseInt(row.get(3)); value.z = Integer.parseInt(row.get(4));
                if (value.name == null || value.name.isBlank()) value.name = row.get(5);
                if (value.command == null || value.command.isBlank()) value.command = row.get(6);
                Instant time = Instant.ofEpochMilli(Long.parseLong(row.get(0)));
                value.firstExecution = earliest(value.firstExecution, time);
                value.lastExecution = latest(value.lastExecution, time);
                value.executions++;
            } catch (RuntimeException ignored) {}
        }
        return values.values().stream().map(value -> {
            CommandAssessment assessment = assessCommand(value.material, value.command, value.executions,
                    value.firstExecution, value.lastExecution);
            return new PdfReportData.CommandBlockRow(value.position, value.world, value.x, value.y, value.z,
                    empty(value.material, "UNKNOWN"), empty(value.name, "@"), empty(value.command, ""),
                    value.observations, value.firstSeen, value.lastSeen, value.executions,
                    value.firstExecution, value.lastExecution, value.powered, empty(value.conditional, "desconocido"),
                    empty(value.facing, "desconocido"), assessment.cadence(), assessment.risk(),
                    assessment.impact(), assessment.recommendation());
        }).sorted(Comparator.comparingInt((PdfReportData.CommandBlockRow row) -> riskRank(row.risk())).reversed()
                .thenComparing(Comparator.comparingLong(PdfReportData.CommandBlockRow::executions).reversed())).toList();
    }

    private static CommandAssessment assessCommand(String rawMaterial, String rawCommand, long executions,
                                                    Instant firstExecution, Instant lastExecution) {
        String material = empty(rawMaterial, "").toLowerCase(Locale.ROOT);
        String command = empty(rawCommand, "").toLowerCase(Locale.ROOT);
        boolean repeating = material.contains("repeating");
        boolean broad = command.contains("@e") && !command.contains("distance=") && !command.contains("dx=")
                && !command.contains("dy=") && !command.contains("dz=") && !command.contains("limit=");
        List<String> impacts = new ArrayList<>(), recommendations = new ArrayList<>();
        int score = repeating ? 1 : 0;
        if (broad) {
            score += repeating ? 4 : 2;
            impacts.add("@e puede recorrer entidades de todos los mundos o una zona muy amplia");
            recommendations.add("limita @e con type, distance, dx/dy/dz, tag y limit");
        }
        if (command.contains("summon ")) { score += 2; impacts.add("crea entidades"); recommendations.add("anade limite, cooldown y limpieza verificable"); }
        if (command.contains("particle ")) { score++; impacts.add("genera paquetes de particulas"); recommendations.add("reduce count, frecuencia y radio de receptores"); }
        if (command.contains("forceload")) { score += 3; impacts.add("mantiene chunks cargados"); recommendations.add("retira forceload cuando termine la tarea"); }
        if (command.matches(".*\\b(fill|clone)\\b.*")) { score += 2; impacts.add("modifica muchos bloques y vecinos"); recommendations.add("divide el volumen y evita repetirlo cada tick"); }
        if (command.contains("spreadplayers")) { score += 2; impacts.add("busca posiciones y puede cargar chunks"); recommendations.add("ejecutalo bajo demanda, no de forma repetitiva"); }
        if (command.contains("function ")) { score++; impacts.add("invoca una funcion cuyo coste depende de sus comandos"); recommendations.add("revisa functions.csv y evita llamadas recursivas por tick"); }
        if (command.split("execute", -1).length > 3) { score++; impacts.add("cadena execute compleja"); recommendations.add("reduce ramas y selectores repetidos"); }
        String cadence = repeating ? "Potencial: hasta 20 ejecuciones/s mientras este activo"
                : material.contains("chain") ? "Se ejecuta al activarse la cadena" : "Se ejecuta por impulso/activacion";
        if (executions > 0) {
            double minutes = firstExecution == null || lastExecution == null ? 0
                    : Math.max(1d / 60d, Duration.between(firstExecution, lastExecution).toMillis() / 60_000d);
            cadence += "; observado: " + executions + " ejecuciones"
                    + (minutes > 0 ? " (" + one(executions / minutes) + "/min)" : "");
        } else cadence += "; sin ejecuciones capturadas";
        String risk = score >= 4 ? "CRITICAL" : score >= 1 ? "WARNING" : "INFO";
        String impact = impacts.isEmpty() ? "No se detecto un patron costoso evidente; el coste real depende de frecuencia y contexto."
                : String.join("; ", impacts) + ".";
        String recommendation = recommendations.isEmpty() ? "Conserva el comando y verifica su frecuencia real antes de cambiarlo."
                : String.join("; ", new LinkedHashSet<>(recommendations)) + ".";
        return new CommandAssessment(cadence, risk, impact, recommendation);
    }

    private static String commandReview(List<String> row) {
        if (row.size() < 8) return "fila incompleta";
        String material = row.get(5).toLowerCase(Locale.ROOT);
        String command = row.get(7).toLowerCase(Locale.ROOT);
        List<String> notes = new ArrayList<>();
        boolean broadEntities = command.contains("@e") && !command.contains("distance=")
                && !command.contains("dx=") && !command.contains("limit=");
        if (material.contains("repeating") && broadEntities) notes.add("repetitivo con @e sin limite espacial");
        else if (broadEntities) notes.add("@e sin limite espacial");
        if (command.contains("forceload")) notes.add("modifica chunks forzados");
        if (command.contains("spreadplayers")) notes.add("spreadplayers puede buscar muchas posiciones");
        if (command.contains("execute") && command.split("execute", -1).length > 3) notes.add("cadena execute compleja");
        return notes.isEmpty() ? "sin patron de riesgo evidente" : String.join("; ", notes);
    }

    private Map<String, ChunkAggregate> aggregateChunks(List<ChunkObservation> rows) {
        Map<String, ChunkAggregate> result = new HashMap<>();
        for (ChunkObservation row : rows) result.computeIfAbsent(row.key(), ChunkAggregate::new).add(row);
        return result;
    }

    private Map<String, PlayerAggregate> aggregatePlayers(List<List<String>> rows) {
        Map<String, PlayerAggregate> result = new HashMap<>();
        for (List<String> row : rows) {
            if (row.size() < 9) continue;
            try { result.computeIfAbsent(row.get(2), PlayerAggregate::new).add(Integer.parseInt(row.get(7))); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private EventAggregate aggregateEvents(List<List<String>> rows) {
        EventAggregate result = new EventAggregate();
        for (List<String> row : rows) {
            if (row.size() < 12) continue;
            try {
                result.chunkLoads += Long.parseLong(row.get(2)); result.chunkUnloads += Long.parseLong(row.get(3));
                result.entitySpawns += Long.parseLong(row.get(4)); result.entityDeaths += Long.parseLong(row.get(5));
                result.redstone += Long.parseLong(row.get(6)); result.hoppers += Long.parseLong(row.get(7));
                result.pistons += Long.parseLong(row.get(8)); result.explosions += Long.parseLong(row.get(9));
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private <T> List<T> read(String prefix, Instant cutoff, Function<String, T> parser) throws IOException {
        List<T> result = new ArrayList<>();
        for (Path path : matching(prefix)) {
            try (BufferedReader reader = openReader(path)) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    checkCancelled();
                    try {
                        List<String> fields = Csv.parse(line);
                        if (Instant.ofEpochMilli(Long.parseLong(fields.getFirst())).isBefore(cutoff)) continue;
                        result.add(parser.apply(line));
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return result;
    }

    private List<List<String>> readRows(String prefix, Instant cutoff) throws IOException {
        List<List<String>> result = new ArrayList<>();
        for (Path path : matching(prefix)) {
            try (BufferedReader reader = openReader(path)) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    checkCancelled();
                    try {
                        List<String> fields = Csv.parse(line);
                        if (!Instant.ofEpochMilli(Long.parseLong(fields.getFirst())).isBefore(cutoff)) result.add(fields);
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return result;
    }

    private List<List<String>> readCsv(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return List.of();
        List<List<String>> result = new ArrayList<>();
        try (BufferedReader reader = openReader(path)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                checkCancelled();
                result.add(Csv.parse(line));
            }
        }
        return result;
    }

    private List<Path> matching(String prefix) throws IOException {
        if (!Files.isDirectory(telemetryRoot)) return List.of();
        List<Path> candidates;
        try (Stream<Path> stream = Files.list(telemetryRoot)) {
            candidates = stream.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && (p.getFileName().toString().endsWith(".csv")
                            || p.getFileName().toString().endsWith(".csv.gz")))
                    .sorted().toList();
        }
        Map<String, Path> segments = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            String name = candidate.getFileName().toString();
            String logicalName = name.endsWith(".gz") ? name.substring(0, name.length() - 3) : name;
            Path previous = segments.get(logicalName);
            if (previous == null || name.endsWith(".gz")) segments.put(logicalName, candidate);
        }
        return segments.values().stream().sorted().toList();
    }

    private static BufferedReader openReader(Path path) throws IOException {
        if (path.getFileName().toString().endsWith(".gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(path), 64 * 1024), StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Generación de informe cancelada");
    }

    private void writeCommands(Path path, List<InventorySnapshot.CommandInfo> commands) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("key,name,owner,permission,description,usage");
        for (var c : commands) lines.add(Csv.row(c.key(), c.name(), c.owner(), c.permission(), c.description(), c.usage()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeCommandAliases(Path path, List<InventorySnapshot.CommandInfo> commands) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("command_key,command_name,alias");
        for (var command : commands) {
            for (String alias : command.aliases()) lines.add(Csv.row(command.key(), command.name(), alias));
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writePlugins(Path path, List<InventorySnapshot.PluginInfo> plugins) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("plugin,version,enabled,main_class,api_version,sync_tasks,async_tasks,registered_listeners");
        for (var plugin : plugins) lines.add(Csv.row(plugin.name(), plugin.version(), plugin.enabled(),
                plugin.mainClass(), plugin.apiVersion(), plugin.syncTasks(), plugin.asyncTasks(),
                plugin.registeredListeners()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writePluginAuthors(Path path, List<InventorySnapshot.PluginInfo> plugins) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("plugin,author");
        for (var plugin : plugins) {
            for (String author : plugin.authors()) lines.add(Csv.row(plugin.name(), author));
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writePluginDependencies(Path path, List<InventorySnapshot.PluginInfo> plugins) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("plugin,relationship,dependency");
        for (var plugin : plugins) {
            for (String dependency : plugin.depend()) lines.add(Csv.row(plugin.name(), "required", dependency));
            for (String dependency : plugin.softDepend()) lines.add(Csv.row(plugin.name(), "optional", dependency));
            for (String dependency : plugin.loadBefore()) lines.add(Csv.row(plugin.name(), "load_before", dependency));
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeWorlds(Path path, List<InventorySnapshot.WorldInfo> worlds) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("world,environment,difficulty,seed,loaded_chunks,players,view_distance,simulation_distance,border_size,keep_spawn_loaded");
        for (var world : worlds) lines.add(Csv.row(world.name(), world.environment(), world.difficulty(), world.seed(),
                world.loadedChunks(), world.players(), world.viewDistance(), world.simulationDistance(),
                world.borderSize(), world.keepSpawnLoaded()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeDataPacks(Path path, List<InventorySnapshot.DataPackInfo> packs) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("key,title,source,enabled,required,compatibility,pack_format,min_format,max_format");
        for (var pack : packs) lines.add(Csv.row(pack.key(), pack.title(), pack.source(), pack.enabled(),
                pack.required(), pack.compatibility(), pack.packFormat(), pack.minFormat(), pack.maxFormat()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeDataPackFeatures(Path path, List<InventorySnapshot.DataPackInfo> packs) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("datapack_key,feature");
        for (var pack : packs) {
            for (String feature : pack.features()) lines.add(Csv.row(pack.key(), feature));
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeResourcePack(Path path, InventorySnapshot.ResourcePackInfo pack) throws IOException {
        Files.write(path, List.of("id,url,hash,prompt,required",
                Csv.row(pack.id(), pack.url(), pack.hash(), pack.prompt(), pack.required())),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeFiles(Path path, List<InventorySnapshot.FileInventory.FileInfo> files) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("group,path,bytes,modified,sha256,config_entry_count");
        for (var f : files) lines.add(Csv.row(f.group(), f.path(), f.bytes(), f.modified(), f.sha256(), f.configEntries().size()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeConfigEntries(Path path, List<InventorySnapshot.FileInventory.FileInfo> files) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("group,path,key,value,redacted");
        for (var file : files) {
            for (var entry : file.configEntries()) {
                lines.add(Csv.row(file.group(), file.path(), entry.key(), entry.value(), entry.redacted()));
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writePacks(Path path, List<InventorySnapshot.FileInventory.PackInfo> packs) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("path,entries,uncompressed_bytes,models,textures,block_states,functions,tags,recipes,loot_tables,has_pack_meta");
        for (var pack : packs) lines.add(Csv.row(pack.path(), pack.entries(), pack.uncompressedBytes(), pack.models(),
                pack.textures(), pack.blockStates(), pack.functions(), pack.tags(), pack.recipes(),
                pack.lootTables(), pack.hasPackMeta()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeFunctions(Path path, List<InventorySnapshot.FileInventory.FunctionInfo> functions) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("path,commands,executes,broad_entity_selectors,schedules,world_mutations,summons,particles,risk_score,notes");
        for (var f : functions) lines.add(Csv.row(f.path(), f.commands(), f.executes(), f.broadEntitySelectors(),
                f.schedules(), f.worldMutations(), f.summons(), f.particles(), f.riskScore(), f.notes()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeCommandBlocks(Path path, List<List<String>> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,world,x,y,z,material,name,command,conditional,facing,powered");
        rows.stream().map(r -> Csv.row(r.toArray())).distinct().forEach(lines::add);
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeCommandBlockAnalysis(Path path, List<PdfReportData.CommandBlockRow> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("position,tp_command,material,name,command,observations,first_seen,last_seen,executions,first_execution,last_execution,powered_observations,conditional,facing,cadence,risk,impact,recommendation");
        for (var row : rows) lines.add(Csv.row(row.position(), commandTp(row), row.material(), row.name(), row.command(),
                row.observations(), row.firstSeen(), row.lastSeen(), row.executions(), row.firstExecution(),
                row.lastExecution(), row.poweredObservations(), row.conditional(), row.facing(), row.cadence(),
                row.risk(), row.impact(), row.recommendation()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeEntitySummary(Path path, List<PdfReportData.EntityLifecycleRow> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("type,spawns,removals,observed_balance,average_ticks_lived,max_ticks_lived,average_observed_lifetime_ms,max_observed_lifetime_ms,top_spawn_reason,top_remove_cause,top_location,center_x,center_z,tp_command,top_location_events,ticking,persistent,from_spawner,named,passengers,tracked_players,top_attributed_source");
        for (var row : rows) lines.add(Csv.row(row.type(), row.spawns(), row.removals(), row.observedBalance(),
                row.averageTicksLived(), row.maxTicksLived(), row.averageObservedLifetimeMs(), row.maxObservedLifetimeMs(),
                row.topSpawnReason(), row.topRemoveCause(), row.topLocation(), centerX(row.topLocation()),
                centerZ(row.topLocation()), tpCommand(row.topLocation()), row.topLocationEvents(), row.ticking(),
                row.persistent(), row.fromSpawner(), row.named(), row.passengers(), row.trackedPlayers(),
                row.topAttributedSource()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeChunkAnalysis(Path path, List<PdfReportData.ChunkRow> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("location,center_x,center_z,tp_command,samples,entity_min,entity_average,entity_max,block_entity_min,block_entity_average,block_entity_max,item_max,frame_max,hopper_max,spawner_max,command_block_max,scan_average_micros,scan_max_micros,risk,force_loaded");
        for (var row : rows) lines.add(Csv.row(row.key(), centerX(row.key()), centerZ(row.key()), tpCommand(row.key()),
                row.samples(), row.entityMin(), row.entityAverage(), row.entityMax(), row.blockMin(), row.blockAverage(),
                row.blockMax(), row.itemMax(), row.frameMax(), row.hopperMax(), row.spawnerMax(), row.commandMax(),
                row.averageScanMicros(), row.maxScanMicros(), row.risk(), row.forceLoaded()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeChunkEntityTypes(Path path, List<PdfReportData.ChunkRow> rows) throws IOException {
        writeChunkTypes(path, rows, false);
    }

    private void writeChunkBlockEntityTypes(Path path, List<PdfReportData.ChunkRow> rows) throws IOException {
        writeChunkTypes(path, rows, true);
    }

    private void writeChunkTypes(Path path, List<PdfReportData.ChunkRow> rows, boolean blockEntities) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("location,center_x,center_z,tp_command,type,max_observed_count");
        for (var row : rows) {
            String encoded = blockEntities ? row.blockEntityTypes() : row.entityTypes();
            for (var entry : parseCounts(encoded).entrySet()) {
                lines.add(Csv.row(row.key(), centerX(row.key()), centerZ(row.key()), tpCommand(row.key()),
                        entry.getKey(), entry.getValue()));
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeChunkPluginTickets(Path path, List<PdfReportData.ChunkRow> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("location,center_x,center_z,tp_command,plugin_ticket");
        for (var row : rows) {
            for (String ticket : splitValues(row.pluginTickets())) {
                lines.add(Csv.row(row.key(), centerX(row.key()), centerZ(row.key()), tpCommand(row.key()), ticket));
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeSessionHistory(Path path, List<List<String>> rows) throws IOException {
        List<List<String>> core = new ArrayList<>();
        for (List<String> row : rows) {
            if (row.size() >= 5) core.add(List.copyOf(row.subList(0, 5)));
        }
        writeRows(path, "timestamp,event,session_id,server_version,java_version", core);
    }

    private void writeSessionPlugins(Path path, List<List<String>> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,event,session_id,plugin,version");
        for (List<String> row : rows) {
            if (row.size() <= 5) continue;
            for (String encoded : splitValues(row.get(5))) {
                int separator = encoded.lastIndexOf('@');
                String plugin = separator < 0 ? encoded : encoded.substring(0, separator);
                String version = separator < 0 ? "" : encoded.substring(separator + 1);
                lines.add(Csv.row(row.get(0), row.get(1), row.get(2), plugin, version));
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeSessionDataPacks(Path path, List<List<String>> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,event,session_id,datapack");
        for (List<String> row : rows) {
            if (row.size() <= 6) continue;
            for (String datapack : splitValues(row.get(6))) {
                lines.add(Csv.row(row.get(0), row.get(1), row.get(2), datapack));
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeRows(Path path, String header, List<List<String>> rows) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write(header); writer.newLine();
            for (List<String> row : rows) { writer.write(Csv.row(row.toArray())); writer.newLine(); }
        }
    }

    private static List<List<String>> enrichChunkRows(List<List<String>> rows, int worldIndex,
                                                       int chunkXIndex, int chunkZIndex) {
        List<List<String>> result = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> enriched = new ArrayList<>(row);
            try {
                ChunkLocation location = new ChunkLocation(row.get(worldIndex),
                        Integer.parseInt(row.get(chunkXIndex)), Integer.parseInt(row.get(chunkZIndex)));
                enriched.add(Long.toString(location.centerX()));
                enriched.add(Long.toString(location.centerZ()));
                enriched.add(location.tpCommand());
            } catch (RuntimeException ignored) {
                enriched.add(""); enriched.add(""); enriched.add("");
            }
            result.add(List.copyOf(enriched));
        }
        return result;
    }

    private static List<List<String>> withoutColumn(List<List<String>> rows, int column) {
        List<List<String>> result = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            if (row.size() <= column) {
                result.add(row);
                continue;
            }
            List<String> copy = new ArrayList<>(row);
            copy.remove(column);
            result.add(List.copyOf(copy));
        }
        return result;
    }

    private void writeChunkLifecycleTickets(Path path, List<List<String>> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,event,world,chunk_x,chunk_z,center_x,center_z,tp_command,plugin_ticket");
        for (List<String> row : rows) {
            if (row.size() <= 13) continue;
            try {
                ChunkLocation location = new ChunkLocation(row.get(2), Integer.parseInt(row.get(3)),
                        Integer.parseInt(row.get(4)));
                for (String ticket : splitValues(row.get(13))) {
                    lines.add(Csv.row(row.get(0), row.get(1), location.world(), location.chunkX(), location.chunkZ(),
                            location.centerX(), location.centerZ(), location.tpCommand(), ticket));
                }
            } catch (RuntimeException ignored) {}
        }
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeIncidents(Path path, List<PdfReportData.IncidentRow> incidents) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,severity,signal,location,center_x,center_z,tp_command,detail");
        for (var incident : incidents) lines.add(Csv.row(incident.timestamp().toEpochMilli(), incident.severity(),
                incident.signal(), incident.location(), centerX(incident.location()), centerZ(incident.location()),
                tpCommand(incident.location()), incident.detail()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writePluginAnalysis(Path path, List<PdfReportData.PluginAnalysisRow> plugins) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("plugin,version,sync_tasks,async_tasks,listeners,jfr_plugin_samples,jfr_execution_samples,assessment");
        for (var plugin : plugins) lines.add(Csv.row(plugin.plugin(), plugin.version(), plugin.syncTasks(),
                plugin.asyncTasks(), plugin.listeners(), plugin.profileSamples(), plugin.totalProfileSamples(),
                plugin.assessment()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writePerformanceEpisodes(Path path, List<PdfReportData.PerformanceEpisode> episodes) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("started_at,worst_at,recovered_at,severity,trigger,min_tps,max_tick_p95_ms,baseline_mspt,incident_mspt,recovery_mspt,duration_ms,probable_cause,evidence,location,center_x,center_z,tp_command,confidence,recommendation");
        for (var episode : episodes) lines.add(Csv.row(episode.startedAt().toEpochMilli(), episode.worstAt().toEpochMilli(),
                episode.recoveredAt() == null ? "" : episode.recoveredAt().toEpochMilli(), episode.severity(),
                episode.trigger(), episode.minTps(), episode.maxTickP95Ms(), episode.baselineMspt(),
                episode.incidentMspt(), episode.recoveryMspt(), episode.durationMs(), episode.probableCause(),
                episode.evidence(), episode.location(), centerX(episode.location()), centerZ(episode.location()),
                tpCommand(episode.location()), episode.confidence(), episode.recommendation()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeProfileIo(Path path, List<PdfReportData.ProfileIoRow> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("started_at,completed_at,profile_id,trigger,socket_read_bytes,socket_write_bytes,file_read_bytes,file_write_bytes,gc_events,socket_io_nanos,max_socket_io_nanos,file_io_nanos,max_file_io_nanos");
        for (var row : rows) lines.add(Csv.row(row.startedAt().toEpochMilli(), row.completedAt().toEpochMilli(),
                row.profileId(), row.trigger(), row.socketReadBytes(), row.socketWriteBytes(),
                row.fileReadBytes(), row.fileWriteBytes(), row.gcEvents(), row.socketIoNanos(),
                row.maxSocketIoNanos(), row.fileIoNanos(), row.maxFileIoNanos()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void writeDiagnosticFindings(Path path, List<DiagnosticFinding> findings) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("severity,title,location,evidence,probable_cause,impact,observed_time,action,verification");
        for (var finding : findings) lines.add(Csv.row(finding.severity(), finding.title(), finding.location(),
                finding.evidence(), finding.probableCause(), finding.impact(), finding.observedTime(),
                finding.action(), finding.verification()));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private void copyIfPresent(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) Files.copy(source, target);
    }

    private static void metric(StringBuilder out, String name, List<ServerSample> samples,
                               java.util.function.ToDoubleFunction<ServerSample> value) {
        var stats = samples.stream().mapToDouble(value).filter(v -> v >= 0).summaryStatistics();
        if (stats.getCount() == 0) {
            out.append('|').append(name).append("|n/a|n/a|n/a|\n");
            return;
        }
        out.append('|').append(name).append('|').append(one(stats.getMin())).append('|')
                .append(one(stats.getAverage())).append('|').append(one(stats.getMax())).append("|\n");
    }

    private static void addEvent(StringBuilder out, String name, long total, Duration period) {
        double minutes = Math.max(1.0 / 60, period.toSeconds() / 60.0);
        out.append('|').append(name).append('|').append(total).append('|').append(one(total / minutes)).append("|\n");
    }

    private static String md(String value) { return value == null ? "" : value.replace("|", "\\|").replace("\n", " ").replace("\r", " "); }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private static String top(Map<String, Long> values) {
        return values.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }
    private static void mergeMaxCounts(Map<String, Integer> target, String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        for (String item : encoded.split(";")) {
            int split = item.lastIndexOf('=');
            if (split <= 0) continue;
            try { target.merge(item.substring(0, split), Integer.parseInt(item.substring(split + 1)), Math::max); }
            catch (NumberFormatException ignored) {}
        }
    }
    private static Map<String, Integer> parseCounts(String encoded) {
        Map<String, Integer> result = new LinkedHashMap<>();
        mergeMaxCounts(result, encoded);
        return result;
    }
    private static List<String> splitValues(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        return Stream.of(encoded.split(";"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
    private static String formatCounts(Map<String, Integer> values) {
        return values.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(";"));
    }
    private static long topCount(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).max().orElse(0);
    }
    private static boolean bool(List<String> row, int index) {
        return index < row.size() && Boolean.parseBoolean(row.get(index));
    }
    private static long number(List<String> row, int index) {
        try { return index < row.size() && !row.get(index).isBlank() ? Long.parseLong(row.get(index)) : 0; }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static String one(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f", value) : "n/a"; }
    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return one(value / 1024.0) + " KiB";
        if (value < 1024L * 1024 * 1024) return one(value / (1024.0 * 1024)) + " MiB";
        return one(value / (1024.0 * 1024 * 1024)) + " GiB";
    }
    private static String compact(Duration d) { return d.toDays() > 0 ? d.toDays() + "d" : d.toHours() > 0 ? d.toHours() + "h" : d.toMinutes() + "m"; }
    private static String human(Duration d) { return d.toDays() > 0 ? d.toDays() + " dia(s)" : d.toHours() > 0 ? d.toHours() + " hora(s)" : d.toMinutes() + " minuto(s)"; }
    private static String humanDurationMillis(long value) {
        Duration duration = Duration.ofMillis(Math.max(0, value));
        if (duration.toDays() > 0) return duration.toDays() + " d " + duration.toHoursPart() + " h";
        if (duration.toHours() > 0) return duration.toHours() + " h " + duration.toMinutesPart() + " min";
        if (duration.toMinutes() > 0) return duration.toMinutes() + " min " + duration.toSecondsPart() + " s";
        return duration.toSeconds() + " s";
    }

    @Override public void close() { executor.shutdownNow(); }

    private static final class ChunkAggregate {
        final String key;
        long samples, entitySum, blockSum;
        long scanSum, scanMax;
        int entityMin = Integer.MAX_VALUE, entityMax, blockMin = Integer.MAX_VALUE, blockMax;
        int itemMax, frameMax, hopperMax, spawnerMax, commandMax;
        boolean forceLoaded;
        final Map<String, Integer> entityTypeMax = new HashMap<>();
        final Map<String, Integer> blockEntityTypeMax = new HashMap<>();
        final Set<String> pluginTickets = new LinkedHashSet<>();
        ChunkAggregate(String key) { this.key = key; }
        void add(ChunkObservation v) {
            samples++; entitySum += v.entities(); blockSum += v.blockEntities();
            entityMin = Math.min(entityMin, v.entities()); entityMax = Math.max(entityMax, v.entities());
            blockMin = Math.min(blockMin, v.blockEntities()); blockMax = Math.max(blockMax, v.blockEntities());
            itemMax = Math.max(itemMax, v.itemEntities()); frameMax = Math.max(frameMax, v.itemFrames());
            hopperMax = Math.max(hopperMax, v.hoppers()); spawnerMax = Math.max(spawnerMax, v.spawners());
            commandMax = Math.max(commandMax, v.commandBlocks());
            forceLoaded |= v.forceLoaded();
            mergeMaxCounts(entityTypeMax, v.entityTypes());
            mergeMaxCounts(blockEntityTypeMax, v.blockEntityTypes());
            if (!v.pluginTickets().isBlank()) pluginTickets.addAll(List.of(v.pluginTickets().split(";")));
            scanSum += v.scanMicros(); scanMax = Math.max(scanMax, v.scanMicros());
        }
        double entityAverage() { return samples == 0 ? 0 : (double) entitySum / samples; }
        double blockAverage() { return samples == 0 ? 0 : (double) blockSum / samples; }
        double scanAverage() { return samples == 0 ? 0 : (double) scanSum / samples; }
        double risk() { return entityMax + blockMax * 2.0 + hopperMax * 4.0 + commandMax * 4.0; }
        String entityTypes() { return formatCounts(entityTypeMax); }
        String blockEntityTypes() { return formatCounts(blockEntityTypeMax); }
    }

    private static final class PlayerAggregate {
        final String name;
        int samples, pingMin = Integer.MAX_VALUE, pingMax;
        long pingSum;
        PlayerAggregate(String name) { this.name = name; }
        void add(int ping) { samples++; pingSum += ping; pingMin = Math.min(pingMin, ping); pingMax = Math.max(pingMax, ping); }
        double pingAverage() { return samples == 0 ? 0 : (double) pingSum / samples; }
    }

    private static final class EventAggregate {
        long chunkLoads, chunkUnloads, entitySpawns, entityDeaths, redstone, hoppers, pistons, explosions;
    }

    private static final class EntityLifecycleAggregate {
        long spawns, removals, ticks, observedLifetimeMs, observedLifetimeSamples, maxObservedLifetimeMs;
        long ticking, persistent, fromSpawner, named, passengers, trackedPlayers;
        int maxTicks;
        final Map<String, Long> spawnReasons = new HashMap<>();
        final Map<String, Long> removeCauses = new HashMap<>();
        final Map<String, Long> locations = new HashMap<>();
        final Map<String, Long> attributedSources = new HashMap<>();
    }

    private static final class RegionAggregate {
        long files, chunks, bytes, malformed;
    }

    private static final class ChunkLifecycleAggregate {
        long loads, unloads, newChunks, durationSum, durationSamples, maxDuration;
    }
    private static final class CommandBlockAggregate {
        String position, world, material, name, command, conditional, facing;
        int x, y, z;
        long observations, executions, powered;
        Instant firstSeen, lastSeen, firstExecution, lastExecution;
    }
    private record CommandAssessment(String cadence, String risk, String impact, String recommendation) {}
    private record CommandExecution(Instant timestamp, String position, String command) {}
    private record SessionEvent(Instant timestamp, String event, String sessionId, String plugins) {}
    private record StartupHeader(Instant timestamp, String level) {}
    private record StartupDiagnosticEntry(Instant timestamp, String level, String category, String stage,
                                          String origin, String logger, String error, String cause,
                                          String solution) {
        List<String> toRow() {
            return List.of(timestamp.toString(), level, category, stage, origin, logger, error, cause, solution);
        }
    }
    private static final class StartupDiagnosticBuilder {
        final Instant timestamp;
        final String level;
        String category = "", stage = "", origin = "", logger = "", error = "", cause = "", solution = "";
        StartupDiagnosticBuilder(Instant timestamp, String level) {
            this.timestamp = timestamp;
            this.level = level;
        }
        StartupDiagnosticEntry build() {
            return new StartupDiagnosticEntry(timestamp, level, category, stage, origin, logger, error, cause, solution);
        }
    }
    private record ComparisonSpec(String name, ToDoubleFunction<ServerSample> value, String suffix, int direction) {}
    private record Pair(double x, double y) {}
}
