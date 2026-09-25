package es.mrdino.blackbox.inventory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.packs.ResourcePack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InventoryService {
    private final Plugin plugin;

    public InventoryService(Plugin plugin) { this.plugin = plugin; }

    public InventorySnapshot captureLive() {
        var server = plugin.getServer();
        InventorySnapshot.ServerInfo serverInfo = new InventorySnapshot.ServerInfo(server.getName(),
                server.getVersion(), server.getBukkitVersion(), System.getProperty("java.version"),
                System.getProperty("java.vendor"), System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " " + System.getProperty("os.arch"),
                server.getViewDistance(), server.getSimulationDistance(), server.getMaxPlayers());

        List<InventorySnapshot.PluginInfo> plugins = new ArrayList<>();
        for (Plugin installed : server.getPluginManager().getPlugins()) {
            PluginDescriptionFile d = installed.getDescription();
            plugins.add(new InventorySnapshot.PluginInfo(installed.getName(), installed.getPluginMeta().getVersion(),
                    installed.isEnabled(), installed.getClass().getName(), d.getAPIVersion(), d.getAuthors(),
                    d.getDepend(), d.getSoftDepend(), d.getLoadBefore(),
                    (int) server.getScheduler().getPendingTasks().stream().filter(t -> !t.isCancelled()
                            && t.getOwner().equals(installed) && t.isSync()).count(),
                    (int) server.getScheduler().getPendingTasks().stream().filter(t -> !t.isCancelled()
                            && t.getOwner().equals(installed) && !t.isSync()).count(),
                    HandlerList.getRegisteredListeners(installed).size()));
        }
        plugins.sort(Comparator.comparing(InventorySnapshot.PluginInfo::name, String.CASE_INSENSITIVE_ORDER));

        List<InventorySnapshot.CommandInfo> commands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        server.getCommandMap().getKnownCommands().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Command command = entry.getValue();
                    String owner = command instanceof PluginIdentifiableCommand identified
                            ? identified.getPlugin().getName() : command.getClass().getName();
                    String identity = entry.getKey().toLowerCase(Locale.ROOT) + "@" + owner;
                    if (seen.add(identity)) commands.add(new InventorySnapshot.CommandInfo(entry.getKey(),
                            command.getName(), owner, value(command.getPermission()), command.getDescription(),
                            command.getUsage(), List.copyOf(command.getAliases())));
                });

        List<InventorySnapshot.WorldInfo> worlds = new ArrayList<>();
        for (World world : server.getWorlds()) {
            worlds.add(new InventorySnapshot.WorldInfo(world.getName(), world.getEnvironment().name(),
                    world.getDifficulty().name(), world.getSeed(), world.getLoadedChunks().length,
                    world.getPlayerCount(), world.getViewDistance(), world.getSimulationDistance(),
                    world.getWorldBorder().getSize(), world.getKeepSpawnInMemory()));
        }

        List<InventorySnapshot.DataPackInfo> dataPacks = captureDataPacks(server);
        dataPacks.sort(Comparator.comparing(InventorySnapshot.DataPackInfo::key));

        ResourcePack resource = server.getServerResourcePack();
        InventorySnapshot.ResourcePackInfo resourceInfo = resource == null
                ? InventorySnapshot.ResourcePackInfo.none()
                : new InventorySnapshot.ResourcePackInfo(resource.getId().toString(), resource.getUrl(),
                resource.getHash(), value(resource.getPrompt()), resource.isRequired());
        return new InventorySnapshot(Instant.now(), serverInfo, List.copyOf(plugins), List.copyOf(commands),
                List.copyOf(worlds), List.copyOf(dataPacks), resourceInfo);
    }

    private List<InventorySnapshot.DataPackInfo> captureDataPacks(Object server) {
        List<InventorySnapshot.DataPackInfo> result = new ArrayList<>();
        try {
            Object manager = invokeFirst(server, "getDatapackManager", "getDataPackManager");
            Object packs = invokeFirst(manager, "getPacks", "getDataPacks");
            if (!(packs instanceof Collection<?> collection)) return result;
            for (Object pack : collection) {
                Object rawKey = invokeOptional(pack, "getName");
                if (rawKey == null) {
                    Object key = invokeFirst(pack, "getKey");
                    rawKey = invokeOptional(key, "asString");
                    if (rawKey == null) rawKey = key;
                }
                Object rawFeatures = invokeOptional(pack, "getRequiredFeatures", "getRequestedFeatures");
                List<String> features = rawFeatures instanceof Collection<?> collectionFeatures
                        ? collectionFeatures.stream().map(Object::toString).sorted().toList()
                        : List.of();
                Object source = invokeFirst(pack, "getSource");
                Object sourceName = invokeOptional(source, "name");
                result.add(new InventorySnapshot.DataPackInfo(String.valueOf(rawKey),
                        text(invokeFirst(pack, "getTitle")),
                        sourceName == null ? enumName(source) : String.valueOf(sourceName), booleanValue(pack, "isEnabled"),
                        booleanValue(pack, "isRequired"), enumName(invokeFirst(pack, "getCompatibility")),
                        intValue(pack, "getPackFormat"), intValue(pack, "getMinSupportedPackFormat"),
                        intValue(pack, "getMaxSupportedPackFormat"), features));
            }
        } catch (RuntimeException error) {
            plugin.getLogger().warning("No se pudo inventariar los datapacks: " + error.getMessage());
        }
        return result;
    }

    private static Object invokeFirst(Object target, String... methodNames) {
        Object value = invokeOptional(target, methodNames);
        if (value != null) return value;
        throw new IllegalStateException("Ninguno de los metodos existe: " + String.join(", ", methodNames));
    }

    private static Object invokeOptional(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                var method = target.getClass().getMethod(methodName);
                if (!method.canAccess(target)) method = publicInterfaceMethod(target.getClass(), methodName);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Paper 26.3 reemplazo la API Bukkit de datapacks por la API Paper.
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("No se puede acceder a " + methodName, error);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                throw new IllegalStateException("Fallo al ejecutar " + methodName + ": "
                        + (cause == null ? error.getMessage() : cause.getMessage()), cause == null ? error : cause);
            }
        }
        return null;
    }

    private static java.lang.reflect.Method publicInterfaceMethod(Class<?> type, String methodName)
            throws NoSuchMethodException {
        for (Class<?> interfaceType : type.getInterfaces()) {
            try {
                return interfaceType.getMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                // Sigue buscando en las demas interfaces publicas de la API.
            }
        }
        Class<?> parent = type.getSuperclass();
        if (parent != null) return publicInterfaceMethod(parent, methodName);
        throw new NoSuchMethodException(methodName);
    }

    private static boolean booleanValue(Object target, String methodName) {
        Object value = invokeOptional(target, methodName);
        return value instanceof Boolean bool && bool;
    }

    private static int intValue(Object target, String methodName) {
        Object value = invokeOptional(target, methodName);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static String enumName(Object value) {
        return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
    }

    private static String text(Object value) {
        return value instanceof Component component
                ? PlainTextComponentSerializer.plainText().serialize(component)
                : String.valueOf(value);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
