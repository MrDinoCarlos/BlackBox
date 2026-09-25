package es.mrdino.blackbox.report;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.inventory.InventorySnapshot;
import es.mrdino.blackbox.util.Csv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {
    @Test
    void commandWindowFallsBackToExecutionDatesWhenInventoryDatesAreMissing() {
        Instant firstExecution = Instant.parse("2026-09-24T10:00:00Z");
        Instant lastExecution = firstExecution.plusSeconds(90);
        var executionOnly = new PdfReportData.CommandBlockRow("world:1:64:2", "world", 1, 64, 2,
                "UNKNOWN", "@", "summon zombie ~ ~ ~", 0, null, null, 2,
                firstExecution, lastExecution, 0, "desconocido", "desconocido",
                "2 ejecuciones", "WARNING", "Crea entidades", "Limitar frecuencia");

        String window = assertDoesNotThrow(() -> ReportService.commandObservationWindow(List.of(executionOnly)));

        assertTrue(window.contains("1 min 30 s"));
    }

    @Test
    void generatesCompleteReportWithExecutionOnlyCommandBlock(@TempDir Path root) throws Exception {
        Path data = root.resolve("plugins/BlackBox");
        Path telemetry = data.resolve("telemetry");
        Path server = root.resolve("server");
        Files.createDirectories(telemetry);
        Files.createDirectories(server);
        Instant now = Instant.now();
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(
                telemetry.resolve("command-executions-regression.csv.gz")))) {
            String content = String.join("\n", List.of(
                    "timestamp,world,x,y,z,name,command",
                    Csv.row(now.minusSeconds(30).toEpochMilli(), "world", 1, 64, 2, "@", "summon zombie ~ ~ ~"))) + "\n";
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(data.resolve("startup-diagnostics.log"), """
                %s [SEVERE]
                Tipo: CONFIGURACIÓN
                Etapa: carga de mundos
                Origen: plugins/Multiverse-Core/worlds.yml:12
                Logger: Multiverse-Core
                Error: No se pudo cargar el mundo wbwdos
                Causa probable: El nombre o generador configurado para el mundo no es válido.
                Solución sugerida: Corrige la entrada del mundo y prueba de nuevo.
                """.formatted(now.minusSeconds(20)), StandardCharsets.UTF_8);

        var inventoryOptions = new BlackBoxSettings.InventoryOptions(true, true, true,
                1_000, 1024 * 1024, 200, 10_000, 10_000, 1024 * 1024);
        var thresholds = new BlackBoxSettings.Thresholds(18.5, 15, 50, 90, 90,
                200, 100, 80, 32, 16, 100_000, 50_000, 10_000,
                1_200, 50_000, 1, 12, .70, .30, 5);
        var reportOptions = new BlackBoxSettings.ReportOptions(true, 10, 1024 * 1024, 1_000, 4_000,
                500, 200, 50, 100, 20, 40, 50, 60, 50, 50, 40, 60, 40, 80, 100);
        var live = new InventorySnapshot(now,
                new InventorySnapshot.ServerInfo("Regression", "Paper", "1.21.4", "25", "Test", "Test",
                        10, 8, 20), List.of(), List.of(), List.of(), List.of(),
                InventorySnapshot.ResourcePackInfo.none());

        try (var reports = new ReportService(data, server, 1024 * 1024,
                inventoryOptions, thresholds, reportOptions, 64L * 1024 * 1024)) {
            Path pdf = reports.generate(Duration.ofHours(1), live).get(30, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(pdf));
            assertTrue(Files.size(pdf) > 5_000);
            assertTrue(Files.isRegularFile(pdf.getParent().resolve("command-block-analysis.csv")));
            assertTrue(Files.isRegularFile(pdf.getParent().resolve("command-aliases.csv")));
            assertTrue(Files.isRegularFile(pdf.getParent().resolve("file-config-entries.csv")));
            Path startupDiagnostics = pdf.getParent().resolve("startup-diagnostics.csv");
            assertTrue(Files.readString(startupDiagnostics).contains("Corrige la entrada del mundo"));
            assertTrue(Files.readString(pdf.getParent().resolve("report.md")).contains("Inicio: CONFIGURACIÓN"));
        }
    }
}
