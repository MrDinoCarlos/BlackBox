package es.mrdino.blackbox.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryStoreTest {
    @Test
    void rateLimitsAndCompressesSegments(@TempDir Path root) throws Exception {
        Instant now = Instant.parse("2026-09-24T20:01:00Z");
        TelemetryStore store = new TelemetryStore(root, Logger.getAnonymousLogger(), 100,
                14, 5, 16 * 1024 * 1024, 5, 3, 1024);

        for (int i = 0; i < 10; i++) store.append("events", "timestamp,value", now, now.toEpochMilli() + "," + i);
        store.append("oversized", "timestamp,value", now, "x".repeat(2_000));
        store.close();

        List<Path> compressed;
        try (var files = Files.list(root)) {
            compressed = files.filter(path -> path.getFileName().toString().endsWith(".csv.gz")).toList();
        }
        assertEquals(1, compressed.size());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(compressed.getFirst())), StandardCharsets.UTF_8))) {
            assertEquals(4, reader.lines().count()); // header + three accepted rows
        }
        assertEquals(8, store.droppedRows());
    }

    @Test
    void removesOldestLegacyTelemetryUntilQuotaIsMet(@TempDir Path root) throws Exception {
        Path oldest = root.resolve("events-old.csv");
        Path newest = root.resolve("events-new.csv");
        Files.writeString(oldest, "x".repeat(200), StandardCharsets.UTF_8);
        Files.writeString(newest, "y".repeat(80), StandardCharsets.UTF_8);
        long current = System.currentTimeMillis();
        Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(current - 2_000));
        Files.setLastModifiedTime(newest, java.nio.file.attribute.FileTime.fromMillis(current - 1_000));

        try (TelemetryStore ignored = new TelemetryStore(root, Logger.getAnonymousLogger(), 100,
                14, 5, 100, 5, 100, 1024)) {
            assertTrue(Files.notExists(oldest));
            assertTrue(Files.exists(newest) || Files.exists(root.resolve("events-new.csv.gz")));
        }
        long total;
        try (var files = Files.list(root)) {
            total = files.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); }
                catch (Exception ignored) { return 0; }
            }).sum();
        }
        assertTrue(total <= 100);
    }

    @Test
    void removesOldReportDirectoriesButProtectsCurrentOne(@TempDir Path root) throws Exception {
        Path oldReport = Files.createDirectories(root.resolve("old-report"));
        Path currentReport = Files.createDirectories(root.resolve("current-report"));
        Files.writeString(oldReport.resolve("report.pdf"), "x".repeat(200));
        Files.writeString(currentReport.resolve("report.pdf"), "y".repeat(80));
        long current = System.currentTimeMillis();
        Files.setLastModifiedTime(oldReport, java.nio.file.attribute.FileTime.fromMillis(current - 2_000));
        Files.setLastModifiedTime(currentReport, java.nio.file.attribute.FileTime.fromMillis(current - 1_000));

        DiskQuota.directories(root, 100, currentReport);

        assertTrue(Files.notExists(oldReport));
        assertTrue(Files.isDirectory(currentReport));
    }
}
