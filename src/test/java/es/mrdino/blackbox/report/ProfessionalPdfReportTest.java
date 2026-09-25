package es.mrdino.blackbox.report;

import es.mrdino.blackbox.inventory.InventorySnapshot;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfessionalPdfReportTest {
    @Test
    void createsReadableMultipageReport(@TempDir Path directory) throws Exception {
        InventorySnapshot live = new InventorySnapshot(Instant.now(),
                new InventorySnapshot.ServerInfo("Servidor de prueba", "Paper 1.21.4", "1.21.4", "21",
                        "Test JDK", "Test OS", 10, 8, 100),
                List.of(new InventorySnapshot.PluginInfo("BlackBox", "0.2.0", true,
                        "es.mrdino.blackbox.BlackBoxPlugin", "1.21.4", List.of("MrDino"), List.of(),
                        List.of(), List.of(), 2, 1, 12)),
                List.of(), List.of(new InventorySnapshot.WorldInfo("world", "NORMAL", "HARD", 1,
                        100, 2, 10, 8, 60_000_000, true)), List.of(),
                InventorySnapshot.ResourcePackInfo.none());
        PdfReportData data = new PdfReportData(Instant.now(), Instant.now().minusSeconds(3600),
                Duration.ofHours(1), live, new InventorySnapshot.FileInventory(List.of(), List.of(), List.of(), false),
                List.of(), List.of(), List.of(), List.of(), new PdfReportData.EventRow(10, 8, 100, 20, 50, 40, 2, 1),
                List.of(new DiagnosticFinding(DiagnosticFinding.Severity.WARNING, "Prueba de diagnostico",
                        "world:309:-68 /tp @s 4952 ~ -1080", "Evidencia reproducible", "Causa probable",
                        "Impacto sobre el tick, memoria y paquetes.", "Observado durante 42 minutos.",
                        "Accion recomendada con pasos concretos.", "Comparar MSPT y eventos antes y despues.")),
                List.of(new PdfReportData.CommandBlockRow("world:10:64:-5", "world", 10, 64, -5,
                        "REPEATING_COMMAND_BLOCK", "Granja", "execute as @e run summon zombie ~ ~ ~",
                        20, Instant.now().minusSeconds(1200), Instant.now(), 240,
                        Instant.now().minusSeconds(1200), Instant.now(), 20, "false", "NORTH",
                        "Potencial: hasta 20 ejecuciones/s; observado 12/min", "CRITICAL",
                        "Selector global y creacion repetida de entidades.",
                        "Limitar @e por tipo, distancia y cantidad; reducir la frecuencia.")),
                List.of(new PdfReportData.HotspotRow("entity_spawn", "world:0:0", 100)),
                List.of(new PdfReportData.ChunkLifecycleRow("world:0:0", 4, 3, 1, 5000, 10000)),
                List.of(new PdfReportData.EntityLifecycleRow("BLOCK_DISPLAY", 100, 90, 10, 400, 1200,
                        20_000, 60_000, "COMMAND", "PLUGIN", "world:309:-68", 75,
                        100, 100, 0, 10, 2, 400, "Command block world:10:64:-5")), List.of(),
                List.of(new PdfReportData.RegionRow("region", 2, 100, 8192, 0)),
                new PdfReportData.SessionSummary(2, 1, 0, 45_000,
                        List.of(new PdfReportData.EnvironmentChange(Instant.now().minusSeconds(1800),
                                "PluginNuevo", "PluginViejo", "BlackBox 0.4.0 -> 0.5.0"))),
                new PdfReportData.Comparison("Periodo anterior", "Periodo actual",
                        List.of(new PdfReportData.ComparisonMetric("MSPT medio", "28.0 ms", "17.0 ms",
                                "-39.3%", "Mejora")),
                        "El periodo actual mejora: baja el MSPT medio.", List.of("Anadido: PluginNuevo")),
                List.of(new PdfReportData.IncidentRow(Instant.now().minusSeconds(900),
                        DiagnosticFinding.Severity.CRITICAL, "entity_spawn", "world:309:-68",
                        "Se detecto una concentracion elevada de spawns en esta localizacion durante el pico de MSPT.")),
                List.of(new PdfReportData.PluginAnalysisRow("BlackBox", "0.5.0", 2, 1, 12,
                        14, 200, "Aparece en stacks JFR (7.0%); evidencia de ejecucion, no causalidad.")));
        Path pdf = directory.resolve("report.pdf");
        ProfessionalPdfReport.write(pdf, data);
        assertTrue(Files.size(pdf) > 5_000);
        try (var document = Loader.loadPDF(pdf.toFile())) {
            assertTrue(document.getNumberOfPages() >= 3);
            assertEquals("BlackBox - Informe de observabilidad de Servidor de prueba",
                    document.getDocumentInformation().getTitle());
            assertNotNull(document.getDocumentCatalog().getDocumentOutline());
            assertNotNull(document.getDocumentCatalog().getDocumentOutline().getFirstChild());
            Path previewDirectory = Path.of("build", "reports");
            Files.createDirectories(previewDirectory);
            Files.copy(pdf, previewDirectory.resolve("blackbox-pdf-preview.pdf"), StandardCopyOption.REPLACE_EXISTING);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                ImageIO.write(renderer.renderImageWithDPI(page, 110), "png",
                        previewDirectory.resolve("blackbox-pdf-preview-page-" + (page + 1) + ".png").toFile());
            }
        }
    }
}
