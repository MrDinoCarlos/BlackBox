package es.mrdino.blackbox.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record InventorySnapshot(
        Instant capturedAt,
        ServerInfo server,
        List<PluginInfo> plugins,
        List<CommandInfo> commands,
        List<WorldInfo> worlds,
        List<DataPackInfo> dataPacks,
        ResourcePackInfo resourcePack
) {
    public record ServerInfo(String name, String version, String bukkitVersion, String javaVersion,
                             String javaVendor, String os, int viewDistance, int simulationDistance,
                             int maxPlayers) {}

    public record PluginInfo(String name, String version, boolean enabled, String mainClass,
                             String apiVersion, List<String> authors, List<String> depend,
                             List<String> softDepend, List<String> loadBefore, int syncTasks,
                             int asyncTasks, int registeredListeners) {}

    public record CommandInfo(String key, String name, String owner, String permission,
                              String description, String usage, List<String> aliases) {}

    public record WorldInfo(String name, String environment, String difficulty, long seed,
                            int loadedChunks, int players, int viewDistance, int simulationDistance,
                            double borderSize, boolean keepSpawnLoaded) {}

    public record DataPackInfo(String key, String title, String source, boolean enabled,
                               boolean required, String compatibility, int packFormat,
                               int minFormat, int maxFormat, List<String> features) {}

    public record ResourcePackInfo(String id, String url, String hash, String prompt, boolean required) {
        public static ResourcePackInfo none() { return new ResourcePackInfo("", "", "", "", false); }
    }

    public record FileInventory(List<FileInfo> files, List<PackInfo> packs, List<FunctionInfo> functions, boolean truncated) {
        public record FileInfo(String group, String path, long bytes, Instant modified,
                               String sha256, List<ConfigEntry> configEntries) {}
        public record ConfigEntry(String key, String value, boolean redacted) {}
        public record PackInfo(String path, int entries, long uncompressedBytes, int models,
                               int textures, int blockStates, int functions, int tags,
                               int recipes, int lootTables, boolean hasPackMeta) {}
        public record FunctionInfo(String path, int commands, int executes, int broadEntitySelectors,
                                   int schedules, int worldMutations, int summons, int particles,
                                   int riskScore, String notes) {}
    }
}
