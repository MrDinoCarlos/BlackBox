package es.mrdino.blackbox.monitor;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

public final class EntityLifecycleMonitor implements Listener {
    private static final String HEADER = "timestamp,event,uuid,type,spawn_reason,remove_cause,world,x,y,z,chunk_x,chunk_z,ticks_lived,observed_lifetime_ms,ticking,persistent,from_spawner,custom_name,passengers,tracked_players,origin,attributed_source,attribution_confidence";
    private final TelemetryStore store;
    private final boolean storeUuids;
    private final String ownerName;
    private final Map<String, String> pluginPrefixes;
    private final Map<UUID, Long> observedSince = new HashMap<>();

    public EntityLifecycleMonitor(Plugin owner, TelemetryStore store, boolean storeUuids) {
        this.store = store;
        this.storeUuids = storeUuids;
        this.ownerName = owner.getName();
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (Plugin installed : owner.getServer().getPluginManager().getPlugins()) {
            String className = installed.getClass().getName();
            int split = className.lastIndexOf('.');
            if (split > 0) prefixes.put(installed.getName(), className.substring(0, split + 1));
        }
        this.pluginPrefixes = Map.copyOf(prefixes);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        Entity entity = event.getEntity();
        observedSince.put(entity.getUniqueId(), System.currentTimeMillis());
        record("add_world", entity, "", 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        observedSince.putIfAbsent(entity.getUniqueId(), System.currentTimeMillis());
        record("spawn", entity, "", 0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        Long start = observedSince.remove(entity.getUniqueId());
        long lifetime = start == null ? -1 : Math.max(0, System.currentTimeMillis() - start);
        record("remove", entity, event.getCause().name(), lifetime);
    }

    private void record(String event, Entity entity, String removeCause, long observedLifetime) {
        Instant now = Instant.now();
        Location location = entity.getLocation();
        Location origin = entity.getOrigin();
        String originText = origin == null || origin.getWorld() == null ? "" : origin.getWorld().getName() + ":"
                + round(origin.getX()) + ":" + round(origin.getY()) + ":" + round(origin.getZ());
        var spawnReason = entity.getEntitySpawnReason();
        Source source = source(event, entity, spawnReason == null ? "" : spawnReason.name(), originText);
        store.append("entity-lifecycle", HEADER, now, Csv.row(now.toEpochMilli(), event,
                storeUuids ? entity.getUniqueId() : "",
                entity.getType().name(), spawnReason == null ? "" : spawnReason.name(), removeCause,
                location.getWorld().getName(), round(location.getX()), round(location.getY()), round(location.getZ()),
                location.getBlockX() >> 4, location.getBlockZ() >> 4, entity.getTicksLived(), observedLifetime,
                entity.isTicking(), entity.isPersistent(), entity.fromMobSpawner(), safe(entity.getCustomName()),
                entity.getPassengers().size(), entity.getTrackedBy().size(), originText,
                source.value(), source.confidence()));
    }

    private Source source(String event, Entity entity, String spawnReason, String origin) {
        if (!event.equals("spawn")) return new Source("", "");
        if (entity.fromMobSpawner()) return new Source("Spawner" + (origin.isBlank() ? "" : " @ " + origin), "direct");
        boolean mayBeExternal = spawnReason.equals("CUSTOM") || spawnReason.equals("COMMAND")
                || spawnReason.equals("DEFAULT") || spawnReason.equals("PLUGIN");
        if (!mayBeExternal) return new Source("Minecraft: "
                + (spawnReason.isBlank() ? "desconocido" : spawnReason), "direct_reason");
        String plugin = StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                .flatMap(className -> pluginPrefixes.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(ownerName) && className.startsWith(entry.getValue()))
                        .map(Map.Entry::getKey)).findFirst()).orElse("");
        if (!plugin.isBlank()) return new Source("Plugin: " + plugin, "probable_stack");
        if (spawnReason.equals("COMMAND")) return new Source("Comando; ejecutor exacto no expuesto por Paper", "contextual");
        return new Source("Minecraft: " + (spawnReason.isBlank() ? "desconocido" : spawnReason), "direct_reason");
    }

    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private static String safe(String value) { return value == null ? "" : value; }
    private record Source(String value, String confidence) {}
}
