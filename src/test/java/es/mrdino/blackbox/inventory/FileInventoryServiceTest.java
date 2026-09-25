package es.mrdino.blackbox.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileInventoryServiceTest {
    @Test
    void preservesDiagnosticValuesAndRedactsSecrets(@TempDir Path server) throws Exception {
        Path config = server.resolve("plugins/Test/config.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "view-distance: 10\ndatabase-password: super-secret\nenabled: true\n", StandardCharsets.UTF_8);
        Path function = server.resolve("world/datapacks/test/data/demo/function/tick.mcfunction");
        Files.createDirectories(function.getParent());
        Files.writeString(function, "execute as @e run particle flame ~ ~ ~\nschedule function demo:tick 1t\n", StandardCharsets.UTF_8);
        var inventory = new FileInventoryService(server, 1024 * 1024).scan();
        var entries = inventory.files().stream().filter(f -> f.path().endsWith("config.yml"))
                .findFirst().orElseThrow().configEntries();
        assertTrue(entries.stream().anyMatch(e -> e.key().equals("view-distance") && e.value().equals("10")));
        assertTrue(entries.stream().anyMatch(e -> e.key().equals("database-password") && e.redacted()
                && e.value().equals("<redacted>")));
        assertFalse(entries.stream().anyMatch(e -> e.value().contains("super-secret")));
        var analyzed = inventory.functions().stream().filter(f -> f.path().endsWith("tick.mcfunction")).findFirst().orElseThrow();
        assertTrue(analyzed.broadEntitySelectors() > 0);
        assertTrue(analyzed.schedules() > 0);
        assertTrue(analyzed.riskScore() > 0);
    }
}
