package es.mrdino.blackbox.diagnostics;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates values that Bukkit would otherwise silently replace or coerce. */
public final class ConfigurationDiagnostics {
    private static final Map<String, Range> RANGES = ranges();

    private ConfigurationDiagnostics() {}

    public static int validate(FileConfiguration config, Path file, StartupDiagnostics diagnostics) {
        ConfigurationSection defaults = config.getDefaults();
        if (defaults == null) return 0;
        Map<String, Integer> lines = yamlLines(file);
        int issues = 0;

        for (Map.Entry<String, Object> entry : defaults.getValues(true).entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            if (expected instanceof ConfigurationSection) continue;
            Object received = config.get(key);
            if (!compatible(received, expected)) {
                diagnostics.reportConfigurationIssue(key, location(file, lines.get(key)), received,
                        typeName(expected), String.valueOf(expected),
                        "Usa el mismo tipo de dato que en el config.yml incluido con el plugin.");
                issues++;
                continue;
            }
            Range range = RANGES.get(key);
            if (range != null && received instanceof Number number && !range.contains(number.doubleValue())) {
                double effective = range.clamp(number.doubleValue());
                diagnostics.reportConfigurationIssue(key, location(file, lines.get(key)), received,
                        range.description(), format(effective, received),
                        "El valor está fuera del rango seguro; corrígelo para evitar que sea limitado automáticamente.");
                issues++;
            }
        }

        Set<String> known = defaults.getValues(true).keySet();
        for (Map.Entry<String, Object> entry : config.getValues(true).entrySet()) {
            if (entry.getValue() instanceof ConfigurationSection || known.contains(entry.getKey())) continue;
            diagnostics.reportConfigurationIssue(entry.getKey(), location(file, lines.get(entry.getKey())),
                    entry.getValue(), "una clave reconocida", "ignorado",
                    "Elimina la clave o corrige su nombre; puede ser una opción antigua o un error tipográfico.");
            issues++;
        }

        String addressMode = config.getString("privacy.player-addresses", "hash");
        if (!Set.of("hash", "plain", "off").contains(addressMode == null ? "" : addressMode.toLowerCase())) {
            diagnostics.reportConfigurationIssue("privacy.player-addresses",
                    location(file, lines.get("privacy.player-addresses")), addressMode,
                    "hash, plain u off", "hash", "Elige uno de los tres valores admitidos.");
            issues++;
        }

        double critical = config.getDouble("diagnostics.critical-tps", 15.0);
        double low = config.getDouble("diagnostics.low-tps", 18.5);
        if (critical > low) {
            diagnostics.reportConfigurationIssue("diagnostics.critical-tps",
                    location(file, lines.get("diagnostics.critical-tps")), critical,
                    "un valor menor o igual que diagnostics.low-tps (" + low + ")", Double.toString(critical),
                    "El umbral crítico debe activarse a los mismos TPS o por debajo del umbral bajo.");
            issues++;
        }
        double weakCorrelation = config.getDouble("diagnostics.correlation-weak", 0.30);
        double warningCorrelation = config.getDouble("diagnostics.correlation-warning", 0.70);
        if (weakCorrelation > warningCorrelation) {
            diagnostics.reportConfigurationIssue("diagnostics.correlation-weak",
                    location(file, lines.get("diagnostics.correlation-weak")), weakCorrelation,
                    "un valor menor o igual que diagnostics.correlation-warning (" + warningCorrelation + ")",
                    Double.toString(weakCorrelation),
                    "El umbral débil debe ser menor o igual que el umbral de advertencia.");
            issues++;
        }
        return issues;
    }

    private static boolean compatible(Object received, Object expected) {
        if (received == null || expected == null) return received == expected;
        if (expected instanceof Number) return received instanceof Number;
        return expected.getClass().isInstance(received);
    }

    private static String typeName(Object expected) {
        if (expected instanceof Boolean) return "true o false";
        if (expected instanceof Number) return "un número";
        if (expected instanceof String) return "texto";
        return expected.getClass().getSimpleName();
    }

    private static String location(Path file, Integer line) {
        return file.toAbsolutePath() + (line == null ? "" : ":" + line);
    }

    private static String format(double value, Object like) {
        if (like instanceof Byte || like instanceof Short || like instanceof Integer || like instanceof Long) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private static Map<String, Integer> yamlLines(Path file) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return result;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            ArrayDeque<Node> parents = new ArrayDeque<>();
            for (int index = 0; index < lines.size(); index++) {
                String raw = lines.get(index);
                String trimmed = raw.stripLeading();
                if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("-")) continue;
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                int indent = raw.length() - trimmed.length();
                while (!parents.isEmpty() && parents.peekLast().indent() >= indent) parents.removeLast();
                String key = trimmed.substring(0, colon).trim();
                String prefix = parents.stream().map(Node::key).reduce((a, b) -> a + "." + b).orElse("");
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                result.put(path, index + 1);
                String rest = trimmed.substring(colon + 1).trim();
                if (rest.isEmpty() || rest.startsWith("#")) parents.addLast(new Node(indent, key));
            }
        } catch (Exception ignored) {
            // Bukkit's parser supplies the syntax error; exact line lookup is best effort only.
        }
        return result;
    }

    private static Map<String, Range> ranges() {
        Map<String, Range> values = new LinkedHashMap<>();
        add(values, "sampling.server-seconds", 1, 300);
        add(values, "sampling.chunk-scan-seconds", 10, 3600);
        add(values, "sampling.chunks-per-tick", 1, 128);
        add(values, "sampling.player-seconds", 1, 600);
        add(values, "monitoring.warning-message-max-chars", 256, 100000);
        add(values, "monitoring.warning-stack-max-chars", 1024, 1_000_000);
        add(values, "monitoring.region-census-minutes", 10, 10080);
        add(values, "inventory.max-files", 100, 1_000_000);
        add(values, "inventory.max-config-file-mib", 0, 64);
        add(values, "inventory.max-config-entries", 1, 10000);
        add(values, "inventory.max-zip-entries", 100, 2_000_000);
        add(values, "inventory.max-function-commands", 100, 1_000_000);
        add(values, "inventory.max-function-file-mib", 1, 64);
        add(values, "storage.retention-days", 1, 3650);
        add(values, "storage.queue-capacity", 1000, 1_000_000);
        add(values, "storage.shutdown-flush-seconds", 5, 300);
        add(values, "storage.max-telemetry-mib", 64, 10240);
        add(values, "storage.segment-minutes", 1, 60);
        add(values, "storage.max-rows-per-stream-minute", 100, 1_000_000);
        add(values, "storage.max-row-chars", 1024, 1_000_000);
        add(values, "storage.max-profiles-mib", 64, 10240);
        add(values, "storage.max-reports-mib", 64, 10240);
        add(values, "storage.max-hash-file-mib", 0, 1024);
        add(values, "diagnostics.low-tps", 0, 20);
        add(values, "diagnostics.critical-tps", 0, 20);
        add(values, "diagnostics.high-mspt", 1, 60000);
        add(values, "diagnostics.heap-percent", 1, 100);
        add(values, "diagnostics.process-cpu-percent", 1, 100);
        add(values, "diagnostics.high-ping-ms", 0, 60000);
        add(values, "diagnostics.entities-per-chunk", 1, 1_000_000);
        add(values, "diagnostics.block-entities-per-chunk", 1, 1_000_000);
        add(values, "diagnostics.hoppers-per-chunk", 1, 1_000_000);
        add(values, "diagnostics.command-blocks-per-chunk", 1, 1_000_000);
        add(values, "diagnostics.high-redstone-events", 1, Long.MAX_VALUE);
        add(values, "diagnostics.high-hopper-events", 1, Long.MAX_VALUE);
        add(values, "diagnostics.entity-churn-events", 1, Long.MAX_VALUE);
        add(values, "diagnostics.short-entity-life-ticks", 1, 72_000_000);
        add(values, "diagnostics.hotspot-events", 1, Long.MAX_VALUE);
        add(values, "diagnostics.function-risk-score", 0, 1_000_000);
        add(values, "diagnostics.correlation-min-samples", 3, 100000);
        add(values, "diagnostics.correlation-warning", 0, 1);
        add(values, "diagnostics.correlation-weak", 0, 1);
        add(values, "diagnostics.max-findings-per-category", 1, 100);
        add(values, "reports.latest-log-read-mib", 0, 2048);
        add(values, "reports.shutdown-timeout-seconds", 1, 60);
        add(values, "reports.latest-log-findings", 0, 1_000_000);
        add(values, "reports.latest-log-line-max-chars", 256, 100000);
        add(values, "reports.aggregate-hotspots", 1, 100000);
        add(values, "reports.aggregate-warnings", 1, 100000);
        add(values, "reports.markdown-chunks", 1, 10000);
        add(values, "reports.markdown-command-blocks", 1, 10000);
        add(values, "reports.pdf-correlations", 1, 1000);
        add(values, "reports.pdf-chunks", 1, 10000);
        add(values, "reports.pdf-players", 1, 10000);
        add(values, "reports.pdf-command-blocks", 1, 10000);
        add(values, "reports.pdf-functions", 1, 10000);
        add(values, "reports.pdf-hotspots", 1, 10000);
        add(values, "reports.pdf-chunk-lifecycle", 1, 10000);
        add(values, "reports.pdf-entity-types", 1, 10000);
        add(values, "reports.pdf-warnings", 1, 10000);
        add(values, "reports.pdf-incidents", 1, 10000);
        add(values, "reports.pdf-plugins", 1, 10000);
        add(values, "profiling.max-seconds", 10, 3600);
        add(values, "profiling.top-methods", 10, 10000);
        add(values, "profiling.automatic.tps-threshold", 0, 20);
        add(values, "profiling.automatic.tick-p95-ms", 1, 60000);
        add(values, "profiling.automatic.consecutive-samples", 1, 120);
        add(values, "profiling.automatic.seconds", 10, 3600);
        add(values, "profiling.automatic.cooldown-minutes", 1, 10080);
        return Map.copyOf(values);
    }

    private static void add(Map<String, Range> values, String key, double min, double max) {
        values.put(key, new Range(min, max));
    }

    private record Node(int indent, String key) {}
    private record Range(double min, double max) {
        boolean contains(double value) { return Double.isFinite(value) && value >= min && value <= max; }
        double clamp(double value) { return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min; }
        String description() { return "un número entre " + format(min) + " y " + format(max); }
        private static String format(double value) {
            return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
        }
    }
}
