package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;

import java.time.Instant;

/** Records commands that Paper reports as executed by command blocks. */
public final class CommandExecutionMonitor implements Listener {
    private static final String HEADER = "timestamp,world,x,y,z,name,command";
    private final TelemetryStore store;

    public CommandExecutionMonitor(TelemetryStore store) { this.store = store; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(ServerCommandEvent event) {
        if (!(event.getSender() instanceof BlockCommandSender sender)) return;
        Location location = sender.getBlock().getLocation();
        Instant now = Instant.now();
        store.append("command-executions", HEADER, now, Csv.row(now.toEpochMilli(),
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                sender.getName(), event.getCommand()));
    }
}
