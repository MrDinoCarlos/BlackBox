package es.mrdino.blackbox.report;

import es.mrdino.blackbox.BlackBoxSettings;
import es.mrdino.blackbox.model.ServerSample;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Builds bounded cause/effect episodes from telemetry that shares the same time axis. */
final class PerformanceAnalyzer {
    private final BlackBoxSettings.Thresholds thresholds;

    PerformanceAnalyzer(BlackBoxSettings.Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    List<PdfReportData.PerformanceEpisode> analyze(List<ServerSample> rawSamples,
                                                    List<List<String>> eventRows,
                                                    List<List<String>> hotspotRows,
                                                    List<List<String>> lifecycleRows,
                                                    List<List<String>> warningRows,
                                                    List<List<String>> profileRows,
                                                    List<List<String>> profilePluginRows) {
        List<ServerSample> samples = rawSamples.stream().sorted(Comparator.comparing(ServerSample::timestamp)).toList();
        List<PdfReportData.PerformanceEpisode> result = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= samples.size(); i++) {
            boolean bad = i < samples.size() && isBad(samples.get(i));
            if (bad && start < 0) start = i;
            if ((!bad || i == samples.size()) && start >= 0) {
                int end = i - 1;
                result.add(build(samples, start, end, i < samples.size() ? i : -1, eventRows, hotspotRows,
                        lifecycleRows, warningRows, profileRows, profilePluginRows));
                start = -1;
            }
        }
        return result.stream().sorted(Comparator.comparing(PdfReportData.PerformanceEpisode::startedAt)).toList();
    }

    private boolean isBad(ServerSample sample) {
        return sample.tps1m() >= 0 && sample.tps1m() <= thresholds.lowTps()
                || sample.tickP95Ms() >= thresholds.highMspt();
    }

    private PdfReportData.PerformanceEpisode build(List<ServerSample> samples, int start, int end, int recoveryIndex,
                                                    List<List<String>> eventRows, List<List<String>> hotspotRows,
                                                    List<List<String>> lifecycleRows, List<List<String>> warningRows,
                                                    List<List<String>> profileRows, List<List<String>> profilePluginRows) {
        List<ServerSample> incident = samples.subList(start, end + 1);
        List<ServerSample> baseline = samples.subList(Math.max(0, start - 6), start);
        List<ServerSample> recovery = recoveryIndex < 0 ? List.of()
                : samples.subList(recoveryIndex, Math.min(samples.size(), recoveryIndex + 6));
        ServerSample worst = incident.stream().max(Comparator.comparingDouble(this::badness)).orElseThrow();
        Instant from = incident.getFirst().timestamp();
        Instant recoveredAt = recoveryIndex < 0 ? null : samples.get(recoveryIndex).timestamp();
        Instant through = recoveredAt == null ? incident.getLast().timestamp() : recoveredAt;
        long sampleGap = medianGapMillis(samples);
        Instant evidenceEnd = through.plusMillis(Math.max(sampleGap, 5_000));

        List<Candidate> candidates = new ArrayList<>();
        metricCandidates(candidates, baseline, incident);
        eventCandidates(candidates, eventRows, hotspotRows, from, evidenceEnd, sampleGap);
        lifecycleCandidates(candidates, lifecycleRows, from, evidenceEnd, worst.timestamp(), recoveryIndex >= 0);
        warningCandidates(candidates, warningRows, from.minusSeconds(15), evidenceEnd.plusSeconds(15));
        profileCandidates(candidates, profileRows, profilePluginRows, from.minusSeconds(15), evidenceEnd.plusSeconds(90));
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());

        String cause = candidates.isEmpty()
                ? "No hay una causa dominante en la telemetria disponible"
                : candidates.getFirst().label();
        String evidence = candidates.isEmpty()
                ? "El episodio queda delimitado, pero necesita un perfil JFR durante su reproduccion para atribuir codigo."
                : candidates.stream().limit(3).map(Candidate::evidence).reduce((a, b) -> a + " | " + b).orElse("");
        String location = candidates.stream().map(Candidate::location).filter(v -> !v.isBlank()).findFirst().orElse("Servidor");
        String recommendation = candidates.isEmpty()
                ? "Reproduce la carga y ejecuta /blackbox profile 60; compara el metodo dominante con esta ventana."
                : candidates.getFirst().recommendation();
        double top = candidates.isEmpty() ? 0 : candidates.getFirst().score();
        String confidence = candidates.stream().anyMatch(Candidate::direct) ? "ALTA (evidencia directa de log/JFR)"
                : top >= 6 ? "MEDIA (varias senales temporales coinciden)" : "BAJA (correlacion temporal)";

        double minTps = incident.stream().mapToDouble(ServerSample::tps1m).min().orElse(-1);
        double maxP95 = incident.stream().mapToDouble(ServerSample::tickP95Ms).max().orElse(-1);
        DiagnosticFinding.Severity severity = minTps <= thresholds.criticalTps()
                || maxP95 >= thresholds.highMspt() * 2 ? DiagnosticFinding.Severity.CRITICAL
                : DiagnosticFinding.Severity.WARNING;
        String trigger = minTps <= thresholds.lowTps() && maxP95 >= thresholds.highMspt()
                ? "TPS bajo y ticks lentos" : minTps <= thresholds.lowTps() ? "TPS bajo" : "Ticks lentos";
        long duration = Math.max(sampleGap, Duration.between(from, through).toMillis());
        return new PdfReportData.PerformanceEpisode(from, worst.timestamp(), recoveredAt, severity, trigger,
                minTps, maxP95, average(baseline, ServerSample::averageMspt),
                average(incident, ServerSample::averageMspt), average(recovery, ServerSample::averageMspt),
                duration, cause, evidence, location, confidence, recommendation);
    }

    private double badness(ServerSample sample) {
        return Math.max(0, thresholds.lowTps() - sample.tps1m()) * 10
                + Math.max(0, sample.tickP95Ms() - thresholds.highMspt());
    }

    private void metricCandidates(List<Candidate> out, List<ServerSample> before, List<ServerSample> during) {
        metric(out, "CPU del proceso", before, during, ServerSample::processCpuPercent, 12, "%",
                "Perfila los hilos Java y revisa los metodos de plugin con mas muestras.");
        metric(out, "CPU del sistema", before, during, ServerSample::systemCpuPercent, 15, "%",
                "Comprueba limites del contenedor y procesos vecinos del host.");
        metric(out, "heap JVM", before, during,
                s -> 100d * s.heapUsedBytes() / Math.max(1, s.heapMaxBytes()), 10, "%",
                "Relaciona el crecimiento con pausas GC; si no vuelve a bajar, captura un heap dump.");
        metric(out, "RAM fisica", before, during,
                s -> s.physicalMemoryBytes() <= 0 ? -1 : 100d * (s.physicalMemoryBytes() - s.freePhysicalMemoryBytes()) / s.physicalMemoryBytes(),
                8, "%", "Reduce presion de memoria o procesos vecinos y evita swap.");
        metric(out, "chunks cargados", before, during, s -> s.loadedChunks(), 20, "",
                "Localiza cargas/generacion de chunks y reduce view-distance o la fuente que los abre.");
        metric(out, "entidades escaneadas", before, during, s -> s.scannedEntities(), 50, "",
                "Inspecciona los chunks y tipos dominantes del episodio antes de retirar entidades.");
        metric(out, "jugadores conectados", before, during, s -> s.players(), 5, "",
                "Compara con la misma cantidad de jugadores para separar carga normal de una regresion.");

        double gcBefore = counterRate(before, ServerSample::gcTimeMs);
        double gcDuring = counterRate(during, ServerSample::gcTimeMs);
        if (gcDuring > Math.max(20, gcBefore * 1.8)) {
            out.add(new Candidate("Presion de memoria y pausas GC", 5 + gcDuring / Math.max(50, gcBefore + 1),
                    "GC paso de " + one(gcBefore) + " a " + one(gcDuring) + " ms/min", "",
                    "Revisa asignaciones en JFR, heap y caches; no amplíes memoria sin comprobar el patron.", false));
        }
    }

    private void metric(List<Candidate> out, String label, List<ServerSample> before, List<ServerSample> during,
                        ToDoubleFunction<ServerSample> metric, double meaningfulDelta, String suffix,
                        String recommendation) {
        double oldValue = average(before, metric), newValue = average(during, metric);
        double max = during.stream().mapToDouble(metric).filter(v -> Double.isFinite(v) && v >= 0).max().orElse(-1);
        if (!Double.isFinite(newValue) || newValue < 0) return;
        double delta = Double.isFinite(oldValue) ? newValue - oldValue : 0;
        if (delta < meaningfulDelta && !(label.contains("CPU") && max >= 90)) return;
        double score = 2 + Math.max(0, delta / Math.max(1, meaningfulDelta));
        out.add(new Candidate("Aumento de " + label, score,
                label + " " + display(oldValue, suffix) + " -> " + display(newValue, suffix)
                        + " (max " + display(max, suffix) + ")", "", recommendation, false));
    }

    private void eventCandidates(List<Candidate> out, List<List<String>> rows, List<List<String>> hotspots,
                                 Instant from, Instant to, long intervalMs) {
        String[] labels = {"cargas de chunks", "descargas de chunks", "spawns de entidades", "muertes de entidades",
                "cambios de redstone", "movimientos de hopper", "acciones de piston", "explosiones", "bloques rotos", "bloques colocados"};
        String[] eventKeys = {"chunk_load", "chunk_unload", "entity_spawn", "entity_death", "redstone",
                "inventory_move", "piston", "explosion", "block_break", "block_place"};
        long window = Math.max(intervalMs, Duration.between(from, to).toMillis());
        Instant baselineFrom = from.minusMillis(window);
        for (int column = 2; column < 12; column++) {
            long current = sumRows(rows, column, from, to);
            long previous = sumRows(rows, column, baselineFrom, from);
            if (current < 10 || current <= Math.max(previous * 2, previous + 10)) continue;
            String location = topHotspot(hotspots, eventKeys[column - 2], from, to);
            double score = 3 + Math.min(6, (double) current / Math.max(10, previous + 1));
            out.add(new Candidate("Pico de " + labels[column - 2], score,
                    current + " durante el episodio frente a " + previous + " en la ventana anterior"
                            + (location.isBlank() ? "" : "; foco " + location), location,
                    recommendationFor(eventKeys[column - 2], location), false));
        }
    }

    private void lifecycleCandidates(List<Candidate> out, List<List<String>> rows, Instant from, Instant to,
                                     Instant worst, boolean recovered) {
        Map<String, Long> spawns = new HashMap<>(), removals = new HashMap<>();
        Map<String, Long> recoveryRemovals = new HashMap<>();
        for (List<String> row : rows) {
            Instant time = time(row);
            if (time == null || time.isBefore(from) || time.isAfter(to) || row.size() < 12) continue;
            String location = row.get(6) + ":" + row.get(10) + ":" + row.get(11);
            String key = row.get(3) + " @ " + location;
            if ("spawn".equals(row.get(1))) spawns.merge(key, 1L, Long::sum);
            if ("remove".equals(row.get(1))) {
                removals.merge(key, 1L, Long::sum);
                if (!time.isBefore(worst)) recoveryRemovals.merge(key, 1L, Long::sum);
            }
        }
        topEntry(spawns).filter(e -> e.getValue() >= 20).ifPresent(e -> out.add(new Candidate(
                "Creacion concentrada de entidades", 4 + Math.log10(e.getValue()), e.getValue() + " spawns de " + e.getKey(),
                locationOf(e.getKey()), "Revisa el spawn reason y la fuente atribuida; limita frecuencia o poblacion.", false)));
        if (recovered) topEntry(recoveryRemovals).filter(e -> e.getValue() >= 20).ifPresent(e -> out.add(new Candidate(
                "La retirada de entidades coincide con la recuperacion", 4 + Math.log10(e.getValue()),
                "Se retiraron " + e.getValue() + " entidades " + e.getKey() + " desde el peor punto",
                locationOf(e.getKey()), "Confirma repitiendo la carga y retirando solo esa poblacion; compara MSPT antes/despues.", false)));
    }

    private void warningCandidates(List<Candidate> out, List<List<String>> rows, Instant from, Instant to) {
        for (List<String> row : rows) {
            Instant time = time(row);
            if (time == null || time.isBefore(from) || time.isAfter(to) || row.size() < 4) continue;
            String level = row.get(1).toUpperCase(Locale.ROOT);
            String message = row.get(3);
            boolean behind = message.toLowerCase(Locale.ROOT).contains("ticks behind")
                    || message.toLowerCase(Locale.ROOT).contains("can't keep up");
            double score = level.equals("SEVERE") ? 10 : behind ? 8 : 6;
            out.add(new Candidate(behind ? "El servidor confirmo retraso de ticks" : "Error coincidente: " + row.get(2),
                    score, level + " " + shorten(message, 180), row.get(2),
                    behind ? "Usa las demas evidencias del episodio y el JFR automatico para eliminar la fuente del bloqueo."
                            : "Corrige primero la excepcion causal y repite la prueba bajo la misma carga.", true));
        }
    }

    private void profileCandidates(List<Candidate> out, List<List<String>> profiles, List<List<String>> pluginRows,
                                   Instant from, Instant to) {
        for (List<String> row : profiles) {
            Instant time = time(row);
            if (time == null || time.isBefore(from) || time.isAfter(to) || row.size() < 5 || !"started".equals(row.get(1))) continue;
            String id = row.get(2);
            List<String> best = pluginRows.stream().filter(p -> p.size() >= 6 && id.equals(p.get(1)))
                    .max(Comparator.comparingLong(p -> number(p, 4))).orElse(null);
            if (best == null) continue;
            long attributed = number(best, 4), total = number(best, 5);
            double share = total <= 0 ? 0 : 100d * attributed / total;
            out.add(new Candidate("Plugin presente en el perfil: " + best.get(3), 9 + share / 20,
                    attributed + "/" + total + " muestras JFR contenian frames del plugin (" + one(share) + "%)",
                    best.get(3), "Abre el .jfr y corrige el metodo concreto que domina el hilo del servidor; valida con otro perfil.", true));
        }
    }

    private static long sumRows(List<List<String>> rows, int column, Instant from, Instant to) {
        long sum = 0;
        for (List<String> row : rows) {
            Instant time = time(row);
            if (time == null || time.isBefore(from) || !time.isBefore(to) || row.size() <= column) continue;
            sum += number(row, column);
        }
        return sum;
    }

    private static String topHotspot(List<List<String>> rows, String event, Instant from, Instant to) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (List<String> row : rows) {
            Instant time = time(row);
            if (time == null || time.isBefore(from) || time.isAfter(to) || row.size() < 7 || !event.equals(row.get(2))) continue;
            values.merge(row.get(3) + ":" + row.get(4) + ":" + row.get(5), number(row, 6), Long::sum);
        }
        return topEntry(values).map(e -> e.getKey() + " (" + e.getValue() + ")").orElse("");
    }

    private static String recommendationFor(String event, String location) {
        String where = location.isBlank() ? "el foco indicado en hotspots.csv" : location;
        if (event.equals("entity_spawn") || event.equals("entity_death")) return "Inspecciona " + where + " y la fuente/tipo en entity-summary.csv; reduce una fuente cada vez.";
        if (event.equals("inventory_move") || event.equals("redstone") || event.equals("piston")) return "Inspecciona " + where + ", detiene temporalmente la instalacion y compara eventos/min y MSPT.";
        if (event.contains("chunk")) return "Revisa quien carga " + where + ", tickets de plugin y generacion; limita precarga o teletransporte masivo.";
        return "Inspecciona " + where + " y reproduce el episodio cambiando una sola fuente.";
    }

    private static double average(List<ServerSample> samples, ToDoubleFunction<ServerSample> metric) {
        return samples.stream().mapToDouble(metric).filter(v -> Double.isFinite(v) && v >= 0).average().orElse(Double.NaN);
    }

    private static double counterRate(List<ServerSample> samples, java.util.function.ToLongFunction<ServerSample> metric) {
        if (samples.size() < 2) return 0;
        long increase = 0, previous = -1;
        for (ServerSample sample : samples) {
            long current = metric.applyAsLong(sample);
            if (previous >= 0 && current >= previous) increase += current - previous;
            previous = current;
        }
        long millis = Math.max(1, Duration.between(samples.getFirst().timestamp(), samples.getLast().timestamp()).toMillis());
        return increase * 60_000d / millis;
    }

    private static long medianGapMillis(List<ServerSample> samples) {
        if (samples.size() < 2) return 5_000;
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < samples.size(); i++) gaps.add(Math.max(1, Duration.between(samples.get(i - 1).timestamp(), samples.get(i).timestamp()).toMillis()));
        gaps.sort(Long::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    private static java.util.Optional<Map.Entry<String, Long>> topEntry(Map<String, Long> values) {
        return values.entrySet().stream().max(Map.Entry.comparingByValue());
    }

    private static Instant time(List<String> row) {
        try { return Instant.ofEpochMilli(Long.parseLong(row.getFirst())); }
        catch (RuntimeException ignored) { return null; }
    }

    private static long number(List<String> row, int index) {
        try { return Long.parseLong(row.get(index)); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static String locationOf(String entityAtLocation) {
        int split = entityAtLocation.lastIndexOf(" @ ");
        return split < 0 ? "" : entityAtLocation.substring(split + 3);
    }

    private static String display(double value, String suffix) {
        return Double.isFinite(value) && value >= 0 ? one(value) + suffix : "sin base";
    }

    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 3) + "..."; }

    private record Candidate(String label, double score, String evidence, String location,
                             String recommendation, boolean direct) {}
}
