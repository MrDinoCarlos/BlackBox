package es.mrdino.blackbox.profile;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.i18n.Messages;
import es.mrdino.blackbox.model.ServerSample;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.storage.DiskQuota;
import es.mrdino.blackbox.util.Csv;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ProfileService implements AutoCloseable {
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private final Plugin plugin;
    private final TelemetryStore store;
    private final Path root;
    private final int maxSeconds;
    private final int topMethods;
    private final BlackBoxSettings.Profiling options;
    private final long maxDiskBytes;
    private final Messages messages;
    private final AtomicReference<Session> active = new AtomicReference<>();
    private int consecutiveBadSamples;
    private Instant automaticCooldownUntil = Instant.EPOCH;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "blackbox-jfr");
        thread.setDaemon(true);
        return thread;
    });

    public ProfileService(Plugin plugin, Path dataFolder, TelemetryStore store,
                          BlackBoxSettings.Profiling options, long maxDiskBytes) throws IOException {
        this(plugin, dataFolder, store, options, maxDiskBytes, Messages.spanish());
    }

    public ProfileService(Plugin plugin, Path dataFolder, TelemetryStore store,
                          BlackBoxSettings.Profiling options, long maxDiskBytes, Messages messages) throws IOException {
        this.plugin = plugin;
        this.store = store;
        this.root = dataFolder.resolve("profiles");
        this.maxSeconds = options.maxSeconds();
        this.topMethods = options.topMethods();
        this.options = options;
        this.maxDiskBytes = maxDiskBytes;
        this.messages = messages == null ? Messages.english() : messages;
        Files.createDirectories(root);
        DiskQuota.files(root, maxDiskBytes, Set.of());
    }

    public StartResult start(int requestedSeconds) throws Exception {
        return start(requestedSeconds, "manual", "Solicitado por un administrador");
    }

    private StartResult start(int requestedSeconds, String trigger, String reason) throws Exception {
        int seconds = Math.max(10, Math.min(maxSeconds, requestedSeconds));
        Session previous = active.get();
        if (previous != null && previous.recording().getState() == RecordingState.RUNNING) {
            throw new IllegalStateException("Ya hay un perfil activo hasta " + previous.endsAt());
        }
        Instant now = Instant.now();
        String stem = "profile-" + NAME_TIME.format(now);
        Path jfr = root.resolve(stem + ".jfr");
        Path analysis = root.resolve(stem + "-analysis.md");
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName("BlackBox " + stem);
        recording.setToDisk(true);
        recording.setDumpOnExit(true);
        recording.setDestination(jfr);
        recording.setDuration(Duration.ofSeconds(seconds));
        Session session = new Session(recording, jfr, analysis, now, now.plusSeconds(seconds), seconds,
                trigger, reason, pluginPrefixes(), new AtomicBoolean());
        if (!active.compareAndSet(previous, session)) {
            recording.close();
            throw new IllegalStateException("Otro perfil acaba de comenzar");
        }
        recording.start();
        store.append("profiles", "timestamp,event,profile_id,trigger,reason,seconds,path", now,
                Csv.row(now.toEpochMilli(), "started", stem, trigger, reason, seconds, jfr.getFileName()));
        executor.schedule(() -> finish(session), seconds + 2L, TimeUnit.SECONDS);
        return new StartResult(jfr, analysis, seconds, session.endsAt());
    }

    public synchronized void observe(ServerSample sample) {
        if (!options.automaticEnabled()) return;
        boolean badTps = sample.tps1m() >= 0 && sample.tps1m() <= options.automaticTpsThreshold();
        boolean badTick = sample.tickP95Ms() >= options.automaticTickP95Ms();
        consecutiveBadSamples = badTps || badTick ? consecutiveBadSamples + 1 : 0;
        Instant now = Instant.now();
        if (consecutiveBadSamples < options.automaticConsecutiveSamples() || now.isBefore(automaticCooldownUntil)
                || isActive()) return;
        String reason = "TPS=" + one(sample.tps1m()) + ", tick p95=" + one(sample.tickP95Ms()) + " ms tras "
                + consecutiveBadSamples + " muestras";
        try {
            start(options.automaticSeconds(), "automatic", reason);
            automaticCooldownUntil = now.plusSeconds(options.automaticCooldownMinutes() * 60L);
            consecutiveBadSamples = 0;
            plugin.getLogger().warning("Lag sostenido detectado; perfil JFR automatico iniciado: " + reason);
        } catch (Exception error) {
            plugin.getLogger().warning("No se pudo iniciar el perfil JFR automatico: " + error.getMessage());
        }
    }

    public boolean isActive() {
        Session session = active.get();
        return session != null && session.recording().getState() == RecordingState.RUNNING;
    }

    public Instant endsAt() {
        Session session = active.get();
        return session == null ? Instant.EPOCH : session.endsAt();
    }

    private void finish(Session session) {
        if (!session.finished().compareAndSet(false, true)) return;
        try {
            RecordingState state = session.recording().getState();
            if (state == RecordingState.RUNNING) session.recording().stop();
            session.recording().close();
            analyze(session);
            Instant now = Instant.now();
            store.append("profiles", "timestamp,event,profile_id,trigger,reason,seconds,path", now,
                    Csv.row(now.toEpochMilli(), "completed", stem(session.jfr()), session.trigger(),
                            session.reason(), session.seconds(), session.jfr().getFileName()));
            plugin.getLogger().info("Perfil JFR completado: " + session.jfr());
        } catch (Exception error) {
            plugin.getLogger().warning("No se pudo finalizar/analizar el perfil JFR: " + error.getMessage());
        } finally {
            active.compareAndSet(session, null);
            enforceQuota();
        }
    }

    private void analyze(Session session) throws IOException {
        Map<String, Long> methods = new HashMap<>();
        Map<String, Long> plugins = new HashMap<>();
        long executionSamples = 0;
        long socketReadBytes = 0;
        long socketWriteBytes = 0;
        long fileReadBytes = 0;
        long fileWriteBytes = 0;
        long gcEvents = 0;
        long socketIoNanos = 0;
        long maxSocketIoNanos = 0;
        long fileIoNanos = 0;
        long maxFileIoNanos = 0;
        try (RecordingFile recording = new RecordingFile(session.jfr())) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample")) {
                    executionSamples++;
                    if (event.getStackTrace() == null) continue;
                    String firstUseful = null;
                    for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                        String className = frame.getMethod().getType().getName();
                        String method = className + "." + frame.getMethod().getName();
                        if (firstUseful == null && !isPlatform(className)) firstUseful = method;
                        for (var prefix : session.pluginPrefixes().entrySet()) {
                            if (className.startsWith(prefix.getValue())) {
                                plugins.merge(prefix.getKey(), 1L, Long::sum);
                                break;
                            }
                        }
                    }
                    if (firstUseful != null) methods.merge(firstUseful, 1L, Long::sum);
                } else if (type.equals("jdk.SocketRead")) {
                    socketReadBytes += longField(event, "bytesRead");
                    socketIoNanos += event.getDuration().toNanos();
                    maxSocketIoNanos = Math.max(maxSocketIoNanos, event.getDuration().toNanos());
                } else if (type.equals("jdk.SocketWrite")) {
                    socketWriteBytes += longField(event, "bytesWritten");
                    socketIoNanos += event.getDuration().toNanos();
                    maxSocketIoNanos = Math.max(maxSocketIoNanos, event.getDuration().toNanos());
                } else if (type.equals("jdk.FileRead")) {
                    fileReadBytes += longField(event, "bytesRead");
                    fileIoNanos += event.getDuration().toNanos();
                    maxFileIoNanos = Math.max(maxFileIoNanos, event.getDuration().toNanos());
                } else if (type.equals("jdk.FileWrite")) {
                    fileWriteBytes += longField(event, "bytesWritten");
                    fileIoNanos += event.getDuration().toNanos();
                    maxFileIoNanos = Math.max(maxFileIoNanos, event.getDuration().toNanos());
                }
                else if (type.equals("jdk.GarbageCollection")) gcEvents++;
            }
        }
        StringBuilder out = new StringBuilder("# Analisis de perfil BlackBox\n\n")
                .append("Archivo JFR: `").append(session.jfr().getFileName()).append("`  \n")
                .append("Muestras de CPU: **").append(executionSamples).append("** · eventos GC: **").append(gcEvents).append("**  \n")
                .append("Socket leido/escrito: **").append(bytes(socketReadBytes)).append(" / ").append(bytes(socketWriteBytes)).append("**  \n")
                .append("Archivos leidos/escritos: **").append(bytes(fileReadBytes)).append(" / ").append(bytes(fileWriteBytes)).append("**  \n")
                .append("Tiempo E/S socket total/max: **").append(millis(socketIoNanos)).append(" / ").append(millis(maxSocketIoNanos)).append("**  \n")
                .append("Tiempo E/S archivo total/max: **").append(millis(fileIoNanos)).append(" / ").append(millis(maxFileIoNanos)).append("**\n\n")
                .append("## Paquetes de plugins presentes en stacks\n\n| Plugin | Muestras con frames del plugin |\n|---|---:|\n");
        sorted(plugins).forEach(e -> out.append('|').append(e.getKey()).append('|').append(e.getValue()).append("|\n"));
        out.append("\nUna muestra puede contener frames de varios plugins; esta tabla indica presencia, no reparte porcentajes exclusivos.\n\n")
                .append("## Metodos de aplicacion en la cima del stack\n\n| Metodo | Muestras |\n|---|---:|\n");
        sorted(methods).stream().limit(topMethods).forEach(e -> out.append('|').append(e.getKey().replace("|", "\\|"))
                .append('|').append(e.getValue()).append("|\n"));
        out.append("\nAbre el `.jfr` en IntelliJ Profiler o JDK Mission Control para flame graphs, hilos, locks, GC y llamadas completas.\n");
        Files.writeString(session.analysis(), messages.text(out.toString()), StandardCharsets.UTF_8);
        Instant now = Instant.now();
        for (Map.Entry<String, Long> entry : plugins.entrySet()) {
            store.append("profile-plugins", "timestamp,profile_id,trigger,plugin,samples,total_execution_samples", now,
                    Csv.row(now.toEpochMilli(), stem(session.jfr()), session.trigger(), entry.getKey(),
                            entry.getValue(), executionSamples));
        }
        store.append("profile-io",
                "timestamp,started_at,completed_at,profile_id,trigger,socket_read_bytes,socket_write_bytes,file_read_bytes,file_write_bytes,gc_events,socket_io_nanos,max_socket_io_nanos,file_io_nanos,max_file_io_nanos",
                now, Csv.row(now.toEpochMilli(), session.startedAt().toEpochMilli(), now.toEpochMilli(),
                        stem(session.jfr()), session.trigger(), socketReadBytes, socketWriteBytes,
                        fileReadBytes, fileWriteBytes, gcEvents, socketIoNanos, maxSocketIoNanos,
                        fileIoNanos, maxFileIoNanos));
    }

    private Map<String, String> pluginPrefixes() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            String main = installed.getClass().getName();
            int lastDot = main.lastIndexOf('.');
            if (lastDot > 0) result.put(installed.getName(), main.substring(0, lastDot + 1));
        }
        return Map.copyOf(result);
    }

    private static List<Map.Entry<String, Long>> sorted(Map<String, Long> map) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        return entries;
    }

    private static long longField(RecordedEvent event, String field) {
        return event.getEventType().getFields().stream().anyMatch(v -> v.getName().equals(field)) ? event.getLong(field) : 0;
    }

    private static boolean isPlatform(String name) {
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("org.bukkit.") || name.startsWith("io.papermc.")
                || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.");
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024) return String.format("%.1f KiB", value / 1024.0);
        return String.format("%.1f MiB", value / (1024.0 * 1024));
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f ms", nanos / 1_000_000d);
    }

    private static String one(double value) { return String.format(java.util.Locale.ROOT, "%.1f", value); }
    private static String stem(Path jfr) {
        String name = jfr.getFileName().toString();
        return name.endsWith(".jfr") ? name.substring(0, name.length() - 4) : name;
    }

    @Override
    public void close() {
        Session session = active.getAndSet(null);
        executor.shutdownNow();
        if (session == null || !session.finished().compareAndSet(false, true)) return;
        try {
            if (session.recording().getState() == RecordingState.RUNNING) session.recording().stop();
            session.recording().close();
            plugin.getLogger().info("Perfil JFR detenido durante el apagado; captura guardada sin análisis: "
                    + session.jfr());
        } catch (Exception error) {
            plugin.getLogger().warning("No se pudo cerrar el perfil JFR durante el apagado: " + error.getMessage());
        }
        enforceQuota();
    }

    private void enforceQuota() {
        Session current = active.get();
        Set<Path> protectedFiles = current == null ? Set.of()
                : Set.of(current.jfr().toAbsolutePath().normalize(), current.analysis().toAbsolutePath().normalize());
        try { DiskQuota.files(root, maxDiskBytes, protectedFiles); }
        catch (IOException error) {
            plugin.getLogger().warning("No se pudo aplicar la cuota de perfiles: " + error.getMessage());
        }
    }

    private record Session(Recording recording, Path jfr, Path analysis, Instant startedAt, Instant endsAt,
                           int seconds, String trigger, String reason, Map<String, String> pluginPrefixes,
                           AtomicBoolean finished) {}
    public record StartResult(Path jfr, Path analysis, int seconds, Instant endsAt) {}
}
