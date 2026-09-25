package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.BlackBoxSettings;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public final class EventMonitor implements Listener {
    private final EventCounters counters;
    private final BlackBoxSettings.EventOptions options;

    public EventMonitor(EventCounters counters, BlackBoxSettings.EventOptions options) {
        this.counters = counters;
        this.options = options;
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onChunkLoad(ChunkLoadEvent e) { if (options.chunkLoads()) { counters.chunkLoads.increment(); counters.hotspot("chunk_load", e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ()); } }
    @EventHandler(priority = EventPriority.MONITOR) public void onChunkUnload(ChunkUnloadEvent e) { if (options.chunkLoads()) { counters.chunkUnloads.increment(); counters.hotspot("chunk_unload", e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSpawn(EntitySpawnEvent e) { if (options.entitySpawns()) { counters.entitySpawns.increment(); counters.hotspot("entity_spawn", e.getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR) public void onDeath(EntityDeathEvent e) { if (options.entitySpawns()) { counters.entityDeaths.increment(); counters.hotspot("entity_death", e.getEntity().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR) public void onRedstone(BlockRedstoneEvent e) { if (options.redstone()) { counters.redstoneChanges.increment(); counters.hotspot("redstone", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onHopper(InventoryMoveItemEvent e) { if (options.inventoryMoves()) { counters.hopperMoves.increment(); counters.hotspot("inventory_move", e.getSource().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonExtend(BlockPistonExtendEvent e) { if (options.pistons()) { counters.pistonActions.increment(); counters.hotspot("piston", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPistonRetract(BlockPistonRetractEvent e) { if (options.pistons()) { counters.pistonActions.increment(); counters.hotspot("piston", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onEntityExplode(EntityExplodeEvent e) { if (options.explosions()) { counters.explosions.increment(); counters.hotspot("explosion", e.getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBlockExplode(BlockExplodeEvent e) { if (options.explosions()) { counters.explosions.increment(); counters.hotspot("explosion", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { if (options.blockChanges()) { counters.blockBreaks.increment(); counters.hotspot("block_break", e.getBlock().getLocation()); } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { if (options.blockChanges()) { counters.blockPlaces.increment(); counters.hotspot("block_place", e.getBlock().getLocation()); } }
}
