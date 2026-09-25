package es.mrdino.blackbox;

import es.mrdino.blackbox.i18n.Messages;
import es.mrdino.blackbox.inventory.InventoryService;
import es.mrdino.blackbox.model.ServerSample;
import es.mrdino.blackbox.monitor.ChunkScanner;
import es.mrdino.blackbox.monitor.ServerSampler;
import es.mrdino.blackbox.monitor.RegionCensusService;
import es.mrdino.blackbox.profile.ProfileService;
import es.mrdino.blackbox.report.ReportService;
import es.mrdino.blackbox.storage.TelemetryStore;
import es.mrdino.blackbox.util.DurationParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class BlackBoxCommand implements TabExecutor {
    private static final String PREFIX = "§8[§5BlackBox§8] §7";
    private final BlackBoxPlugin plugin;
    private final ServerSampler sampler;
    private final ChunkScanner scanner;
    private final TelemetryStore store;
    private final InventoryService inventory;
    private final ReportService reports;
    private final ProfileService profiles;
    private final RegionCensusService regions;
    private final Messages messages;

    public BlackBoxCommand(BlackBoxPlugin plugin, ServerSampler sampler, ChunkScanner scanner,
                           TelemetryStore store, InventoryService inventory, ReportService reports,
                           ProfileService profiles, RegionCensusService regions) {
        this.plugin = plugin;
        this.sampler = sampler;
        this.scanner = scanner;
        this.store = store;
        this.inventory = inventory;
        this.reports = reports;
        this.profiles = profiles;
        this.regions = regions;
        this.messages = Messages.forLanguage(plugin.settings().language());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blackbox.admin")) {
            sender.sendMessage(PREFIX + "§c" + messages.get("command.permission"));
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> status(sender);
            case "scan" -> {
                if (regions != null) regions.requestNow();
                sender.sendMessage(PREFIX + (scanner.requestScan() ? messages.get("command.scan.queued") : messages.get("command.scan.busy")));
            }
            case "report" -> report(sender, args);
            case "profile" -> profile(sender, args);
            case "help" -> help(sender);
            default -> sender.sendMessage(PREFIX + "§f" + messages.get("command.unknown"));
        }
        return true;
    }

    private void status(CommandSender sender) {
        ServerSample sample = sampler.latest();
        ChunkScanner.ScanSummary scan = scanner.latest();
        if (sample == null) {
            sender.sendMessage(PREFIX + messages.get("command.waiting"));
        } else {
            double heap = 100.0 * sample.heapUsedBytes() / Math.max(1, sample.heapMaxBytes());
            sender.sendMessage(PREFIX + messages.text("TPS §f" + one(sample.tps1m()) + "§7 · MSPT §f" + one(sample.averageMspt())
                    + "§7 · tick p95 §f" + one(sample.tickP95Ms()) + " ms§7 · CPU §f" + one(sample.processCpuPercent())
                    + "%§7 · heap §f" + one(heap) + "%"));
            sender.sendMessage(PREFIX + messages.text("Jugadores §f" + sample.players() + "§7 · chunks §f" + sample.loadedChunks()
                    + "§7 · entidades escaneadas §f" + sample.scannedEntities()));
        }
        sender.sendMessage(PREFIX + messages.text("Ultimo escaneo: §f" + scan.chunks() + " chunks§7, §f" + scan.entities()
                + " entidades§7, §f" + scan.blockEntities() + " block entities§7 · trabajo real §f"
                + one(scan.workMicros() / 1000d) + " ms§7 · chunk max §f" + one(scan.maxChunkMicros() / 1000d) + " ms"));
        sender.sendMessage(PREFIX + messages.text("Cola de escritura §f" + store.queuedRows() + "§7 · filas descartadas §f" + store.droppedRows()
                + "§7 · perfil JFR " + (profiles.isActive() ? "§aactivo" : "§finactivo")));
        if (regions != null) {
            RegionCensusService.Summary disk = regions.latest();
            sender.sendMessage(PREFIX + messages.text("Disco: §f" + disk.terrainChunks() + " chunks de terreno§7 · §f"
                    + disk.entityChunks() + " chunks con datos de entidades§7 · §f" + disk.regions() + " regiones"));
        }
    }

    private void report(CommandSender sender, String[] args) {
        Duration period;
        try { period = DurationParser.parse(args.length >= 2 ? args[1] : "1h"); }
        catch (IllegalArgumentException error) {
            sender.sendMessage(PREFIX + "§c" + messages.text(error.getMessage()));
            return;
        }
        if (period.compareTo(Duration.ofDays(plugin.settings().retentionDays())) > 0) {
            sender.sendMessage(PREFIX + "§c" + messages.get("command.period-too-long", plugin.settings().retentionDays()));
            return;
        }
        var live = inventory.captureLive();
        sender.sendMessage(PREFIX + messages.get("command.report.generating", "§f" + (args.length >= 2 ? args[1] : "1h") + "§7"));
        reports.generate(period, live).whenComplete((path, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = rootCause(error);
                plugin.getLogger().log(Level.SEVERE, messages.text("No se pudo generar el informe"), cause);
                sender.sendMessage(PREFIX + "§c" + messages.get("command.report.error", rootMessage(error)));
            }
            else sender.sendMessage(PREFIX + messages.get("command.report.ready", "§f" + relative(path)));
        }));
    }

    private void profile(CommandSender sender, String[] args) {
        int seconds = 60;
        if (args.length >= 2) {
            try { seconds = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) {
                sender.sendMessage(PREFIX + "§c" + messages.get("command.profile.seconds"));
                return;
            }
        }
        try {
            ProfileService.StartResult result = profiles.start(seconds);
            sender.sendMessage(PREFIX + messages.get("command.profile.started", "§f" + result.seconds() + "§7", "§f" + relative(result.jfr())));
        } catch (Exception error) {
            sender.sendMessage(PREFIX + "§c" + messages.get("command.profile.error", rootMessage(error)));
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§5BlackBox §7— " + messages.get("command.help.title"));
        sender.sendMessage("§f/blackbox status §8- §7" + messages.get("command.help.status"));
        sender.sendMessage("§f/blackbox scan §8- §7" + messages.get("command.help.scan"));
        sender.sendMessage("§f/blackbox report [30m|6h|2d] §8- §7" + messages.get("command.help.report"));
        sender.sendMessage("§f/blackbox profile [seconds] §8- §7" + messages.get("command.help.profile"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("blackbox.admin")) return List.of();
        if (args.length == 1) return filter(List.of("status", "scan", "report", "profile", "help"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) return filter(List.of("30m", "1h", "6h", "1d", "7d"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) return filter(List.of("30", "60", "120", "300"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.startsWith(lower)).toList();
    }
    private String relative(Path path) {
        try { return plugin.getServerRoot().relativize(path.toAbsolutePath().normalize()).toString(); }
        catch (IllegalArgumentException ignored) { return path.toString(); }
    }
    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String rootMessage(Throwable error) {
        Throwable current = rootCause(error);
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
