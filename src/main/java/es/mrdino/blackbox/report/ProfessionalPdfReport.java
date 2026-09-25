package es.mrdino.blackbox.report;

import es.mrdino.blackbox.i18n.Messages;
import es.mrdino.blackbox.model.ServerSample;
import es.mrdino.blackbox.util.ChunkLocation;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;
import java.util.function.ToDoubleFunction;

public final class ProfessionalPdfReport {
    private static final Color NAVY = new Color(10, 19, 43);
    private static final Color BLUE = new Color(49, 87, 213);
    private static final Color CYAN = new Color(25, 198, 226);
    private static final Color TEXT = new Color(29, 38, 55);
    private static final Color MUTED = new Color(91, 102, 119);
    private static final Color LIGHT = new Color(243, 246, 251);
    private static final Color LINE = new Color(218, 224, 234);
    private static final Color GREEN = new Color(28, 158, 113);
    private static final Color AMBER = new Color(222, 145, 24);
    private static final Color RED = new Color(207, 59, 70);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm z", new Locale("es", "ES"))
            .withZone(ZoneId.systemDefault());
    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDFont MONO = new PDType1Font(Standard14Fonts.FontName.COURIER);

    private ProfessionalPdfReport() {}

    public static void write(Path destination, PdfReportData data) throws IOException {
        write(destination, data, Messages.spanish());
    }

    public static void write(Path destination, PdfReportData data, Messages messages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(messages.text("BlackBox - Informe de observabilidad de " + data.live().server().name()));
            info.setAuthor("BlackBox Server Observability");
            info.setSubject(messages.text("Rendimiento, diagnostico e inventario tecnico de Paper 1.21.4–26.3"));
            Layout pdf = new Layout(document, data, messages);
            pdf.cover();
            pdf.createTocPage();
            pdf.newContentPage();
            pdf.executiveOverview();
            pdf.performance();
            pdf.causeAndEffect();
            pdf.resources();
            pdf.historyAndComparison();
            pdf.findings();
            pdf.activity();
            pdf.timeline();
            pdf.runtimeForensics();
            pdf.chunkHotspots();
            pdf.players();
            pdf.commandBlocks();
            pdf.worldsAndPlugins();
            pdf.packsAndAssets();
            pdf.offlineWorldCensus();
            pdf.coverage();
            pdf.glossary();
            pdf.finish();
            document.save(destination.toFile());
        }
    }

    private static final class Layout {
        private static final float MARGIN = 42;
        private static final float CONTENT_WIDTH = PDRectangle.A4.getWidth() - MARGIN * 2;
        private final PDDocument document;
        private final PdfReportData data;
        private final Messages messages;
        private final DateTimeFormatter date;
        private PDPage page;
        private PDPage tocPage;
        private PDPageContentStream stream;
        private float y;
        private boolean freshPage;
        private final List<SectionEntry> sections = new ArrayList<>();

        private Layout(PDDocument document, PdfReportData data, Messages messages) {
            this.document = document;
            this.data = data;
            this.messages = messages;
            this.date = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm z", messages.locale())
                    .withZone(ZoneId.systemDefault());
        }

        void cover() throws IOException {
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            fill(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight(), NAVY);
            fill(MARGIN, 98, 7, 646, CYAN);
            text("BLACKBOX", BOLD, 15, MARGIN + 27, 735, CYAN);
            text("SERVER", BOLD, 42, MARGIN + 27, 647, Color.WHITE);
            text("OBSERVABILITY", BOLD, 42, MARGIN + 27, 598, Color.WHITE);
            text("REPORT", BOLD, 42, MARGIN + 27, 549, Color.WHITE);
            text("Rendimiento, diagnostico e inventario tecnico", REGULAR, 15, MARGIN + 29, 507, new Color(190, 203, 226));
            fill(MARGIN + 27, 347, 475, 105, new Color(17, 31, 65));
            text(shorten(safe(data.live().server().name()), 45), BOLD, 20, MARGIN + 47, 418, Color.WHITE);
            text(shorten(safe(data.live().server().version()), 82), REGULAR, 9, MARGIN + 47, 397, new Color(190, 203, 226));
            text("Ventana analizada", BOLD, 8, MARGIN + 47, 371, CYAN);
            text(human(data.period()) + "  |  desde " + date.format(data.cutoff()), REGULAR, 10, MARGIN + 147, 370, Color.WHITE);
            text("GENERADO", BOLD, 8, MARGIN + 29, 146, CYAN);
            text(date.format(data.generated()), REGULAR, 10, MARGIN + 29, 128, Color.WHITE);
            text("Paper 1.21.4–26.3  /  BlackBox 0.6.0", REGULAR, 8, MARGIN + 29, 107, new Color(143, 160, 190));
            stream.close();
            stream = null;
        }

        void createTocPage() {
            tocPage = new PDPage(PDRectangle.A4);
            document.addPage(tocPage);
        }

        void newContentPage() throws IOException {
            if (stream != null) stream.close();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            fill(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight(), Color.WHITE);
            fill(0, 798, page.getMediaBox().getWidth(), 44, NAVY);
            text("BLACKBOX", BOLD, 10, MARGIN, 815, CYAN);
            text(shorten(safe(data.live().server().name()), 78), REGULAR, 8, MARGIN + 77, 815, new Color(200, 211, 232));
            y = 775;
            freshPage = true;
        }

        void executiveOverview() throws IOException {
            section("01", "Resumen ejecutivo", "Estado observado y senales que requieren atencion");
            int critical = (int) data.findings().stream().filter(f -> f.severity() == DiagnosticFinding.Severity.CRITICAL).count();
            int warnings = (int) data.findings().stream().filter(f -> f.severity() == DiagnosticFinding.Severity.WARNING).count();
            int score = Math.max(0, 100 - critical * 30 - warnings * 10);
            Color scoreColor = score >= 85 ? GREEN : score >= 65 ? AMBER : RED;
            fill(MARGIN, y - 82, CONTENT_WIDTH, 76, LIGHT);
            fill(MARGIN, y - 82, 7, 76, scoreColor);
            text("INDICE DE SALUD", BOLD, 8, MARGIN + 22, y - 27, MUTED);
            text(Integer.toString(score), BOLD, 30, MARGIN + 22, y - 62, scoreColor);
            text("/ 100", REGULAR, 9, MARGIN + 70, y - 60, MUTED);
            text(critical + " criticos", BOLD, 10, MARGIN + 153, y - 39, critical > 0 ? RED : MUTED);
            text(warnings + " advertencias", BOLD, 10, MARGIN + 260, y - 39, warnings > 0 ? AMBER : MUTED);
            text(data.serverSamples().size() + " muestras", BOLD, 10, MARGIN + 392, y - 39, BLUE);
            y -= 105;

            List<ServerSample> samples = data.serverSamples();
            metricCards(List.of(
                    new Metric("TPS minimo", value(samples, ServerSample::tps1m, Mode.MIN), "ticks/s", BLUE),
                    new Metric("MSPT maximo", value(samples, ServerSample::tickP95Ms, Mode.MAX), "ms p95", AMBER),
                    new Metric("CPU maxima", value(samples, ServerSample::processCpuPercent, Mode.MAX), "%", RED),
                    new Metric("Heap maximo", value(samples, s -> 100d * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()), Mode.MAX), "%", BLUE),
                    new Metric("Jugadores max.", value(samples, s -> s.players(), Mode.MAX), "online", GREEN),
                    new Metric("Chunks max.", value(samples, s -> s.loadedChunks(), Mode.MAX), "cargados", CYAN)
            ));
        }

        void performance() throws IOException {
            ensure(370);
            section("02", "Rendimiento y estabilidad", "Serie temporal y distribucion de recursos");
            lineChart("TPS - ventana de 1 minuto", data.serverSamples(), ServerSample::tps1m, 0, 20, BLUE, 20);
            double maxMspt = Math.max(50, data.serverSamples().stream().mapToDouble(ServerSample::tickP95Ms).max().orElse(50));
            lineChart("Latencia de tick - p95", data.serverSamples(), ServerSample::tickP95Ms, 0, maxMspt, AMBER, 50);
            List<List<String>> rows = new ArrayList<>();
            rows.add(metricRow("TPS 1 min", data.serverSamples(), ServerSample::tps1m, ""));
            rows.add(metricRow("MSPT medio", data.serverSamples(), ServerSample::averageMspt, " ms"));
            rows.add(metricRow("Tick p95", data.serverSamples(), ServerSample::tickP95Ms, " ms"));
            rows.add(metricRow("Tick max", data.serverSamples(), ServerSample::tickMaxMs, " ms"));
            rows.add(metricRow("CPU proceso", data.serverSamples(), ServerSample::processCpuPercent, "%"));
            rows.add(metricRow("Heap usado", data.serverSamples(), s -> 100d * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()), "%"));
            rows.add(metricRow("Chunks cargados", data.serverSamples(), s -> s.loadedChunks(), ""));
            rows.add(metricRow("Entidades escaneadas", data.serverSamples(), s -> s.scannedEntities(), ""));
            table(List.of("Metrica", "Minimo", "Media", "Maximo"), rows, new float[]{.40f, .20f, .20f, .20f});
            subtitle("Correlacion temporal con MSPT");
            List<List<String>> correlations = data.correlations().stream().limit(data.reportOptions().pdfCorrelations())
                    .map(c -> List.of(c.signal(), one(c.correlationWithMspt()), Integer.toString(c.samples()), c.interpretation()))
                    .toList();
            table(List.of("Senal", "Pearson r", "Intervalos", "Lectura"), correlations,
                    new float[]{.35f, .16f, .16f, .33f});
            paragraph("r positivo indica que la senal y el MSPT aumentaron juntos; r negativo indica relacion inversa. Correlacion no prueba causalidad y siempre debe confirmarse con un perfil.", 8.2f, MUTED);
        }

        void resources() throws IOException {
            ensure(260);
            section("03", "JVM y recursos del sistema", "Memoria, GC, hilos, disco y limites del proceso");
            List<ServerSample> samples = data.serverSamples();
            long gcCollections = counterIncrease(samples, ServerSample::gcCount);
            long gcMillis = counterIncrease(samples, ServerSample::gcTimeMs);
            metricCards(List.of(
                    new Metric("RAM fisica max.", value(samples, s -> percentUsed(s.physicalMemoryBytes(), s.freePhysicalMemoryBytes()), Mode.MAX), "%", BLUE),
                    new Metric("Memoria directa", humanBytes(maxLong(samples, ServerSample::directBufferBytes)), "maximo", CYAN),
                    new Metric("Hilos max.", value(samples, s -> s.threadCount(), Mode.MAX), "threads", AMBER),
                    new Metric("Disco libre min.", humanBytes(minLong(samples, ServerSample::diskUsableBytes)), "disponible", GREEN),
                    new Metric("Colecciones GC", Long.toString(gcCollections), "en el periodo", AMBER),
                    new Metric("Tiempo en GC", humanMillis(gcMillis), "acumulado", RED)
            ));
            lineChart("CPU del proceso", samples, ServerSample::processCpuPercent, 0, 100, RED, 90);
            lineChart("CPU total del sistema", samples, ServerSample::systemCpuPercent, 0, 100, AMBER, 90);
            lineChart("Heap JVM usado", samples,
                    s -> 100d * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()), 0, 100, BLUE, 90);
            lineChart("RAM fisica usada", samples,
                    s -> percentUsed(s.physicalMemoryBytes(), s.freePhysicalMemoryBytes()), 0, 100, CYAN, 95);
            double maxThreads = Math.max(10, samples.stream().mapToInt(ServerSample::threadCount).max().orElse(10));
            lineChart("Hilos JVM", samples, s -> s.threadCount(), 0, maxThreads, AMBER, -1);
            double maxDiskGiB = Math.max(1, samples.stream().mapToDouble(s -> s.diskUsableBytes() / (1024d * 1024 * 1024)).max().orElse(1));
            lineChart("Disco libre (GiB)", samples, s -> s.diskUsableBytes() / (1024d * 1024 * 1024),
                    0, maxDiskGiB, GREEN, 2);
            List<List<String>> rows = new ArrayList<>();
            rows.add(metricRow("CPU del sistema", samples, ServerSample::systemCpuPercent, "%"));
            rows.add(metricRow("Carga del sistema", samples, ServerSample::systemLoadAverage, ""));
            rows.add(metricRow("RAM fisica usada", samples,
                    s -> percentUsed(s.physicalMemoryBytes(), s.freePhysicalMemoryBytes()), "%"));
            rows.add(metricRow("Swap usada", samples, s -> percentUsed(s.swapBytes(), s.freeSwapBytes()), "%"));
            rows.add(byteMetricRow("Memoria no-heap", samples, ServerSample::nonHeapUsedBytes));
            rows.add(byteMetricRow("Buffer directo", samples, ServerSample::directBufferBytes));
            rows.add(metricRow("Hilos", samples, s -> s.threadCount(), ""));
            rows.add(metricRow("Descriptores abiertos", samples, s -> s.openFileDescriptors(), ""));
            rows.add(metricRow("Cola de telemetria", samples, s -> s.telemetryQueuedRows(), ""));
            rows.add(metricRow("Deadlocks", samples, s -> s.deadlockedThreads(), ""));
            table(List.of("Recurso", "Minimo", "Media", "Maximo"), rows,
                    new float[]{.40f, .20f, .20f, .20f});
            if (!data.profileIo().isEmpty()) {
                subtitle("Disco, red y GC observados durante perfiles JFR");
                List<List<String>> io = data.profileIo().stream().map(p -> List.of(date.format(p.startedAt()),
                        p.profileId(), p.trigger(), humanBytes(p.socketReadBytes()) + " / " + humanBytes(p.socketWriteBytes()),
                        humanBytes(p.fileReadBytes()) + " / " + humanBytes(p.fileWriteBytes()),
                        one(p.maxSocketIoNanos() / 1_000_000d) + " / " + one(p.maxFileIoNanos() / 1_000_000d) + " ms",
                        Long.toString(p.gcEvents()))).toList();
                table(List.of("Inicio", "Perfil", "Tipo", "Socket R/W", "Archivo R/W", "Max socket/file", "GC"), io,
                        new float[]{.17f, .16f, .10f, .16f, .16f, .17f, .08f});
            }
            paragraph("Los contadores de GC se calculan por incrementos para tolerar reinicios de la JVM. Los valores -1 o no expuestos por el sistema operativo se excluyen.", 8.2f, MUTED);
        }

        void causeAndEffect() throws IOException {
            ensure(190);
            section("02A", "Causa y efecto", "Cuando empeoro, que coincidio y cuando se recupero");
            if (data.performanceEpisodes().isEmpty()) {
                callout(GREEN, "SIN EPISODIOS", "No se detectaron muestras bajo los umbrales de TPS o p95 de tick en esta ventana.");
                return;
            }
            for (PdfReportData.PerformanceEpisode episode : data.performanceEpisodes()
                    .stream().limit(data.reportOptions().pdfIncidents()).toList()) episodeCard(episode);
            paragraph("La confianza ALTA exige evidencia directa de log o stacks JFR. MEDIA combina varias senales temporales. BAJA es una hipotesis para reproducir. performance-episodes.csv conserva todos los valores y coordenadas.", 8.2f, MUTED);
        }

        void historyAndComparison() throws IOException {
            ensure(190);
            section("04", "Historial y comparativa", "Reinicios, cambios de software y evolucion frente al periodo anterior");
            PdfReportData.SessionSummary sessions = data.sessionSummary();
            table(List.of("Arranques", "Apagados limpios", "Sin cierre", "Tiempo apagado", "Cambios plugins"),
                    List.of(List.of(Integer.toString(sessions.starts()), Integer.toString(sessions.cleanStops()),
                            Integer.toString(sessions.uncleanStarts()), humanMillis(sessions.observedDowntimeMs()),
                            Integer.toString(sessions.changes().size()))),
                    new float[]{.17f, .22f, .16f, .22f, .23f});
            PdfReportData.Comparison comparison = data.comparison();
            Color comparisonColor = comparison.assessment().toLowerCase(Locale.ROOT).contains("empeora") ? RED
                    : comparison.assessment().toLowerCase(Locale.ROOT).contains("mejora") ? GREEN : BLUE;
            callout(comparisonColor, "LECTURA COMPARATIVA", comparison.assessment());
            if (!comparison.metrics().isEmpty()) {
                paragraph("Anterior: " + comparison.beforeLabel(), 7.8f, MUTED);
                paragraph("Actual: " + comparison.afterLabel(), 7.8f, MUTED);
                table(List.of("Metrica", "Anterior", "Actual", "Cambio", "Lectura"),
                        comparison.metrics().stream().map(m -> List.of(m.metric(), m.before(), m.after(), m.delta(), m.reading())).toList(),
                        new float[]{.28f, .17f, .17f, .16f, .22f});
            }
            if (!sessions.changes().isEmpty()) {
                subtitle("Cambios de plugins detectados en arranques");
                table(List.of("Momento", "Anadidos", "Retirados", "Actualizados"),
                        sessions.changes().stream().map(c -> List.of(date.format(c.timestamp()), c.added(), c.removed(), c.updated())).toList(),
                        new float[]{.24f, .25f, .25f, .26f});
            }
            paragraph("Una mejora o regresion cercana a un cambio de plugin es una pista temporal. La atribucion se confirma con stacks JFR y reproduccion controlada.", 8.2f, MUTED);
        }

        void findings() throws IOException {
            ensure(120);
            section("05", "Hallazgos y acciones", "Diagnostico basado en umbrales y correlaciones observadas");
            if (data.findings().isEmpty()) {
                callout(GREEN, "SIN UMBRALES SUPERADOS", "No se detectaron alertas en las muestras disponibles. Esto no descarta fallos fuera de la ventana o variables no expuestas por Paper.");
            }
            for (DiagnosticFinding finding : data.findings()) {
                Color color = finding.severity() == DiagnosticFinding.Severity.CRITICAL ? RED
                        : finding.severity() == DiagnosticFinding.Severity.WARNING ? AMBER : BLUE;
                findingCard(finding, color);
            }
        }

        void activity() throws IOException {
            ensure(245);
            section("06", "Actividad del servidor", "Eventos acumulados durante la ventana seleccionada");
            PdfReportData.EventRow e = data.events();
            double minutes = Math.max(1d / 60d, data.period().toSeconds() / 60d);
            table(List.of("Senal", "Total", "Media/min"), List.of(
                    row("Cargas de chunks", e.chunkLoads(), e.chunkLoads() / minutes),
                    row("Descargas de chunks", e.chunkUnloads(), e.chunkUnloads() / minutes),
                    row("Spawns de entidades", e.entitySpawns(), e.entitySpawns() / minutes),
                    row("Muertes de entidades", e.entityDeaths(), e.entityDeaths() / minutes),
                    row("Cambios de redstone", e.redstone(), e.redstone() / minutes),
                    row("Movimientos de hopper", e.hoppers(), e.hoppers() / minutes),
                    row("Acciones de pistones", e.pistons(), e.pistons() / minutes),
                    row("Explosiones", e.explosions(), e.explosions() / minutes)
            ), new float[]{.55f, .22f, .23f});
        }

        void timeline() throws IOException {
            ensure(150);
            section("07", "Cronologia de incidentes", "Marcas por severidad, momento y localizacion observada");
            if (data.incidents().isEmpty()) {
                callout(GREEN, "SIN INCIDENTES REGISTRADOS", "No hay umbrales, errores ni eventos localizados que marcar en esta ventana.");
                return;
            }
            int shown = 0;
            for (PdfReportData.IncidentRow incident : data.incidents()) {
                if (shown++ >= data.reportOptions().pdfIncidents()) break;
                incidentRow(incident);
            }
            paragraph("Para una localizacion mundo:chunk se muestra el centro X/Z y un /tp que conserva la altura actual. Los hotspots agregados indican concentracion y deben contrastarse con MSPT/JFR.", 8.2f, MUTED);
        }

        void chunkHotspots() throws IOException {
            ensure(180);
            section("09", "Densidad por chunk", "Minimos, medias y maximos de las zonas con mayor carga potencial");
            List<List<String>> rows = data.chunks().stream().sorted(Comparator.comparingDouble(PdfReportData.ChunkRow::risk).reversed())
                    .limit(data.reportOptions().pdfChunks()).map(c -> List.of(chunkNavigation(c.key()), Long.toString(c.samples()),
                            c.entityMin() + " / " + one(c.entityAverage()) + " / " + c.entityMax(),
                            c.blockMin() + " / " + one(c.blockAverage()) + " / " + c.blockMax(),
                            Integer.toString(c.itemMax()), Integer.toString(c.hopperMax()),
                            one(c.averageScanMicros() / 1000d) + "/" + one(c.maxScanMicros() / 1000d)))
                    .toList();
            table(List.of("Chunk / centro / TP", "N", "Entidades min/med/max", "B.E. min/med/max", "Items", "Hoppers", "Scan ms med/max"),
                    rows, new float[]{.27f, .05f, .19f, .19f, .07f, .08f, .15f});
            subtitle("Tipos y responsables de carga por chunk");
            List<List<String>> contents = data.chunks().stream().sorted(Comparator.comparingDouble(PdfReportData.ChunkRow::risk).reversed())
                    .limit(data.reportOptions().pdfChunks()).map(c -> List.of(chunkNavigation(c.key()), c.entityTypes(),
                            c.blockEntityTypes(), c.forceLoaded() ? "Si" : "No", c.pluginTickets())).toList();
            table(List.of("Chunk / centro / TP", "Tipos de entidad (max)", "Block entities (max)", "Forzado", "Tickets de plugins"),
                    contents, new float[]{.27f, .25f, .25f, .08f, .15f});
        }

        void players() throws IOException {
            ensure(175);
            section("10", "Jugadores y conectividad", "Latencia estimada desde el servidor");
            List<List<String>> rows = data.players().stream().sorted(Comparator.comparingInt(PdfReportData.PlayerRow::pingMax).reversed())
                    .limit(data.reportOptions().pdfPlayers()).map(p -> List.of(p.name(), Integer.toString(p.samples()), Integer.toString(p.pingMin()),
                            one(p.pingAverage()), Integer.toString(p.pingMax()))).toList();
            table(List.of("Jugador", "Muestras", "Ping min", "Ping medio", "Ping max"), rows,
                    new float[]{.34f, .16f, .16f, .17f, .17f});
            paragraph("El ping es una estimacion del servidor. El protocolo normal de Minecraft no envia FPS, GPU, shaders, memoria o carga del cliente.", 8.5f, MUTED);
        }

        void commandBlocks() throws IOException {
            ensure(175);
            section("11", "Command blocks", "Inventario unico por posicion y revision estatica de patrones");
            if (data.commandBlocks().isEmpty()) {
                callout(GREEN, "SIN COMMAND BLOCKS OBSERVADOS", "No se encontraron command blocks cargados ni ejecuciones notificadas por Paper durante esta ventana.");
            }
            int shown = 0;
            for (PdfReportData.CommandBlockRow row : data.commandBlocks()) {
                if (shown++ >= data.reportOptions().pdfCommandBlocks()) break;
                commandBlockCard(row);
            }
            paragraph("Las ejecuciones son las notificadas por Paper. Un bloque inventariado sin ejecuciones puede estar inactivo o haberse ejecutado fuera de la ventana. command-block-analysis.csv conserva el detalle completo.", 8.5f, MUTED);
        }

        void worldsAndPlugins() throws IOException {
            ensure(190);
            section("12", "Topologia e inventario", "Mundos, plugins y superficie de ejecucion");
            List<List<String>> worlds = data.live().worlds().stream().map(w -> List.of(w.name(), w.environment(),
                    w.difficulty(), Integer.toString(w.loadedChunks()), Integer.toString(w.players()),
                    w.viewDistance() + " / " + w.simulationDistance(), one(w.borderSize()))).toList();
            table(List.of("Mundo", "Entorno", "Dificultad", "Chunks", "Players", "View/Sim", "Borde"), worlds,
                    new float[]{.24f, .15f, .15f, .11f, .10f, .13f, .12f});
            subtitle("Plugins instalados");
            List<List<String>> plugins = data.live().plugins().stream().map(p -> List.of(p.name(), p.version(),
                    p.enabled() ? "Activo" : "Inactivo", p.syncTasks() + "/" + p.asyncTasks(),
                    Integer.toString(p.registeredListeners()), shorten(p.mainClass(), 45),
                    shorten(String.join(", ", p.depend()), 35))).toList();
            table(List.of("Plugin", "Version", "Estado", "Tasks S/A", "Listeners", "Clase principal", "Deps"), plugins,
                    new float[]{.17f, .11f, .10f, .10f, .10f, .27f, .15f});
            subtitle("Analisis operativo y evidencia JFR");
            List<List<String>> analysis = data.pluginAnalysis().stream().limit(data.reportOptions().pdfPlugins())
                    .map(p -> List.of(p.plugin(), p.version(), p.syncTasks() + "/" + p.asyncTasks(),
                            Integer.toString(p.listeners()), p.profileSamples() + "/" + p.totalProfileSamples(),
                            p.assessment())).toList();
            table(List.of("Plugin", "Version", "Tasks S/A", "Listeners", "JFR plugin/total", "Lectura"), analysis,
                    new float[]{.17f, .10f, .11f, .10f, .15f, .37f});
            paragraph("Los frames JFR son evidencia de que el codigo estuvo en stacks muestreados. No prueban por si solos que el plugin cause lag; la comparativa antes/despues ayuda a validar el efecto.", 8.2f, MUTED);
        }

        void packsAndAssets() throws IOException {
            ensure(180);
            section("13", "Datos, recursos y modelos", "Datapacks, resource packs y contenido detectado en disco");
            List<List<String>> packs = data.files().packs().stream().map(p -> List.of(p.path(), Integer.toString(p.entries()),
                    Integer.toString(p.models()), Integer.toString(p.textures()), Integer.toString(p.blockStates()),
                    Integer.toString(p.functions()), Integer.toString(p.recipes()), Integer.toString(p.lootTables()))).toList();
            table(List.of("Pack", "Entradas", "Modelos", "Texturas", "States", "Funciones", "Recetas", "Loot"), packs,
                    new float[]{.31f, .10f, .10f, .10f, .10f, .11f, .09f, .09f});
            subtitle("Funciones de datapack con mayor riesgo estatico");
            List<List<String>> functions = data.files().functions().stream().filter(f -> f.riskScore() > 0)
                    .limit(data.reportOptions().pdfFunctions())
                    .map(f -> List.of(shorten(f.path(), 75), Integer.toString(f.commands()),
                            Integer.toString(f.broadEntitySelectors()), Integer.toString(f.schedules()),
                            Integer.toString(f.worldMutations()), Integer.toString(f.riskScore()), f.notes())).toList();
            table(List.of("Funcion", "Cmd", "@e", "Schedule", "Muta", "Riesgo", "Revision"), functions,
                    new float[]{.33f, .07f, .07f, .10f, .08f, .09f, .26f});
            paragraph("Archivos inventariados: " + data.files().files().size() + ". files.csv contiene los metadatos y file-config-entries.csv guarda una fila por clave y valor. Contrasenas, tokens, credenciales, direcciones y claves privadas se redactan.", 8.5f, MUTED);
        }

        void coverage() throws IOException {
            ensure(275);
            section("15", "Cobertura, precision y anexos", "Que mide BlackBox y donde termina la evidencia disponible");
            bullet("Fuente directa de Paper: TPS, MSPT, tiempos de tick, mundos, jugadores, plugins, comandos, entidades, block entities, datapacks y resource pack configurado.");
            bullet("Correlacion: la densidad y la actividad por chunk ayudan a localizar zonas sospechosas, pero no equivalen a CPU exacta por chunk.");
            bullet("Atribucion: /blackbox profile <segundos> captura stacks reales, GC, locks, archivos y sockets mediante JFR para identificar codigo costoso.");
            bullet("Cliente: FPS, GPU, shaders, mods y RAM del jugador requieren un mod cliente que envie esas metricas de forma explicita.");
            bullet("Hosting: limites del contenedor, perdida de paquetes externa, latencia de disco fisico y red del proveedor requieren metricas del panel o agente del host.");
            bullet("Particulas: Paper no mantiene un inventario global retrospectivo de paquetes de particulas. JFR puede localizar codigo emisor durante un perfil.");
            bullet("Crashes: se analizan los crash-reports del servidor y los .txt de cliente copiados a plugins/BlackBox/client-crash-reports; el cliente no los envia automaticamente.");
            subtitle("Anexos generados junto al PDF");
            table(List.of("Archivo", "Contenido"), List.of(
                    List.of("commands.csv", "Una fila por comando, con propietario, permiso y uso"),
                    List.of("command-aliases.csv", "Una fila por alias y comando relacionado"),
                    List.of("plugins.csv", "Version, estado, clase, tareas y listeners por plugin"),
                    List.of("plugin-authors.csv", "Una fila por autor de plugin"),
                    List.of("plugin-dependencies.csv", "Una fila por dependencia y tipo de relacion"),
                    List.of("worlds.csv", "Configuracion y estado observado de cada mundo"),
                    List.of("datapacks.csv", "Estado, formato y compatibilidad de cada datapack"),
                    List.of("datapack-features.csv", "Una fila por feature declarada por datapack"),
                    List.of("resource-pack.csv", "Resource pack configurado por el servidor"),
                    List.of("files.csv", "Una fila por archivo con tamano, fecha y SHA-256"),
                    List.of("file-config-entries.csv", "Una fila por clave y valor sanitizado de configuracion"),
                    List.of("packs.csv", "Contenido agregado de resource packs y datapacks ZIP"),
                    List.of("functions.csv", "Funciones, comandos, selectores y patrones de riesgo"),
                    List.of("command-blocks.csv", "Posicion, tipo, nombre y comando completo"),
                    List.of("command-executions.csv", "Ejecuciones reales de command blocks notificadas por Paper"),
                    List.of("command-block-analysis.csv", "Frecuencia, riesgo, impacto y solucion por command block"),
                    List.of("entity-lifecycle.csv", "UUID, spawn, retirada, causas y tiempo de vida"),
                    List.of("entity-summary.csv", "Tipos, vida, balance, origen, ubicacion y atribucion"),
                    List.of("chunk-analysis.csv", "Metricas del chunk, centro, TP y estado forceload"),
                    List.of("chunk-entity-types.csv", "Una fila por chunk y tipo de entidad observado"),
                    List.of("chunk-block-entity-types.csv", "Una fila por chunk y tipo de block entity"),
                    List.of("chunk-plugin-tickets.csv", "Una fila por ticket de plugin y chunk"),
                    List.of("hotspots.csv", "Actividad por chunk, centro en bloques y comando /tp"),
                    List.of("chunk-lifecycle.csv", "Carga, duracion, centro en bloques y comando /tp"),
                    List.of("chunk-lifecycle-plugin-tickets.csv", "Una fila por ticket observado y evento de chunk"),
                    List.of("warnings.csv", "Warnings, errores y stacks capturados"),
                    List.of("startup-diagnostics.csv", "Causa, origen y solución sugerida de errores de inicio"),
                    List.of("incidents.csv", "Cronologia, severidad, localizacion, centro y comando /tp"),
                    List.of("session-history.csv", "Una fila por arranque o apagado del servidor"),
                    List.of("session-plugins.csv", "Plugins y versiones presentes en cada evento de sesion"),
                    List.of("session-datapacks.csv", "Datapacks activos en cada evento de sesion"),
                    List.of("plugin-analysis.csv", "Huella operativa y atribucion disponible por JFR"),
                    List.of("profiles.csv", "Perfiles manuales y automaticos iniciados en el periodo"),
                    List.of("profile-io.csv", "Bytes de socket/archivo y GC medidos dentro de cada perfil JFR"),
                    List.of("performance-episodes.csv", "Inicio, peor punto, recuperacion, causa, evidencia, confianza y solucion"),
                    List.of("crash-analysis.csv", "Crash reports encontrados, causa probable y accion verificable"),
                    List.of("server-log-findings.txt", "Warnings, errores y watchdog del log actual de Paper"),
                    List.of("regions.csv", "Cabeceras y salud de todos los archivos .mca"),
                    List.of("disk-chunks.csv", "Todos los chunks presentes en terreno, entidades y POI sin cargarlos"),
                    List.of("report.md", "Representacion tecnica accesible y buscable")
            ), new float[]{.30f, .70f});
        }

        void glossary() throws IOException {
            ensure(560);
            section("16", "Glosario de siglas", "Lectura rapida de las medidas y herramientas del informe");
            table(List.of("Sigla", "Significado", "Para que sirve"), List.of(
                    List.of("TPS", "Ticks por segundo", "Ritmo del servidor; el objetivo habitual es 20."),
                    List.of("MSPT", "Milisegundos por tick", "Coste de cada tick; por encima de 50 ms no se sostienen 20 TPS."),
                    List.of("p50/p95/p99", "Percentiles", "El p95 deja por debajo al 95% de ticks y muestra lentitud frecuente."),
                    List.of("CPU", "Unidad central de proceso", "Tiempo de computacion consumido por Java o por todo el sistema."),
                    List.of("RAM", "Memoria de acceso aleatorio", "Memoria fisica usada por Java, cache y otros procesos."),
                    List.of("JVM", "Maquina virtual de Java", "Proceso que ejecuta Paper y los plugins."),
                    List.of("Heap", "Memoria de objetos Java", "Aloja entidades, chunks, caches y objetos de plugins."),
                    List.of("GC", "Recolector de basura", "Recupera heap; demasiada actividad puede introducir pausas."),
                    List.of("JFR", "Java Flight Recorder", "Registra stacks, CPU, GC, locks y operaciones de archivo/socket."),
                    List.of("I/O / E/S", "Entrada y salida", "Lecturas y escrituras de disco o red."),
                    List.of("R/W", "Lectura / escritura", "Bytes recibidos/leidos y enviados/escritos."),
                    List.of("JAR", "Java Archive", "Archivo que contiene un plugin y sus metadatos."),
                    List.of("API", "Interfaz de programacion", "Contrato entre Paper, plugins y otras dependencias."),
                    List.of("MCA", "Minecraft Anvil region", "Archivo de region que almacena grupos de chunks."),
                    List.of("POI", "Punto de interes", "Datos usados por aldeanos y otras mecanicas del mundo."),
                    List.of("UUID", "Identificador unico universal", "Identifica jugadores y entidades sin depender del nombre."),
                    List.of("CSV", "Valores separados por comas", "Anexo tabular que conserva el detalle completo."),
                    List.of("TP", "Teleport", "Comando listo para viajar al centro del chunk investigado."),
                    List.of("FPS", "Fotogramas por segundo", "Rendimiento visual del cliente; Paper no lo recibe."),
                    List.of("GPU", "Procesador grafico", "Renderiza el cliente; no se puede medir desde el servidor."),
                    List.of("DNS", "Sistema de nombres de dominio", "Resuelve nombres de servicios externos a direcciones de red."),
                    List.of("TCP", "Protocolo de transporte", "Canal fiable usado por conexiones de Minecraft y servicios.")),
                    new float[]{.16f, .30f, .54f});
        }

        void runtimeForensics() throws IOException {
            ensure(190);
            section("08", "Forensica de ejecucion", "Hotspots, ciclo de vida de entidades y errores registrados");
            subtitle("Hotspots geograficos");
            List<List<String>> hotspots = data.hotspots().stream().limit(data.reportOptions().pdfHotspots())
                    .map(h -> List.of(h.event(), chunkNavigation(h.location()), Long.toString(h.count()))).toList();
            table(List.of("Evento", "Chunk / centro / TP", "Cantidad"), hotspots, new float[]{.25f, .52f, .23f});

            subtitle("Ciclo de carga de chunks");
            List<List<String>> chunks = data.chunkLifecycle().stream().limit(data.reportOptions().pdfChunkLifecycle()).map(c -> List.of(chunkNavigation(c.location()),
                    Long.toString(c.loads()), Long.toString(c.unloads()), Long.toString(c.newChunks()),
                    one(c.averageLoadedMs() / 1000d) + " s", one(c.maxLoadedMs() / 1000d) + " s")).toList();
            table(List.of("Chunk / centro / TP", "Load", "Unload", "Nuevos", "Carga media", "Carga max"), chunks,
                    new float[]{.37f, .09f, .09f, .10f, .17f, .18f});

            subtitle("Ciclo de vida de entidades");
            List<List<String>> entities = data.entityLifecycle().stream().limit(data.reportOptions().pdfEntityTypes()).map(e -> List.of(e.type(),
                    e.spawns() + "/" + e.removals() + "/" + e.observedBalance(),
                    entityLifetime(e), e.topSpawnReason() + " / " + e.topRemoveCause() + " / " + e.topAttributedSource(),
                    chunkNavigation(e.topLocation()) + " (" + e.topLocationEvents() + ")",
                    "tick " + e.ticking() + ", persist " + e.persistent() + ", spawner " + e.fromSpawner()
                            + ", named " + e.named() + ", passengers " + e.passengers() + ", tracked " + e.trackedPlayers())).toList();
            table(List.of("Entidad", "Spawn/Remove/Balance", "Vida media/max", "Origen / salida / fuente", "Chunk principal / TP", "Estado e impacto de red"), entities,
                    new float[]{.13f, .13f, .14f, .20f, .22f, .18f});
            paragraph("El balance es spawns menos retiradas observadas, no un censo instantaneo. BLOCK_DISPLAY, ITEM_DISPLAY, TEXT_DISPLAY e INTERACTION aparecen como tipos propios cuando existen.", 8.2f, MUTED);

            subtitle("Warnings y errores agrupados");
            List<List<String>> warnings = data.warnings().stream().limit(data.reportOptions().pdfWarnings()).map(w -> List.of(w.level(),
                    Long.toString(w.count()), shorten(w.source(), 40), shorten(w.message(), 120))).toList();
            table(List.of("Nivel", "N", "Fuente", "Mensaje"), warnings, new float[]{.12f, .08f, .27f, .53f});
        }

        void offlineWorldCensus() throws IOException {
            ensure(170);
            section("14", "Censo completo en disco", "Chunks generados, descargados o cargados, leidos sin activarlos en Paper");
            List<List<String>> regions = data.regions().stream().map(r -> List.of(r.kind(), Long.toString(r.files()),
                    Long.toString(r.chunks()), humanBytes(r.bytes()), Long.toString(r.malformedEntries()))).toList();
            table(List.of("Datos .mca", "Archivos", "Chunks presentes", "Tamano", "Entradas invalidas"), regions,
                    new float[]{.24f, .15f, .23f, .18f, .20f});
            paragraph("El censo lee las cabeceras Anvil y no carga los chunks. disk-chunks.csv enumera cada coordenada presente en archivos de terreno, entidades y puntos de interes. Un chunk no generado no existe en disco y el espacio teorico de Minecraft es practicamente ilimitado.", 8.5f, MUTED);
        }

        void finish() throws IOException {
            if (stream != null) stream.close();
            stream = null;
            renderToc();
            addBookmarks();
            int pages = document.getNumberOfPages();
            for (int i = 1; i < pages; i++) {
                PDPage target = document.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(document, target,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    footer.setStrokingColor(LINE);
                    footer.moveTo(MARGIN, 30);
                    footer.lineTo(PDRectangle.A4.getWidth() - MARGIN, 30);
                    footer.stroke();
                    drawText(footer, messages.text("BlackBox  |  Informe confidencial del servidor"), REGULAR, 7, MARGIN, 17, MUTED);
                    String number = (i + 1) + " / " + pages;
                    drawText(footer, number, BOLD, 7, PDRectangle.A4.getWidth() - MARGIN - width(BOLD, 7, number), 17, MUTED);
                    drawText(footer, messages.text("TPS ticks/s  |  MSPT ms/tick  |  JVM Java  |  GC memoria  |  JFR perfil  |  E/S disco-red"),
                            REGULAR, 5.8f, MARGIN, 7, MUTED);
                }
            }
        }

        private void section(String number, String title, String subtitle) throws IOException {
            ensure(freshPage ? 62 : 78);
            if (!freshPage) y -= 14;
            freshPage = false;
            sections.add(new SectionEntry(number, title, page));
            text(number, BOLD, 9, MARGIN, y, BLUE);
            text(title, BOLD, 19, MARGIN + 31, y - 4, NAVY);
            y -= 22;
            text(subtitle, REGULAR, 8.5f, MARGIN + 31, y, MUTED);
            y -= 28;
        }

        private void subtitle(String value) throws IOException {
            ensure(32);
            y -= 7;
            text(value, BOLD, 11, MARGIN, y, NAVY);
            y -= 20;
        }

        private void metricCards(List<Metric> metrics) throws IOException {
            ensure(170);
            float gap = 10;
            float cardWidth = (CONTENT_WIDTH - gap * 2) / 3;
            for (int i = 0; i < metrics.size(); i++) {
                if (i == 3) y -= 82;
                int column = i % 3;
                float x = MARGIN + column * (cardWidth + gap);
                Metric m = metrics.get(i);
                fill(x, y - 68, cardWidth, 66, LIGHT);
                fill(x, y - 68, 4, 66, m.color());
                text(m.label().toUpperCase(Locale.ROOT), BOLD, 7, x + 14, y - 20, MUTED);
                text(m.value(), BOLD, 20, x + 14, y - 46, NAVY);
                text(m.unit(), REGULAR, 7, x + 14, y - 59, MUTED);
            }
            y -= 86;
        }

        private void lineChart(String title, List<ServerSample> samples, ToDoubleFunction<ServerSample> metric,
                               double min, double max, Color color, double threshold) throws IOException {
            ensure(150);
            text(title, BOLD, 9, MARGIN, y, NAVY);
            float chartY = y - 118;
            float chartH = 92;
            fill(MARGIN, chartY, CONTENT_WIDTH, chartH, LIGHT);
            stream.setStrokingColor(LINE);
            for (int i = 1; i < 4; i++) {
                float gy = chartY + chartH * i / 4f;
                stream.moveTo(MARGIN, gy); stream.lineTo(MARGIN + CONTENT_WIDTH, gy); stream.stroke();
            }
            if (threshold >= min && threshold <= max) {
                float ty = chartY + (float) ((threshold - min) / Math.max(.0001, max - min) * chartH);
                stream.setStrokingColor(AMBER);
                stream.moveTo(MARGIN, ty); stream.lineTo(MARGIN + CONTENT_WIDTH, ty); stream.stroke();
            }
            if (!samples.isEmpty()) {
                stream.setStrokingColor(color);
                stream.setLineWidth(1.6f);
                int stride = Math.max(1, samples.size() / 300);
                boolean first = true;
                int rendered = (samples.size() + stride - 1) / stride;
                int point = 0;
                for (int i = 0; i < samples.size(); i += stride) {
                    double value = Math.max(min, Math.min(max, metric.applyAsDouble(samples.get(i))));
                    float x = MARGIN + (rendered <= 1 ? 0 : CONTENT_WIDTH * point / (rendered - 1f));
                    float py = chartY + (float) ((value - min) / Math.max(.0001, max - min) * chartH);
                    if (first) { stream.moveTo(x, py); first = false; } else stream.lineTo(x, py);
                    point++;
                }
                stream.stroke();
            }
            text(one(max), REGULAR, 6.5f, MARGIN + 4, chartY + chartH - 9, MUTED);
            text(one(min), REGULAR, 6.5f, MARGIN + 4, chartY + 4, MUTED);
            y = chartY - 18;
        }

        private void findingCard(DiagnosticFinding finding, Color color) throws IOException {
            List<String> title = wrap(finding.title(), BOLD, 11, CONTENT_WIDTH - 112);
            List<String> location = wrap("Donde: " + finding.location(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            List<String> evidence = wrap("Evidencia: " + finding.evidence(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            List<String> cause = wrap("Causa probable: " + finding.probableCause(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            List<String> impact = wrap("Impacto: " + finding.impact(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            List<String> time = wrap("Tiempo observado: " + finding.observedTime(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            List<String> action = wrap("Solucion: " + finding.action(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            List<String> verification = wrap("Como verificar: " + finding.verification(), REGULAR, 8.2f, CONTENT_WIDTH - 32);
            int bodyLines = location.size() + evidence.size() + cause.size() + impact.size()
                    + time.size() + action.size() + verification.size();
            float height = 48 + title.size() * 12 + bodyLines * 10 + 18;
            ensure(height + 10);
            fill(MARGIN, y - height, CONTENT_WIDTH, height, LIGHT);
            fill(MARGIN, y - height, 5, height, color);
            text(finding.severity().name(), BOLD, 7, MARGIN + 17, y - 18, color);
            float titleY = y - 20;
            for (String line : title) { text(line, BOLD, 11, MARGIN + 84, titleY, NAVY); titleY -= 12; }
            float lineY = titleY - 8;
            for (List<String> part : List.of(location, evidence, cause, impact, time, action, verification)) {
                for (String line : part) { text(line, REGULAR, 8.2f, MARGIN + 17, lineY, TEXT); lineY -= 10; }
                lineY -= 3;
            }
            y -= height + 16;
        }

        private void incidentRow(PdfReportData.IncidentRow incident) throws IOException {
            Color color = incident.severity() == DiagnosticFinding.Severity.CRITICAL ? RED
                    : incident.severity() == DiagnosticFinding.Severity.WARNING ? AMBER : BLUE;
            String heading = date.format(incident.timestamp()) + "  |  " + incident.signal();
            List<String> detail = wrap(incident.detail(), REGULAR, 8f, CONTENT_WIDTH - 155);
            List<String> location = wrap(chunkNavigation(incident.location()), BOLD, 7.2f, 122);
            int bodyLines = Math.max(detail.size(), location.size());
            float height = 41 + Math.max(0, bodyLines - 1) * 10;
            ensure(height + 5);
            fill(MARGIN, y - height, CONTENT_WIDTH, height, new Color(248, 250, 253));
            fill(MARGIN, y - height, 5, height, color);
            text(incident.severity().name(), BOLD, 6.8f, MARGIN + 15, y - 15, color);
            text(shorten(heading, 70), BOLD, 8.5f, MARGIN + 78, y - 15, NAVY);
            float locationY = y - 29;
            for (String line : location) { text(line, BOLD, 7.2f, MARGIN + 15, locationY, MUTED); locationY -= 10; }
            float py = y - 29;
            for (String line : detail) { text(line, REGULAR, 8f, MARGIN + 145, py, TEXT); py -= 10; }
            y -= height + 8;
        }

        private void episodeCard(PdfReportData.PerformanceEpisode episode) throws IOException {
            Color color = episode.severity() == DiagnosticFinding.Severity.CRITICAL ? RED : AMBER;
            String recovered = episode.recoveredAt() == null ? "sin recuperacion dentro del informe"
                    : date.format(episode.recoveredAt()) + " (MSPT posterior " + one(episode.recoveryMspt()) + ")";
            List<String> cause = wrap("Causa probable: " + episode.probableCause(), BOLD, 8.6f, CONTENT_WIDTH - 32);
            List<String> evidence = wrap("Evidencia: " + episode.evidence(), REGULAR, 8f, CONTENT_WIDTH - 32);
            List<String> action = wrap("Como corregir y validar: " + episode.recommendation(), REGULAR, 8f, CONTENT_WIDTH - 32);
            List<String> location = wrap("Donde: " + chunkNavigation(episode.location()), REGULAR, 8f, CONTENT_WIDTH - 32);
            int lines = cause.size() + evidence.size() + action.size() + location.size();
            float height = 82 + lines * 9.5f;
            ensure(height + 10);
            fill(MARGIN, y - height, CONTENT_WIDTH, height, LIGHT);
            fill(MARGIN, y - height, 5, height, color);
            text(episode.severity().name() + "  " + episode.trigger(), BOLD, 8, MARGIN + 16, y - 17, color);
            text("Confianza: " + episode.confidence(), BOLD, 7.2f, MARGIN + 258, y - 17, MUTED);
            text("Inicio " + date.format(episode.startedAt()) + "  |  peor " + date.format(episode.worstAt()),
                    REGULAR, 7.7f, MARGIN + 16, y - 34, TEXT);
            text("TPS min " + one(episode.minTps()) + "  |  p95 max " + one(episode.maxTickP95Ms())
                            + " ms  |  MSPT base/durante " + one(episode.baselineMspt()) + "/" + one(episode.incidentMspt()),
                    REGULAR, 7.7f, MARGIN + 16, y - 48, TEXT);
            text("Recuperacion: " + recovered, REGULAR, 7.7f, MARGIN + 16, y - 62, TEXT);
            float py = y - 79;
            for (List<String> part : List.of(cause, location, evidence, action)) {
                PDFont font = part == cause ? BOLD : REGULAR;
                for (String line : part) { text(line, font, 8f, MARGIN + 16, py, TEXT); py -= 9.5f; }
                py -= 2;
            }
            y -= height + 11;
        }

        private void commandBlockCard(PdfReportData.CommandBlockRow row) throws IOException {
            Color color = row.risk().equals("CRITICAL") ? RED : row.risk().equals("WARNING") ? AMBER : BLUE;
            List<String> command = wrap("Comando: " + row.command(), MONO, 7.5f, CONTENT_WIDTH - 32);
            List<String> cadence = wrap("Frecuencia: " + row.cadence(), REGULAR, 8f, CONTENT_WIDTH - 32);
            List<String> impact = wrap("Impacto: " + row.impact(), REGULAR, 8f, CONTENT_WIDTH - 32);
            List<String> solution = wrap("Solucion: " + row.recommendation(), REGULAR, 8f, CONTENT_WIDTH - 32);
            String state = "Tipo " + row.material() + " | condicional " + row.conditional() + " | facing "
                    + row.facing() + " | powered " + row.poweredObservations() + "/" + row.observations();
            List<String> status = wrap(state, REGULAR, 7.7f, CONTENT_WIDTH - 32);
            String time = "Inventariado: " + observed(row.firstSeen(), row.lastSeen()) + " | ejecuciones: "
                    + row.executions() + " | " + commandTp(row);
            List<String> timing = wrap(time, REGULAR, 7.7f, CONTENT_WIDTH - 32);
            int lines = command.size() + cadence.size() + impact.size() + solution.size() + status.size() + timing.size();
            float height = 47 + lines * 9.5f + 14;
            ensure(height + 10);
            fill(MARGIN, y - height, CONTENT_WIDTH, height, LIGHT);
            fill(MARGIN, y - height, 5, height, color);
            text(row.risk(), BOLD, 7, MARGIN + 17, y - 18, color);
            text(shorten(row.position() + "  " + row.name(), 75), BOLD, 10, MARGIN + 82, y - 19, NAVY);
            float py = y - 38;
            for (List<String> part : List.of(status, timing, command, cadence, impact, solution)) {
                PDFont font = part == command ? MONO : REGULAR;
                float size = part == command ? 7.5f : 8f;
                for (String line : part) { text(line, font, size, MARGIN + 17, py, TEXT); py -= 9.5f; }
                py -= 2;
            }
            y -= height + 12;
        }

        private void callout(Color color, String title, String body) throws IOException {
            List<String> lines = wrap(body, REGULAR, 8.5f, CONTENT_WIDTH - 32);
            float h = 38 + lines.size() * 10;
            ensure(h);
            fill(MARGIN, y - h, CONTENT_WIDTH, h, LIGHT);
            fill(MARGIN, y - h, 5, h, color);
            text(title, BOLD, 9, MARGIN + 17, y - 19, color);
            float py = y - 37;
            for (String line : lines) { text(line, REGULAR, 8.5f, MARGIN + 17, py, TEXT); py -= 10; }
            y -= h + 10;
        }

        private void paragraph(String value, float size, Color color) throws IOException {
            List<String> lines = wrap(value, REGULAR, size, CONTENT_WIDTH);
            ensure(lines.size() * (size + 3) + 8);
            for (String line : lines) { text(line, REGULAR, size, MARGIN, y, color); y -= size + 3; }
            y -= 6;
        }

        private void bullet(String value) throws IOException {
            List<String> lines = wrap(value, REGULAR, 8.5f, CONTENT_WIDTH - 20);
            ensure(lines.size() * 11 + 5);
            fill(MARGIN + 1, y - 2, 5, 5, BLUE);
            float py = y;
            for (String line : lines) { text(line, REGULAR, 8.5f, MARGIN + 18, py, TEXT); py -= 11; }
            y = py - 4;
        }

        private void table(List<String> headers, List<List<String>> rows, float[] ratios) throws IOException {
            if (headers.size() != ratios.length) throw new IllegalArgumentException("Columnas y anchos incompatibles");
            float[] widths = new float[ratios.length];
            for (int i = 0; i < ratios.length; i++) widths[i] = CONTENT_WIDTH * ratios[i];
            drawTableHeader(headers, widths);
            boolean alternate = false;
            for (List<String> row : rows) {
                List<List<String>> cells = new ArrayList<>();
                int lineCount = 1;
                for (int i = 0; i < widths.length; i++) {
                    String value = i < row.size() ? row.get(i) : "";
                    List<String> lines = wrap(value, REGULAR, 7.2f, widths[i] - 10);
                    if (lines.size() > 5) {
                        lines = new ArrayList<>(lines.subList(0, 5));
                        int last = lines.size() - 1;
                        lines.set(last, shorten(lines.get(last), Math.max(4, lines.get(last).length() - 3)) + "...");
                    }
                    cells.add(lines);
                    lineCount = Math.max(lineCount, lines.size());
                }
                float height = 9 + lineCount * 8.6f;
                if (y - height < 48) {
                    newContentPage();
                    drawTableHeader(headers, widths);
                }
                if (alternate) fill(MARGIN, y - height, CONTENT_WIDTH, height, new Color(248, 250, 253));
                stream.setStrokingColor(LINE);
                stream.moveTo(MARGIN, y - height); stream.lineTo(MARGIN + CONTENT_WIDTH, y - height); stream.stroke();
                float x = MARGIN;
                for (int i = 0; i < cells.size(); i++) {
                    float ty = y - 13;
                    for (String line : cells.get(i)) { text(line, REGULAR, 7.2f, x + 5, ty, TEXT); ty -= 8.6f; }
                    x += widths[i];
                }
                y -= height;
                alternate = !alternate;
            }
            y -= 14;
        }

        private void drawTableHeader(List<String> headers, float[] widths) throws IOException {
            List<List<String>> cells = new ArrayList<>();
            int lineCount = 1;
            for (int i = 0; i < headers.size(); i++) {
                List<String> lines = wrap(headers.get(i).toUpperCase(Locale.ROOT), BOLD, 6.4f, widths[i] - 10);
                if (lines.size() > 2) lines = lines.subList(0, 2);
                cells.add(lines);
                lineCount = Math.max(lineCount, lines.size());
            }
            float height = 10 + lineCount * 8f;
            ensure(height + 3);
            fill(MARGIN, y - height, CONTENT_WIDTH, height, NAVY);
            float x = MARGIN;
            for (int i = 0; i < headers.size(); i++) {
                float ty = y - 12;
                for (String line : cells.get(i)) { text(line, BOLD, 6.4f, x + 5, ty, Color.WHITE); ty -= 8; }
                x += widths[i];
            }
            y -= height;
        }

        private void renderToc() throws IOException {
            try (PDPageContentStream toc = new PDPageContentStream(document, tocPage,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                toc.setNonStrokingColor(Color.WHITE);
                toc.addRect(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                toc.fill();
                toc.setNonStrokingColor(NAVY);
                toc.addRect(0, 798, PDRectangle.A4.getWidth(), 44);
                toc.fill();
                drawText(toc, "BLACKBOX", BOLD, 10, MARGIN, 815, CYAN);
                drawText(toc, messages.text("INDICE DEL INFORME"), BOLD, 22, MARGIN, 755, NAVY);
                drawText(toc, messages.text("Selecciona un marcador del visor PDF para navegar directamente."), REGULAR, 9, MARGIN, 733, MUTED);
                float py = 690;
                for (SectionEntry entry : sections) {
                    drawText(toc, entry.number(), BOLD, 8, MARGIN, py, BLUE);
                    drawText(toc, messages.text(entry.title()), BOLD, 10, MARGIN + 34, py, TEXT);
                    int pageNumber = document.getPages().indexOf(entry.page()) + 1;
                    String number = Integer.toString(pageNumber);
                    drawText(toc, number, BOLD, 9, PDRectangle.A4.getWidth() - MARGIN - width(BOLD, 9, number), py, MUTED);
                    toc.setStrokingColor(LINE);
                    toc.moveTo(MARGIN + 34, py - 8);
                    toc.lineTo(PDRectangle.A4.getWidth() - MARGIN, py - 8);
                    toc.stroke();
                    py -= 38;
                }
            }
        }

        private void addBookmarks() {
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            for (SectionEntry entry : sections) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(entry.number() + "  " + entry.title());
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(entry.page());
                item.setDestination(destination);
                outline.addLast(item);
            }
            outline.openNode();
        }

        private void ensure(float required) throws IOException {
            if (y - required < 46) newContentPage();
        }

        private void fill(float x, float y, float width, float height, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.addRect(x, y, width, height);
            stream.fill();
        }

        private void text(String value, PDFont font, float size, float x, float y, Color color) throws IOException {
            drawText(stream, messages.text(value), font, size, x, y, color);
        }
    }

    private static void drawText(PDPageContentStream stream, String value, PDFont font, float size,
                                 float x, float y, Color color) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(safe(value));
        stream.endText();
    }

    private static List<String> wrap(String input, PDFont font, float size, float maxWidth) {
        String clean = safe(input);
        if (clean.isBlank()) return List.of("");
        List<String> result = new ArrayList<>();
        for (String paragraph : clean.split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(font, size, candidate) <= maxWidth) {
                    line.setLength(0); line.append(candidate);
                } else {
                    if (!line.isEmpty()) result.add(line.toString());
                    line.setLength(0);
                    if (width(font, size, word) <= maxWidth) line.append(word);
                    else {
                        String remaining = word;
                        while (!remaining.isEmpty()) {
                            int cut = remaining.length();
                            while (cut > 1 && width(font, size, remaining.substring(0, cut)) > maxWidth) cut--;
                            result.add(remaining.substring(0, cut));
                            remaining = remaining.substring(cut);
                        }
                    }
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
        }
        return result.isEmpty() ? List.of("") : result;
    }

    private static float width(PDFont font, float size, String value) {
        try { return font.getStringWidth(safe(value)) / 1000f * size; }
        catch (IOException ignored) { return value.length() * size * .52f; }
    }

    private static String safe(String value) {
        if (value == null) return "";
        String normalized = value.replace('\u2014', '-').replace('\u2013', '-').replace('\u2026', '.')
                .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2022', '-').replace('\n', ' ').replace('\r', ' ');
        StringBuilder out = new StringBuilder(normalized.length());
        for (char c : normalized.toCharArray()) out.append(c >= 32 && c <= 255 ? c : '?');
        return out.toString();
    }

    private static String commandReview(List<String> row) {
        if (row.size() < 8) return "Fila incompleta";
        String type = row.get(5).toLowerCase(Locale.ROOT);
        String command = row.get(7).toLowerCase(Locale.ROOT);
        List<String> notes = new ArrayList<>();
        boolean broad = command.contains("@e") && !command.contains("distance=")
                && !command.contains("dx=") && !command.contains("limit=");
        if (type.contains("repeating") && broad) notes.add("@e amplio en repetitivo");
        else if (broad) notes.add("@e sin limite espacial");
        if (command.contains("forceload")) notes.add("forceload");
        if (command.contains("spreadplayers")) notes.add("spreadplayers");
        return notes.isEmpty() ? "Sin patron evidente" : String.join("; ", notes);
    }

    private static List<String> metricRow(String label, List<ServerSample> samples,
                                          ToDoubleFunction<ServerSample> metric, String suffix) {
        var stats = samples.stream().mapToDouble(metric).filter(v -> Double.isFinite(v) && v >= 0).summaryStatistics();
        if (stats.getCount() == 0) return List.of(label, "n/a", "n/a", "n/a");
        return List.of(label, one(stats.getMin()) + suffix, one(stats.getAverage()) + suffix, one(stats.getMax()) + suffix);
    }

    private static List<String> byteMetricRow(String label, List<ServerSample> samples,
                                              ToLongFunction<ServerSample> metric) {
        var stats = samples.stream().mapToLong(metric).filter(v -> v >= 0).summaryStatistics();
        if (stats.getCount() == 0) return List.of(label, "n/a", "n/a", "n/a");
        return List.of(label, humanBytes(stats.getMin()), humanBytes((long) stats.getAverage()), humanBytes(stats.getMax()));
    }

    private static List<String> row(String name, long total, double rate) {
        return List.of(name, Long.toString(total), one(rate));
    }

    private static String value(List<ServerSample> samples, ToDoubleFunction<ServerSample> metric, Mode mode) {
        var values = samples.stream().mapToDouble(metric).filter(v -> Double.isFinite(v) && v >= 0);
        double result = mode == Mode.MIN ? values.min().orElse(Double.NaN) : values.max().orElse(Double.NaN);
        return Double.isFinite(result) ? one(result) : "n/a";
    }

    private static long counterIncrease(List<ServerSample> samples, ToLongFunction<ServerSample> metric) {
        long total = 0, previous = -1;
        for (ServerSample sample : samples.stream().sorted(Comparator.comparing(ServerSample::timestamp)).toList()) {
            long current = metric.applyAsLong(sample);
            if (current < 0) continue;
            if (previous >= 0 && current >= previous) total += current - previous;
            previous = current;
        }
        return total;
    }

    private static long maxLong(List<ServerSample> samples, ToLongFunction<ServerSample> metric) {
        return samples.stream().mapToLong(metric).filter(v -> v >= 0).max().orElse(-1);
    }

    private static long minLong(List<ServerSample> samples, ToLongFunction<ServerSample> metric) {
        return samples.stream().mapToLong(metric).filter(v -> v >= 0).min().orElse(-1);
    }

    private static double percentUsed(long total, long free) {
        return total > 0 && free >= 0 ? 100d * (total - free) / total : -1;
    }

    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String humanBytes(long value) {
        if (value < 0) return "n/a";
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return one(value / 1024d) + " KiB";
        if (value < 1024L * 1024 * 1024) return one(value / (1024d * 1024d)) + " MiB";
        return one(value / (1024d * 1024d * 1024d)) + " GiB";
    }
    private static String humanMillis(long value) {
        if (value < 1000) return value + " ms";
        if (value < 60_000) return one(value / 1000d) + " s";
        if (value < 3_600_000) return one(value / 60_000d) + " min";
        if (value < 86_400_000) return one(value / 3_600_000d) + " h";
        return one(value / 86_400_000d) + " d";
    }
    private static String chunkNavigation(String location) {
        ChunkLocation chunk = ChunkLocation.parse(location);
        return chunk == null ? location : chunk.navigation();
    }
    private static String commandTp(PdfReportData.CommandBlockRow row) {
        return "/tp @s " + row.x() + " " + (row.y() + 1) + " " + row.z();
    }
    private static String observed(java.time.Instant first, java.time.Instant last) {
        if (first == null || last == null) return "sin intervalo suficiente";
        return DATE.format(first) + " a " + DATE.format(last) + " ("
                + humanMillis(Math.max(0, java.time.Duration.between(first, last).toMillis())) + ")";
    }
    private static String entityLifetime(PdfReportData.EntityLifecycleRow row) {
        if (row.averageObservedLifetimeMs() > 0 || row.maxObservedLifetimeMs() > 0)
            return humanMillis((long) row.averageObservedLifetimeMs()) + " / " + humanMillis(row.maxObservedLifetimeMs());
        return one(row.averageTicksLived() / 20d) + " s / " + one(row.maxTicksLived() / 20d) + " s";
    }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "..."; }
    private static String human(Duration duration) {
        if (duration.toDays() > 0) return duration.toDays() + " dia(s)";
        if (duration.toHours() > 0) return duration.toHours() + " hora(s)";
        return duration.toMinutes() + " minuto(s)";
    }

    private enum Mode { MIN, MAX }
    private record Metric(String label, String value, String unit, Color color) {}
    private record SectionEntry(String number, String title, PDPage page) {}
}
