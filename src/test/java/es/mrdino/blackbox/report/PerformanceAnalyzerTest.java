package es.mrdino.blackbox.report;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.model.ServerSample;
import es.mrdino.blackbox.util.Csv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceAnalyzerTest {
    @Test
    void explainsDegradationAndRecoveryFromCoincidentSignals() {
        Instant base = Instant.parse("2026-09-25T10:00:00Z");
        List<ServerSample> samples = new ArrayList<>();
        for (int i = 0; i < 6; i++) samples.add(sample(base.plusSeconds(i * 5L), 20, 18, 25, 40, 100));
        for (int i = 6; i < 9; i++) samples.add(sample(base.plusSeconds(i * 5L), 14, 82, 130, 96, 420));
        for (int i = 9; i < 13; i++) samples.add(sample(base.plusSeconds(i * 5L), 20, 20, 28, 35, 90));
        List<List<String>> events = List.of(Csv.parse(Csv.row(base.plusSeconds(35).toEpochMilli(), 5,
                0, 0, 250, 0, 0, 0, 0, 0, 0, 0)));
        List<List<String>> hotspots = List.of(Csv.parse(Csv.row(base.plusSeconds(35).toEpochMilli(), 5,
                "entity_spawn", "world", 12, -3, 250)));

        var episodes = new PerformanceAnalyzer(thresholds()).analyze(samples, events, hotspots,
                List.of(), List.of(), List.of(), List.of());

        assertEquals(1, episodes.size());
        var episode = episodes.getFirst();
        assertEquals(base.plusSeconds(30), episode.startedAt());
        assertNotNull(episode.recoveredAt());
        assertTrue(episode.evidence().contains("CPU") || episode.evidence().contains("spawns"));
        assertTrue(episode.location().contains("world:12:-3"));
        assertEquals("MEDIA (varias senales temporales coinciden)", episode.confidence());
    }

    private static ServerSample sample(Instant time, double tps, double averageMspt, double p95,
                                       double cpu, int entities) {
        return new ServerSample(time, tps, tps, tps, averageMspt, averageMspt, p95, p95, p95,
                2_000_000_000L, 8_000_000_000L, cpu, cpu, 2, 80, 10, 100,
                5, 100, entities, 20_000_000_000L, 200_000_000L, 10_000_000L,
                0, 16_000_000_000L, 8_000_000_000L, 0, 0, 10_000_000_000L,
                20_000, 8, 60_000, 0, 100, 10_000, 0, 0);
    }

    private static BlackBoxSettings.Thresholds thresholds() {
        return new BlackBoxSettings.Thresholds(18.5, 15, 50, 90, 90,
                200, 100, 80, 32, 16, 100_000, 50_000, 10_000,
                1_200, 50_000, 1, 12, .70, .30, 5);
    }
}
