package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.util.Csv;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RegionCensusService implements AutoCloseable {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private final Plugin plugin;
    private final Path outputRoot;
    private final int intervalMinutes;
    private final boolean parseNbt;
    private final boolean includePoi;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "blackbox-region-census");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private volatile Summary latest = Summary.empty();
    private BukkitTask schedule;

    public RegionCensusService(Plugin plugin, int intervalMinutes, boolean parseNbt,
                               boolean includePoi) throws IOException {
        this.plugin = plugin;
        this.intervalMinutes = intervalMinutes;
        this.parseNbt = parseNbt;
        this.includePoi = includePoi;
        this.outputRoot = plugin.getDataFolder().toPath().resolve("inventory");
        Files.createDirectories(outputRoot);
    }

    public void start() {
        schedule = plugin.getServer().getScheduler().runTaskTimer(plugin, this::requestNow,
                20L, intervalMinutes * 60L * 20L);
    }
    public void requestNow() {
        List<WorldRoot> worlds = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            worlds.add(new WorldRoot(world.getName(), world.getWorldFolder().toPath().toAbsolutePath().normalize()));
        }
        executor.execute(() -> safeScan(List.copyOf(worlds)));
    }
    public Summary latest() { return latest; }
    public Path chunksFile() { return outputRoot.resolve("disk-chunks-latest.csv"); }
    public Path regionsFile() { return outputRoot.resolve("regions-latest.csv"); }

    private void safeScan(List<WorldRoot> worlds) {
        try { scan(worlds); }
        catch (Exception error) { plugin.getLogger().warning("Fallo en el censo offline de regiones: " + error.getMessage()); }
    }

    private void scan(List<WorldRoot> worlds) throws IOException {
        Instant started = Instant.now();
        Path chunksTemp = Files.createTempFile(outputRoot, "disk-chunks-", ".tmp");
        Path regionsTemp = Files.createTempFile(outputRoot, "regions-", ".tmp");
        long regionCount = 0, terrainChunks = 0, entityChunks = 0, poiChunks = 0, totalBytes = 0, malformed = 0;
        try (BufferedWriter chunks = Files.newBufferedWriter(chunksTemp, StandardCharsets.UTF_8);
             BufferedWriter regions = Files.newBufferedWriter(regionsTemp, StandardCharsets.UTF_8)) {
            chunks.write("world,kind,region_path,region_x,region_z,chunk_x,chunk_z,sectors,file_modified,entities,entity_types,block_entities,block_entity_types,block_ticks,fluid_ticks,nbt_error"); chunks.newLine();
            regions.write("world,kind,path,region_x,region_z,chunks,allocated_sectors,bytes,modified,malformed_entries,nbt_errors,entities,block_entities,block_ticks,fluid_ticks"); regions.newLine();
            for (WorldRoot world : worlds) {
                for (Path directory : regionDirectories(world.path())) {
                    String kind = directory.getFileName().toString().toLowerCase(Locale.ROOT);
                    try (Stream<Path> files = Files.list(directory)) {
                        for (Path file : files.filter(Files::isRegularFile).filter(p -> REGION_NAME.matcher(p.getFileName().toString()).matches()).sorted().toList()) {
                            RegionResult result = inspect(world, kind, file, chunks);
                            if (result == null) continue;
                            regionCount++;
                            totalBytes += result.bytes;
                            malformed += result.malformed;
                            if (kind.equals("region")) terrainChunks += result.chunks;
                            else if (kind.equals("entities")) entityChunks += result.chunks;
                            else if (kind.equals("poi")) poiChunks += result.chunks;
                            regions.write(Csv.row(world.name(), kind, relative(world.path(), file), result.regionX,
                                    result.regionZ, result.chunks, result.sectors, result.bytes,
                                    result.modified.toEpochMilli(), result.malformed, result.nbtErrors,
                                    result.entities, result.blockEntities, result.blockTicks, result.fluidTicks));
                            regions.newLine();
                        }
                    }
                }
            }
        } catch (Exception error) {
            Files.deleteIfExists(chunksTemp);
            Files.deleteIfExists(regionsTemp);
            throw error;
        }
        replace(chunksTemp, chunksFile());
        replace(regionsTemp, regionsFile());
        latest = new Summary(started, Instant.now(), regionCount, terrainChunks, entityChunks, poiChunks,
                totalBytes, malformed, chunksFile(), regionsFile());
        plugin.getLogger().info("Censo offline: " + terrainChunks + " chunks de terreno en " + regionCount + " archivos .mca.");
    }

    private RegionResult inspect(WorldRoot world, String kind, Path file, BufferedWriter chunksOut) throws IOException {
        Matcher matcher = REGION_NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) return null;
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));
        ByteBuffer header = ByteBuffer.allocate(4096);
        long bytes = Files.size(file);
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        int count = 0, sectorsTotal = 0, malformed = bytes < 8192 ? 1 : 0, nbtErrors = 0;
        long entities = 0, blockEntities = 0, blockTicks = 0, fluidTicks = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (header.hasRemaining() && channel.read(header) > 0) {}
            header.flip();
            for (int index = 0; index < 1024 && header.remaining() >= 4; index++) {
                int location = header.getInt();
                int offset = (location >>> 8) & 0x00FF_FFFF;
                int sectors = location & 0xFF;
                if (offset == 0 && sectors == 0) continue;
                boolean invalid = offset < 2 || sectors == 0 || ((long) offset + sectors) * 4096L > bytes;
                if (invalid) malformed++;
                count++;
                sectorsTotal += sectors;
                int chunkX = regionX * 32 + (index & 31);
                int chunkZ = regionZ * 32 + (index >>> 5);
                McaChunkInspector.Summary content = invalid || kind.equals("poi") || !parseNbt
                        ? McaChunkInspector.Summary.error(invalid ? "invalid_location" : "")
                        : McaChunkInspector.inspect(channel, offset, sectors);
                if (!content.error().isBlank()) nbtErrors++;
                entities += content.entities(); blockEntities += content.blockEntities();
                blockTicks += content.blockTicks(); fluidTicks += content.fluidTicks();
                chunksOut.write(Csv.row(world.name(), kind, relative(world.path(), file), regionX, regionZ,
                        chunkX, chunkZ, sectors, modified.toEpochMilli(), content.entities(), content.entityTypesText(),
                        content.blockEntities(), content.blockEntityTypesText(), content.blockTicks(),
                        content.fluidTicks(), content.error()));
                chunksOut.newLine();
            }
        }
        return new RegionResult(regionX, regionZ, count, sectorsTotal, bytes, modified, malformed, nbtErrors,
                entities, blockEntities, blockTicks, fluidTicks);
    }

    private List<Path> regionDirectories(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> stream = Files.find(root, 7, (path, attrs) -> attrs.isDirectory()
                && (path.getFileName().toString().equals("region") || path.getFileName().toString().equals("entities")
                || (includePoi && path.getFileName().toString().equals("poi"))))) {
            return stream.sorted().toList();
        }
    }

    private static String relative(Path root, Path path) {
        try { return root.relativize(path).toString().replace('\\', '/'); }
        catch (IllegalArgumentException ignored) { return path.toString().replace('\\', '/'); }
    }

    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    @Override public void close() {
        if (schedule != null) schedule.cancel();
        executor.shutdownNow();
    }

    private record WorldRoot(String name, Path path) {}
    private record RegionResult(int regionX, int regionZ, int chunks, int sectors, long bytes,
                                Instant modified, int malformed, int nbtErrors, long entities,
                                long blockEntities, long blockTicks, long fluidTicks) {}
    public record Summary(Instant started, Instant finished, long regions, long terrainChunks,
                          long entityChunks, long poiChunks, long bytes, long malformedEntries,
                          Path chunksFile, Path regionsFile) {
        static Summary empty() { return new Summary(Instant.EPOCH, Instant.EPOCH, 0, 0, 0, 0, 0, 0, null, null); }
        public long durationMillis() { return Math.max(0, finished.toEpochMilli() - started.toEpochMilli()); }
    }
}
