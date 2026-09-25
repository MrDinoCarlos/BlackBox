package es.mrdino.blackbox.monitor;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.Csv;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

public final class PlayerMonitor implements Listener {
    private static final String PLAYER_HEADER = "timestamp,uuid,name,world,x,y,z,ping_ms,protocol,client_brand,locale,view_distance,simulation_distance,send_distance,sent_chunks";
    private static final String CONNECTION_HEADER = "timestamp,event,uuid,name,address,protocol,virtual_host,detail";
    private final Plugin plugin;
    private final TelemetryStore store;
    private final int intervalSeconds;
    private final BlackBoxSettings.Privacy privacy;
    private final boolean samplesEnabled;
    private final boolean connectionsEnabled;
    private final byte[] salt;
    private BukkitTask task;

    public PlayerMonitor(Plugin plugin, TelemetryStore store, int intervalSeconds,
                         BlackBoxSettings.Privacy privacy, boolean samplesEnabled,
                         boolean connectionsEnabled) throws IOException {
        this.plugin = plugin;
        this.store = store;
        this.intervalSeconds = intervalSeconds;
        this.privacy = privacy;
        this.samplesEnabled = samplesEnabled;
        this.connectionsEnabled = connectionsEnabled;
        this.salt = loadOrCreateSalt(plugin.getDataFolder().toPath().resolve("address-salt.bin"));
    }

    public void start() {
        if (!samplesEnabled) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::samplePlayers,
                intervalSeconds * 20L, intervalSeconds * 20L);
    }

    public void stop() { if (task != null) task.cancel(); }
    public void captureNow() { if (samplesEnabled) samplePlayers(); }

    private void samplePlayers() {
        Instant now = Instant.now();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location location = player.getLocation();
            store.append("players", PLAYER_HEADER, now, Csv.row(now.toEpochMilli(),
                    privacy.storePlayerUuids() ? player.getUniqueId() : "",
                    privacy.storePlayerNames() ? player.getName() : "",
                    privacy.storePlayerLocations() ? location.getWorld().getName() : "",
                    privacy.storePlayerLocations() ? round(location.getX()) : "",
                    privacy.storePlayerLocations() ? round(location.getY()) : "",
                    privacy.storePlayerLocations() ? round(location.getZ()) : "",
                    player.getPing(), player.getProtocolVersion(), safe(player.getClientBrandName()),
                    player.locale(), player.getClientViewDistance(), player.getSimulationDistance(),
                    player.getSendViewDistance(), player.getSentChunkKeys().size()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { connection("join", event.getPlayer(), ""); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { connection("quit", event.getPlayer(), event.getReason().name()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) { connection("kick", event.getPlayer(), plain(event.reason())); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePack(PlayerResourcePackStatusEvent event) {
        connection("resource_pack", event.getPlayer(), event.getStatus().name());
    }

    private void connection(String type, Player player, String detail) {
        if (!connectionsEnabled) return;
        Instant now = Instant.now();
        InetSocketAddress virtualHost = player.getVirtualHost();
        store.append("connections", CONNECTION_HEADER, now, Csv.row(now.toEpochMilli(), type,
                privacy.storePlayerUuids() ? player.getUniqueId() : "",
                privacy.storePlayerNames() ? player.getName() : "", address(player.getAddress()), player.getProtocolVersion(),
                virtualHost == null ? "" : virtualHost.getHostString() + ":" + virtualHost.getPort(), detail));
    }

    private String address(InetSocketAddress socket) {
        if (privacy.playerAddresses() == BlackBoxSettings.AddressMode.OFF || socket == null) return "";
        String raw = socket.getAddress() == null ? socket.getHostString() : socket.getAddress().getHostAddress();
        if (privacy.playerAddresses() == BlackBoxSettings.AddressMode.PLAIN) return raw;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            return "unavailable";
        }
    }

    private static byte[] loadOrCreateSalt(Path path) throws IOException {
        if (Files.exists(path)) return Files.readAllBytes(path);
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        Files.createDirectories(path.getParent());
        Files.write(path, value);
        return value;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }
}
