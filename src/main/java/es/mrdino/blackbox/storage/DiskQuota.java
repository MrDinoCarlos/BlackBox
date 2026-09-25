package es.mrdino.blackbox.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class DiskQuota {
    private DiskQuota() {}

    public static void files(Path root, long maxBytes, Set<Path> protectedFiles) throws IOException {
        if (maxBytes <= 0 || !Files.isDirectory(root)) return;
        List<Path> files;
        try (Stream<Path> listed = Files.list(root)) {
            files = listed.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(DiskQuota::modified)).toList();
        }
        long total = files.stream().mapToLong(DiskQuota::size).sum();
        for (Path file : files) {
            if (total <= maxBytes) break;
            if (protectedFiles.contains(file.toAbsolutePath().normalize())) continue;
            long bytes = size(file);
            if (Files.deleteIfExists(file)) total -= bytes;
        }
    }

    public static void directories(Path root, long maxBytes, Path protectedDirectory) throws IOException {
        if (maxBytes <= 0 || !Files.isDirectory(root)) return;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path protectedPath = protectedDirectory == null ? null : protectedDirectory.toAbsolutePath().normalize();
        List<Path> directories;
        try (Stream<Path> listed = Files.list(normalizedRoot)) {
            directories = listed.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(DiskQuota::modified)).toList();
        }
        long total = 0;
        for (Path directory : directories) total += treeSize(directory);
        for (Path directory : directories) {
            if (total <= maxBytes) break;
            Path normalized = directory.toAbsolutePath().normalize();
            if (!normalized.startsWith(normalizedRoot) || normalized.equals(protectedPath)) continue;
            long bytes = treeSize(normalized);
            deleteTree(normalized, normalizedRoot);
            total -= bytes;
        }
    }

    private static long treeSize(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(DiskQuota::size).sum();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static void deleteTree(Path target, Path allowedRoot) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(allowedRoot) || normalized.equals(allowedRoot)) return;
        try (Stream<Path> paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static long size(Path path) {
        try { return Files.size(path); }
        catch (IOException ignored) { return 0; }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }
}
