package es.mrdino.blackbox;

import es.mrdino.blackbox.diagnostics.ConfigurationDiagnostics;
import es.mrdino.blackbox.diagnostics.StartupDiagnostics;
import es.mrdino.blackbox.inventory.InventoryService;
import es.mrdino.blackbox.inventory.InventorySnapshot;
import es.mrdino.blackbox.i18n.Messages;
import es.mrdino.blackbox.monitor.ChunkScanner;
import es.mrdino.blackbox.monitor.CommandExecutionMonitor;
import es.mrdino.blackbox.monitor.ChunkLifecycleMonitor;
import es.mrdino.blackbox.monitor.EventCounters;
import es.mrdino.blackbox.monitor.EventMonitor;
import es.mrdino.blackbox.monitor.EntityLifecycleMonitor;
import es.mrdino.blackbox.monitor.LogCapture;
import es.mrdino.blackbox.monitor.PlayerMonitor;
import es.mrdino.blackbox.monitor.RegionCensusService;
import es.mrdino.blackbox.monitor.ServerSampler;
import es.mrdino.blackbox.monitor.SessionHistory;
import es.mrdino.blackbox.profile.ProfileService;
import es.mrdino.blackbox.report.ReportService;
import es.mrdino.blackbox.storage.TelemetryStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class BlackBoxPlugin extends JavaPlugin {
    private BlackBoxSettings settings;
    private TelemetryStore store;
    private ServerSampler serverSampler;
    private ChunkScanner chunkScanner;
    private PlayerMonitor playerMonitor;
    private ReportService reportService;
    private ProfileService profileService;
    private RegionCensusService regionCensus;
    private LogCapture logCapture;
    private InventoryService inventory;
    private SessionHistory sessionHistory;
    private Path serverRoot;
    private Instant sessionStarted;
    private boolean startupComplete;
    private StartupDiagnostics startupDiagnostics;
    private Messages messages;

    @Override
    public void onLoad() {
        messages = Messages.english();
        serverRoot = getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
        startupDiagnostics = new StartupDiagnostics(getLogger(),
                getDataFolder().toPath().resolve("startup-diagnostics.log"));
        startupDiagnostics.start();
        // Attach the live handler before reading the file so nothing is lost while both streams synchronize.
        startupDiagnostics.backfill(serverRoot.resolve("logs").resolve("latest.log"), 64L * 1024L * 1024L);
    }

    @Override
    public void onEnable() {
        sessionStarted = Instant.now();
        String startupStage = "preparación inicial";
        try {
            startupStage = "carga y validación de config.yml";
            saveDefaultConfig();
            // Parse explicitly so a malformed YAML file is diagnosed and never overwritten with defaults.
            new YamlConfiguration().load(getDataFolder().toPath().resolve("config.yml").toFile());
            getConfig().options().copyDefaults(true);
            ConfigurationDiagnostics.validate(getConfig(), getDataFolder().toPath().resolve("config.yml"),
                    startupDiagnostics);
            saveConfig();
            settings = BlackBoxSettings.load(getConfig());
            messages = Messages.forLanguage(settings.language());

            startupStage = "registro del monitor de inicio";
            getServer().getPluginManager().registerEvents(startupDiagnostics, this);

            startupStage = "inicialización del almacenamiento";
            store = new TelemetryStore(this, settings.queueCapacity(), settings.retentionDays(),
                    settings.shutdownFlushSeconds(), settings.maxTelemetryBytes(),
                    settings.storageSegmentMinutes(), settings.maxRowsPerStreamMinute(),
                    settings.maxTelemetryRowChars());
            startupStage = "creación de monitores y servicios";
            EventCounters counters = new EventCounters(settings.monitoring().eventHotspots());
            serverSampler = new ServerSampler(this, store, counters, settings.serverSampleSeconds());
            chunkScanner = new ChunkScanner(this, store, serverSampler, settings.chunkScanSeconds(),
                    settings.chunksPerTick(), settings.monitoring().detailedEntities(),
                    settings.monitoring().commandBlockDetails());
            playerMonitor = new PlayerMonitor(this, store, settings.playerSampleSeconds(), settings.privacy(),
                    settings.monitoring().playerSamples(), settings.monitoring().playerConnections());
            inventory = new InventoryService(this);
            sessionHistory = new SessionHistory(store, sessionStarted);
            reportService = new ReportService(getDataFolder().toPath(), serverRoot, settings.maxHashFileBytes(),
                    settings.inventory(), settings.thresholds(), settings.reports(), settings.maxReportBytes(), messages);
            profileService = new ProfileService(this, getDataFolder().toPath(), store, settings.profiling(),
                    settings.maxProfileBytes(), messages);
            serverSampler.setSampleListener(profileService::observe);
            if (settings.monitoring().offlineRegionCensus()) {
                regionCensus = new RegionCensusService(this, settings.monitoring().regionCensusMinutes(),
                        settings.monitoring().regionParseNbt(), settings.monitoring().regionIncludePoi());
            }
            if (settings.monitoring().serverWarnings()) {
                logCapture = new LogCapture(store, settings.monitoring().warningMessageMaxChars(),
                        settings.monitoring().warningStackMaxChars());
            }

            startupStage = "registro de listeners";
            getServer().getPluginManager().registerEvents(new EventMonitor(counters, settings.monitoring().events()), this);
            if (settings.monitoring().commandBlockDetails()) {
                getServer().getPluginManager().registerEvents(new CommandExecutionMonitor(store), this);
            }
            getServer().getPluginManager().registerEvents(playerMonitor, this);
            if (settings.monitoring().entityLifecycle()) {
                getServer().getPluginManager().registerEvents(
                        new EntityLifecycleMonitor(this, store, settings.privacy().storeEntityUuids()), this);
            }
            if (settings.monitoring().chunkLifecycle()) {
                getServer().getPluginManager().registerEvents(new ChunkLifecycleMonitor(this, store), this);
            }
            startupStage = "inicio de tareas de monitorización";
            serverSampler.start();
            chunkScanner.start();
            playerMonitor.start();
            if (regionCensus != null) regionCensus.start();
            startupStage = "registro del comando /blackbox";
            BlackBoxCommand executor = new BlackBoxCommand(this, serverSampler, chunkScanner, store,
                    inventory, reportService, profileService, regionCensus);
            PluginCommand command = getCommand("blackbox");
            if (command == null) throw new IllegalStateException(messages.text("Falta el comando blackbox en plugin.yml"));
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            startupStage = "captura del estado inicial";
            sessionHistory.record("start", inventory.captureLive());
            startupComplete = true;
            getLogger().info(messages.get("plugin.enabled", settings.serverSampleSeconds(), settings.chunkScanSeconds(), settings.retentionDays()));
        } catch (Throwable error) {
            if (startupDiagnostics != null) startupDiagnostics.reportFailure(error, startupStage);
            else getLogger().log(Level.SEVERE, messages.text("BlackBox no pudo iniciar durante " + startupStage), error);
            getServer().getPluginManager().disablePlugin(this);
            if (error instanceof VirtualMachineError fatal) throw fatal;
        }
    }

    @Override
    public void onDisable() {
        InventorySnapshot finalInventory = null;
        if (startupComplete) {
            getLogger().info(messages.get("plugin.shutdown.capture"));
            try {
                if (serverSampler != null) {
                    serverSampler.setSampleListener(null);
                    serverSampler.captureNow();
                }
                if (playerMonitor != null) playerMonitor.captureNow();
                if (inventory != null) finalInventory = inventory.captureLive();
                if (sessionHistory != null && finalInventory != null) sessionHistory.record("stop", finalInventory);
            } catch (Exception error) {
                getLogger().warning(messages.text("No se pudo capturar el estado final de la sesion: " + error.getMessage()));
            }
        }
        getLogger().info(messages.get("plugin.shutdown.monitors"));
        if (serverSampler != null) serverSampler.stop();
        if (chunkScanner != null) chunkScanner.stop();
        if (playerMonitor != null) playerMonitor.stop();
        if (regionCensus != null) regionCensus.close();
        if (profileService != null) profileService.close();
        if (logCapture != null) logCapture.close();
        if (store != null) {
            getLogger().info(messages.get("plugin.shutdown.storage", store.queuedRows(), settings.shutdownFlushSeconds()));
            store.close();
        }
        if (startupComplete && settings != null && settings.reports().automaticSessionOnShutdown()
                && reportService != null && finalInventory != null && sessionStarted != null) {
            int timeout = settings.reports().shutdownTimeoutSeconds();
            getLogger().info(messages.get("plugin.shutdown.report", timeout));
            Future<Path> report = reportService.generateSessionAsync(sessionStarted, finalInventory);
            try {
                Path pdf = report.get(timeout, TimeUnit.SECONDS);
                getLogger().info(messages.get("plugin.report-generated", pdf));
            } catch (TimeoutException error) {
                report.cancel(true);
                getLogger().warning(messages.text("El informe final superó " + timeout
                        + " s y se canceló para no bloquear el apagado del servidor."));
            } catch (InterruptedException error) {
                report.cancel(true);
                Thread.currentThread().interrupt();
                getLogger().warning(messages.text("El informe final fue interrumpido durante el apagado."));
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                getLogger().log(Level.SEVERE, messages.text("No se pudo generar el informe automatico de sesion"), cause);
            }
        }
        if (reportService != null) reportService.close();
        if (startupDiagnostics != null) startupDiagnostics.close();
        startupComplete = false;
    }

    public BlackBoxSettings settings() { return settings; }
    public Path getServerRoot() { return serverRoot; }
}
