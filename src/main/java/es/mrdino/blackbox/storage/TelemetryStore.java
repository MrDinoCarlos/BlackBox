package es.mrdino.blackbox.storage;

import org.bukkit.plugin.Plugin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

public final class TelemetryStore implements AutoCloseable {
    private static final DateTimeFormatter SEGMENT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneOffset.UTC);
    private static final long MAINTENANCE_INTERVAL_MS = 60_000L;

    private final Logger logger;
    private final Path root;
    private final ArrayBlockingQueue<WriteRequest> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final long shutdownFlushMillis;
    private final int retentionDays;
    private final long maxDiskBytes;
    private final int segmentMinutes;
    private final int maxRowsPerStreamMinute;
    private final int maxRowChars;
    private final Map<String, RateWindow> rates = new HashMap<>();

    public TelemetryStore(Plugin plugin, int capacity, int retentionDays, int shutdownFlushSeconds,
                          long maxDiskBytes, int segmentMinutes, int maxRowsPerStreamMinute,
                          int maxRowChars) throws IOException {
        this(plugin.getDataFolder().toPath().resolve("telemetry"), plugin.getLogger(), capacity,
                retentionDays, shutdownFlushSeconds, maxDiskBytes, segmentMinutes,
                maxRowsPerStreamMinute, maxRowChars);
    }

    TelemetryStore(Path root, Logger logger, int capacity, int retentionDays, int shutdownFlushSeconds,
                   long maxDiskBytes, int segmentMinutes, int maxRowsPerStreamMinute,
                   int maxRowChars) throws IOException {
        this.logger = logger;
        this.root = root;
        this.retentionDays = retentionDays;
        this.maxDiskBytes = maxDiskBytes;
        this.segmentMinutes = segmentMinutes;
        this.maxRowsPerStreamMinute = maxRowsPerStreamMinute;
        this.maxRowChars = maxRowChars;
        Files.createDirectories(root);
        cleanupByAge();
        // Free space before migrating legacy daily CSV files, which may already be very large.
        enforceQuota(Set.of());
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.shutdownFlushMillis = shutdownFlushSeconds * 1000L;
        this.worker = Thread.ofPlatform().daemon().name("blackbox-storage").start(this::writeLoop);
    }

    public synchronized void append(String stream, String header, Instant instant, String row) {
        if (row.length() > maxRowChars) {
            dropped.incrementAndGet();
            return;
        }
        long minute = instant.getEpochSecond() / 60L;
        RateWindow rate = rates.get(stream);
        if (rate == null || rate.minute != minute) {
            rate = new RateWindow(minute);
            rates.put(stream, rate);
        }
        if (rate.rows >= maxRowsPerStreamMinute) {
            dropped.incrementAndGet();
            return;
        }
        rate.rows++;
        if (!queue.offer(new WriteRequest(stream, header, instant, row))) dropped.incrementAndGet();
        if (rates.size() > 128) rates.entrySet().removeIf(entry -> entry.getValue().minute < minute - 2);
    }

    public long droppedRows() { return dropped.get(); }
    public int queuedRows() { return queue.size(); }
    public Path root() { return root; }

    private void writeLoop() {
        Map<String, ActiveWriter> writers = new HashMap<>();
        long nextMaintenance = System.currentTimeMillis() + MAINTENANCE_INTERVAL_MS;
        try {
            while (running.get() || !queue.isEmpty()) {
                WriteRequest first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first != null) {
                    write(first, writers);
                    WriteRequest next;
                    while ((next = queue.poll()) != null) write(next, writers);
                    for (ActiveWriter writer : writers.values()) writer.writer.flush();
                }
                long now = System.currentTimeMillis();
                if (now >= nextMaintenance) {
                    maintenance(writers, now);
                    nextMaintenance = now + MAINTENANCE_INTERVAL_MS;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            logger.severe("No se pudo escribir telemetria: " + error.getMessage());
        } finally {
            for (ActiveWriter writer : new ArrayList<>(writers.values())) closeAndCompress(writer);
            try { enforceQuota(Set.of()); }
            catch (IOException ignored) {}
        }
    }

    private void write(WriteRequest request, Map<String, ActiveWriter> writers) throws IOException {
        long segmentSeconds = segmentMinutes * 60L;
        long segmentStart = request.instant.getEpochSecond() / segmentSeconds * segmentSeconds;
        String fileName = request.stream + "-" + SEGMENT_TIME.format(Instant.ofEpochSecond(segmentStart)) + ".csv";
        Path path = root.resolve(fileName);
        ActiveWriter active = writers.get(request.stream);
        if (active == null || !active.path.equals(path)) {
            if (active != null) closeAndCompress(active);
            boolean empty = !Files.exists(path) || Files.size(path) == 0;
            BufferedWriter output = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            active = new ActiveWriter(path, segmentStart, output);
            writers.put(request.stream, active);
            if (empty) {
                output.write(request.header);
                output.newLine();
            }
        }
        active.writer.write(request.row);
        active.writer.newLine();
    }

    private void maintenance(Map<String, ActiveWriter> writers, long nowMillis) throws IOException {
        long currentSeconds = nowMillis / 1000L;
        long segmentSeconds = segmentMinutes * 60L;
        var iterator = writers.entrySet().iterator();
        while (iterator.hasNext()) {
            ActiveWriter writer = iterator.next().getValue();
            if (currentSeconds >= writer.segmentStart + segmentSeconds) {
                closeAndCompress(writer);
                iterator.remove();
            }
        }
        Set<Path> open = new HashSet<>();
        writers.values().forEach(writer -> open.add(writer.path));
        cleanupByAge();
        compressClosedCsvFiles(open);
        enforceQuota(open);
    }

    private void cleanupByAge() throws IOException {
        Instant cutoff = Instant.now().minusSeconds(retentionDays * 86_400L);
        try (var files = Files.list(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (!isTelemetry(path)) continue;
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) Files.deleteIfExists(path);
            }
        }
    }

    private void compressClosedCsvFiles(Set<Path> open) throws IOException {
        try (var files = Files.list(root)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".csv")).toList()) {
                if (!open.contains(path)) compress(path);
            }
        }
    }

    private void compress(Path source) {
        Path target = source.resolveSibling(source.getFileName() + ".gz");
        Path temporary = source.resolveSibling(source.getFileName() + ".gz.tmp");
        try (InputStream input = Files.newInputStream(source);
             OutputStream file = Files.newOutputStream(temporary, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING);
             GZIPOutputStream gzip = new GZIPOutputStream(file, 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while (!Thread.currentThread().isInterrupted() && (read = input.read(buffer)) >= 0) {
                gzip.write(buffer, 0, read);
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        } catch (Exception error) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) {}
            return;
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source);
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) {}
        }
    }

    private void enforceQuota(Set<Path> open) throws IOException {
        if (maxDiskBytes <= 0) return;
        List<Path> files;
        try (var listed = Files.list(root)) {
            files = listed.filter(Files::isRegularFile).filter(TelemetryStore::isTelemetry)
                    .sorted(Comparator.comparingLong(TelemetryStore::modified)).toList();
        }
        long total = 0;
        for (Path path : files) total += size(path);
        for (Path path : files) {
            if (total <= maxDiskBytes) break;
            if (open.contains(path)) continue;
            long bytes = size(path);
            if (Files.deleteIfExists(path)) total -= bytes;
        }
    }

    private static boolean isTelemetry(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".csv") || name.endsWith(".csv.gz") || name.endsWith(".gz.tmp");
    }

    private static long size(Path path) {
        try { return Files.size(path); }
        catch (IOException ignored) { return 0; }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }

    private void closeAndCompress(ActiveWriter active) {
        try { active.writer.close(); }
        catch (IOException ignored) {}
        compress(active.path);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            worker.join(shutdownFlushMillis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            long pending = queue.size();
            dropped.addAndGet(pending);
            queue.clear();
            logger.warning("Tiempo agotado al vaciar telemetria: " + pending + " filas pendientes.");
            worker.interrupt();
            try { worker.join(2_000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private static final class RateWindow {
        final long minute;
        int rows;
        RateWindow(long minute) { this.minute = minute; }
    }
    private record WriteRequest(String stream, String header, Instant instant, String row) {}
    private record ActiveWriter(Path path, long segmentStart, BufferedWriter writer) {}
}
