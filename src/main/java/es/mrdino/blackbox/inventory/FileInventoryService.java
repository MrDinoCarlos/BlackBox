package es.mrdino.blackbox.inventory;

import es.mrdino.blackbox.BlackBoxSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class FileInventoryService {
    private static final Set<String> CONFIG_EXTENSIONS = Set.of("yml", "yaml", "json", "toml", "properties", "conf", "cfg");
    private static final Set<String> ASSET_EXTENSIONS = Set.of("zip", "mcmeta", "mcfunction", "json", "png", "ogg", "bbmodel", "jem", "jpm");
    private static final Pattern YAML_KEY = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*:");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^\\s*([^#!\\s][^=:#]*?)\\s*[=:#]");
    private static final Pattern JSON_KEY = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(\\\"[^\\\"]{0,200}\\\"|-?\\d+(?:\\.\\d+)?|true|false|null)");
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|passwd|secret|token|api[-_.]?key|private[-_.]?key|credential|authorization|webhook|jdbc|database[-_.]?url|proxy|address|server[-_.]?ip).*");
    private final Path serverRoot;
    private final long maxHashBytes;
    private final BlackBoxSettings.InventoryOptions options;

    public FileInventoryService(Path serverRoot, long maxHashBytes) {
        this(serverRoot, maxHashBytes, new BlackBoxSettings.InventoryOptions(
                true, true, true, 20_000, 1024 * 1024L, 200, 200_000, 100_000, 2L * 1024 * 1024));
    }

    public FileInventoryService(Path serverRoot, long maxHashBytes, BlackBoxSettings.InventoryOptions options) {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.maxHashBytes = maxHashBytes;
        this.options = options;
    }

    public InventorySnapshot.FileInventory scan() {
        List<InventorySnapshot.FileInventory.FileInfo> files = new ArrayList<>();
        List<InventorySnapshot.FileInventory.PackInfo> packs = new ArrayList<>();
        List<InventorySnapshot.FileInventory.FunctionInfo> functions = new ArrayList<>();
        boolean truncated = false;
        List<ScanRoot> roots = roots();
        outer:
        for (ScanRoot scanRoot : roots) {
            if (Thread.currentThread().isInterrupted()) { truncated = true; break; }
            if (!Files.exists(scanRoot.path())) continue;
            try (Stream<Path> stream = Files.walk(scanRoot.path(), scanRoot.depth(), FileVisitOption.FOLLOW_LINKS)) {
                for (Path path : stream.filter(Files::isRegularFile).sorted().toList()) {
                    if (Thread.currentThread().isInterrupted()) { truncated = true; break outer; }
                    if (files.size() >= options.maxFiles()) { truncated = true; break outer; }
                    String extension = extension(path);
                    if (!scanRoot.filter().test(path)) continue;
                    try {
                        long size = Files.size(path);
                        FileTime modified = Files.getLastModifiedTime(path);
                        files.add(new InventorySnapshot.FileInventory.FileInfo(scanRoot.group(), relative(path), size,
                                modified.toInstant(), size <= maxHashBytes ? sha256(path) : "skipped:size",
                                options.scanConfigValues() && CONFIG_EXTENSIONS.contains(extension)
                                        && size <= options.maxConfigReadBytes() ? configEntries(path, extension) : List.of()));
                        if (options.analyzeFunctions() && extension.equals("mcfunction")) {
                            inspectFunction(path, relative(path), functions);
                        }
                        if (options.analyzePackArchives() && extension.equals("zip")) {
                            inspectPack(path, packs, functions);
                        }
                    } catch (IOException ignored) {
                        files.add(new InventorySnapshot.FileInventory.FileInfo(scanRoot.group(), relative(path), -1,
                                Instant.EPOCH, "unreadable", List.of()));
                    }
                }
            } catch (IOException ignored) {
                truncated = true;
            }
        }
        files.sort(Comparator.comparing(InventorySnapshot.FileInventory.FileInfo::group)
                .thenComparing(InventorySnapshot.FileInventory.FileInfo::path));
        functions.sort(Comparator.comparingInt(InventorySnapshot.FileInventory.FunctionInfo::riskScore).reversed()
                .thenComparing(InventorySnapshot.FileInventory.FunctionInfo::path));
        return new InventorySnapshot.FileInventory(List.copyOf(files), List.copyOf(packs), List.copyOf(functions), truncated);
    }

    private List<ScanRoot> roots() {
        Predicate<Path> topConfig = path -> path.getParent() != null && path.getParent().equals(serverRoot)
                && (CONFIG_EXTENSIONS.contains(extension(path)) || path.getFileName().toString().equals("server.properties"));
        List<ScanRoot> result = new ArrayList<>();
        result.add(new ScanRoot("server", serverRoot, 1, topConfig));
        result.add(new ScanRoot("plugins", serverRoot.resolve("plugins"), 6,
                p -> extension(p).equals("jar") || CONFIG_EXTENSIONS.contains(extension(p)) || ASSET_EXTENSIONS.contains(extension(p))));
        result.add(new ScanRoot("paper-config", serverRoot.resolve("config"), 5, p -> true));
        result.add(new ScanRoot("resource-packs", serverRoot.resolve("resourcepacks"), 8, p -> true));
        // Solo entra en carpetas datapacks conocidas. Recorrer el root completo tambien visitaria
        // archivos region y haria que el inventario fuese innecesariamente caro.
        try (Stream<Path> children = Files.list(serverRoot)) {
            children.filter(Files::isDirectory).map(p -> p.resolve("datapacks")).filter(Files::isDirectory)
                    .forEach(p -> result.add(new ScanRoot("world-datapacks", p, 12, ignored -> true)));
        } catch (IOException ignored) {}
        return result;
    }

    private void inspectPack(Path path, List<InventorySnapshot.FileInventory.PackInfo> packs,
                             List<InventorySnapshot.FileInventory.FunctionInfo> functionInventory) {
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            int entries = 0, models = 0, textures = 0, blockStates = 0, functionCount = 0, tags = 0, recipes = 0, loot = 0;
            long bytes = 0;
            boolean meta = false;
            var enumeration = zip.entries();
            while (!Thread.currentThread().isInterrupted()
                    && enumeration.hasMoreElements() && entries < options.maxZipEntries()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) continue;
                entries++;
                if (entry.getSize() > 0) bytes += entry.getSize();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.equals("pack.mcmeta")) meta = true;
                if (name.contains("/models/") && name.endsWith(".json")) models++;
                if (name.contains("/textures/") && name.endsWith(".png")) textures++;
                if (name.contains("/blockstates/") && name.endsWith(".json")) blockStates++;
                if (name.contains("/function") && name.endsWith(".mcfunction")) {
                    functionCount++;
                    if (options.analyzeFunctions() && entry.getSize() >= 0
                            && entry.getSize() <= options.maxFunctionFileBytes()) {
                        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                            analyzeFunction(relative(path) + "!/" + entry.getName(), reader, functionInventory);
                        }
                    }
                }
                if (name.contains("/tags/") && name.endsWith(".json")) tags++;
                if (name.contains("/recipe") && name.endsWith(".json")) recipes++;
                if (name.contains("/loot_table") && name.endsWith(".json")) loot++;
            }
            if (meta || models + textures + functionCount > 0) {
                packs.add(new InventorySnapshot.FileInventory.PackInfo(relative(path), entries, bytes, models,
                        textures, blockStates, functionCount, tags, recipes, loot, meta));
            }
        } catch (IOException ignored) {}
    }

    private void inspectFunction(Path path, String displayPath,
                                 List<InventorySnapshot.FileInventory.FunctionInfo> functions) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            analyzeFunction(displayPath, reader, functions);
        } catch (IOException ignored) {}
    }

    private void analyzeFunction(String path, BufferedReader reader,
                                 List<InventorySnapshot.FileInventory.FunctionInfo> output) throws IOException {
        int commands = 0, executes = 0, broad = 0, schedules = 0, mutations = 0, summons = 0, particles = 0;
        String line;
        while (!Thread.currentThread().isInterrupted()
                && (line = reader.readLine()) != null && commands < options.maxFunctionCommands()) {
            String command = line.strip().toLowerCase(Locale.ROOT);
            if (command.isEmpty() || command.startsWith("#")) continue;
            commands++;
            if (command.startsWith("execute ")) executes++;
            if (command.contains("@e") && !command.contains("distance=") && !command.contains("dx=")
                    && !command.contains("limit=")) broad++;
            if (command.startsWith("schedule ") || command.contains(" run schedule ")) schedules++;
            if (command.startsWith("fill ") || command.startsWith("clone ") || command.startsWith("forceload ")) mutations++;
            if (command.startsWith("summon ") || command.contains(" run summon ")) summons++;
            if (command.startsWith("particle ") || command.contains(" run particle ")) particles++;
        }
        int risk = broad * 8 + schedules * 4 + mutations * 6 + summons * 3 + particles * 2 + Math.max(0, commands - 500) / 100;
        List<String> notes = new ArrayList<>();
        if (broad > 0) notes.add(broad + " selectores @e amplios");
        if (schedules > 0) notes.add(schedules + " schedules");
        if (mutations > 0) notes.add(mutations + " mutaciones masivas/forceload");
        if (summons > 0) notes.add(summons + " summons");
        if (particles > 0) notes.add(particles + " particulas");
        output.add(new InventorySnapshot.FileInventory.FunctionInfo(path, commands, executes, broad, schedules,
                mutations, summons, particles, risk, String.join("; ", notes)));
    }

    private List<InventorySnapshot.FileInventory.ConfigEntry> configEntries(Path path, String extension) {
        LinkedHashMap<String, InventorySnapshot.FileInventory.ConfigEntry> entries = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while (!Thread.currentThread().isInterrupted()
                    && (line = reader.readLine()) != null && entries.size() < options.maxConfigEntries()) {
                Pattern pattern = extension.equals("json") ? JSON_KEY
                        : extension.equals("properties") ? PROPERTY_KEY : YAML_KEY;
                Matcher matcher = pattern.matcher(line);
                while (matcher.find() && entries.size() < options.maxConfigEntries()) {
                    String key = matcher.group(1).trim();
                    String value;
                    if (extension.equals("json")) {
                        value = matcher.groupCount() >= 2 ? matcher.group(2) : "";
                    } else {
                        value = line.substring(matcher.end()).trim();
                        int comment = value.indexOf(" #");
                        if (comment >= 0) value = value.substring(0, comment).trim();
                    }
                    if (value.length() > 160) value = value.substring(0, 157) + "...";
                    boolean redacted = !value.isBlank() && SENSITIVE_KEY.matcher(key).matches();
                    if (redacted) value = "<redacted>";
                    entries.putIfAbsent(key, new InventorySnapshot.FileInventory.ConfigEntry(key, value, redacted));
                }
            }
        } catch (IOException ignored) { return List.of(); }
        return List.copyOf(entries.values());
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while (!Thread.currentThread().isInterrupted() && (read = input.read(buffer)) >= 0)
                    digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String relative(Path path) {
        try { return serverRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
        catch (IllegalArgumentException ignored) { return path.toString().replace('\\', '/'); }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record ScanRoot(String group, Path path, int depth, Predicate<Path> filter) {}
}
