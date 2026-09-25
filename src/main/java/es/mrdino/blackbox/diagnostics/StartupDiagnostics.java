package es.mrdino.blackbox.diagnostics;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures and explains warnings and errors emitted while the server is starting. */
public final class StartupDiagnostics extends Handler implements Listener, AutoCloseable {
    private static final Pattern THREADED_LOG_LINE = Pattern.compile(
            "^\\[[^]]+] \\[(?:[^]]+/)?(WARN|WARNING|ERROR|SEVERE|FATAL)](?::)?\\s*(.*)$");
    private static final Pattern SIMPLE_LOG_LINE = Pattern.compile(
            "^\\[[^]]+\\s+(WARN|WARNING|ERROR|SEVERE|FATAL)](?::)?\\s*(.*)$");
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s*at\\s+([^\\s(]+\\([^\\r\\n]+\\))");
    private static final Pattern YAML_LINE = Pattern.compile("(?i)\\bline\\s+(\\d+)");
    private final Logger console;
    private final Logger root = Logger.getLogger("");
    private final Path output;
    private final ThreadLocal<Boolean> reporting = ThreadLocal.withInitial(() -> false);
    private final Map<String, Integer> occurrences = new HashMap<>();
    private volatile boolean active;

    public StartupDiagnostics(Logger console, Path output) {
        this.console = Objects.requireNonNull(console, "console");
        this.output = Objects.requireNonNull(output, "output");
        setLevel(Level.WARNING);
    }

    public synchronized void start() {
        if (active) return;
        active = true;
        root.addHandler(this);
        append("\n=== Diagnóstico de inicio " + Instant.now() + " ===\n");
    }

    /**
     * Reads warnings and errors already written in latest.log before this handler was attached.
     * The live handler is attached first, so messages emitted while this scan runs are not lost.
     */
    public void backfill(Path latestLog, long maxBytes) {
        if (!active || !Files.isRegularFile(latestLog) || maxBytes <= 0) return;
        reporting.set(true);
        int imported = 0;
        try {
            long size = Files.size(latestLog);
            long start = Math.max(0, size - maxBytes);
            byte[] bytes;
            try (var channel = Files.newByteChannel(latestLog, StandardOpenOption.READ)) {
                channel.position(start);
                int length = Math.toIntExact(size - start);
                var buffer = java.nio.ByteBuffer.allocate(length);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { /* read snapshot */ }
                bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0) {
                int firstLine = text.indexOf('\n');
                text = firstLine < 0 ? "" : text.substring(firstLine + 1);
            }
            text = currentSession(text);
            imported = importEntries(text);
            append("Sincronización retroactiva: " + imported + " entradas importadas de "
                    + latestLog.toAbsolutePath() + " (instantánea de " + size + " bytes).\n");
        } catch (Exception error) {
            append("Fallo de sincronización retroactiva: " + stackTrace(error));
        } finally {
            reporting.set(false);
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (!active || !isLoggable(record) || reporting.get()
                || safe(record.getMessage()).startsWith("[Diagnóstico de inicio]")) return;
        Diagnosis diagnosis = analyze(record.getThrown(), record.getMessage(), record.getLoggerName());
        String signature = signature(record.getLevel(), format(record));
        synchronized (this) {
            int count = occurrences.merge(signature, 1, Integer::sum);
            if (count > 1) return;
        }
        report(record.getLevel(), record.getLoggerName(), format(record), record.getThrown(), diagnosis, null);
    }

    public void reportFailure(Throwable error, String stage) {
        Diagnosis diagnosis = analyze(error, error == null ? "Fallo sin excepción" : error.getMessage(), console.getName());
        if ("CONFIGURACIÓN".equals(diagnosis.category()) && stage != null && stage.contains("config.yml")) {
            String details = stackTrace(error);
            Matcher line = YAML_LINE.matcher(details);
            String configLocation = output.resolveSibling("config.yml").toAbsolutePath().toString();
            if (line.find()) configLocation += ":" + line.group(1);
            diagnosis = new Diagnosis(diagnosis.category(), configLocation, diagnosis.cause(), diagnosis.solution());
        }
        report(Level.SEVERE, console.getName(), safe(error == null ? null : error.getMessage()), error, diagnosis, stage);
    }

    public void reportConfigurationIssue(String key, String location, Object received,
                                         String expected, String effectiveValue, String suggestion) {
        Diagnosis diagnosis = new Diagnosis("CONFIGURACIÓN", location,
                "El valor de '" + key + "' no cumple el formato o el rango esperado.",
                suggestion + " Valor efectivo que usará BlackBox: " + effectiveValue + ".");
        synchronized (this) {
            occurrences.merge("CONFIG|" + key + "|" + String.valueOf(received), 1, Integer::sum);
        }
        report(Level.WARNING, console.getName(),
                "Valor recibido para " + key + ": " + String.valueOf(received) + "; esperado: " + expected,
                null, diagnosis, "lectura de config.yml");
    }

    private int importEntries(String text) {
        int imported = 0;
        StringBuilder current = null;
        Level currentLevel = null;
        String currentSource = null;
        for (String line : text.split("\\R", -1)) {
            ParsedLine parsed = parseLogLine(line);
            if (parsed != null) {
                if (current != null) {
                    reportHistorical(currentLevel, currentSource, current.toString());
                    imported++;
                }
                currentLevel = parsed.level();
                String body = parsed.body();
                if (body.startsWith("[Diagnóstico de inicio]")) {
                    current = null;
                    continue;
                }
                currentSource = sourceFrom(body);
                current = new StringBuilder(body);
            } else if (current != null && !looksLikeNewLogEntry(line)) {
                current.append('\n').append(line);
            } else if (current != null) {
                reportHistorical(currentLevel, currentSource, current.toString());
                imported++;
                current = null;
            }
        }
        if (current != null) {
            reportHistorical(currentLevel, currentSource, current.toString());
            imported++;
        }
        return imported;
    }

    private void reportHistorical(Level level, String source, String original) {
        String firstLine = original.lines().findFirst().orElse("Entrada sin mensaje");
        String location = locationFromText(original, source);
        Diagnosis base = analyze(null, original, source);
        Diagnosis diagnosis = new Diagnosis(base.category(), location, base.cause(), base.solution());
        String signature = signature(level, firstLine);
        synchronized (this) {
            int count = occurrences.merge(signature, 1, Integer::sum);
            if (count > 1) return;
        }
        report(level, source, abbreviate(firstLine, 1200), null, diagnosis,
                "recuperado de logs/latest.log");
        append("Entrada original recuperada:\n" + original + "\n");
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        finish("Paper terminó de cargar el servidor (" + event.getType().name().toLowerCase(Locale.ROOT) + ")");
    }

    public synchronized void finish(String reason) {
        if (!active) return;
        long repeated = occurrences.values().stream().mapToLong(count -> Math.max(0, count - 1)).sum();
        append("=== Fin del diagnóstico: " + reason + ". Incidencias únicas: " + occurrences.size()
                + ", repeticiones agrupadas: " + repeated + " ===\n");
        active = false;
        root.removeHandler(this);
    }

    @Override public void flush() {}
    @Override public void close() { finish("BlackBox se detuvo antes de completar el inicio"); }

    private void report(Level level, String source, String message, Throwable error,
                        Diagnosis diagnosis, String stage) {
        reporting.set(true);
        try {
            StringBuilder persisted = new StringBuilder(512)
                    .append('\n').append(Instant.now()).append(" [").append(level.getName()).append("]\n")
                    .append("Tipo: ").append(diagnosis.category()).append('\n');
            if (stage != null) persisted.append("Etapa: ").append(stage).append('\n');
            persisted.append("Origen: ").append(diagnosis.location()).append('\n')
                    .append("Logger: ").append(safe(source)).append('\n')
                    .append("Error: ").append(emptyAs(message, "Sin mensaje")).append('\n')
                    .append("Causa probable: ").append(diagnosis.cause()).append('\n')
                    .append("Solución sugerida: ").append(diagnosis.solution()).append('\n');
            if (error != null) persisted.append("Stack trace completo:\n").append(stackTrace(error));
            append(persisted.toString());
        } finally {
            reporting.set(false);
        }
    }

    static Diagnosis analyze(Throwable error, String message, String loggerName) {
        Throwable rootCause = rootCause(error);
        String combined = (safe(message) + " " + (rootCause == null ? "" : safe(rootCause.getMessage())))
                .toLowerCase(Locale.ROOT);
        String type = rootCause == null ? "" : rootCause.getClass().getName();
        String location = locate(rootCause, loggerName);

        if (containsAny(type, "InvalidConfiguration", "YAMLException", "ScannerException", "ParserException")
                || containsAny(combined, "config.yml", "configuration", "configuración", "yaml", "mapping values")) {
            return new Diagnosis("CONFIGURACIÓN", location,
                    "El archivo de configuración tiene una clave, tipo de dato o sintaxis que el componente no puede interpretar.",
                    "Revisa la clave y la línea indicadas, compárala con el config.yml original y valida espacios, dos puntos, listas y comillas. Haz una copia antes de regenerar el archivo.");
        }
        if (containsAny(type, "NoClassDefFoundError", "ClassNotFoundException", "NoSuchMethodError",
                "AbstractMethodError", "UnsupportedClassVersionError", "IncompatibleClassChangeError")
                || containsAny(combined, "noclassdeffounderror", "classnotfoundexception", "nosuchmethoderror",
                "abstractmethoderror", "unsupportedclassversionerror", "incompatibleclasschangeerror")) {
            return new Diagnosis("DEPENDENCIA/VERSIÓN", location,
                    "El código intenta usar una clase o método que no existe en esta combinación de Java, Paper o plugins.",
                    "Comprueba la versión de Java y Paper, actualiza el plugin señalado y sus dependencias, y elimina copias antiguas o duplicadas del plugin.");
        }
        if (containsAny(type, "AccessDeniedException", "FileNotFoundException", "NoSuchFileException",
                "FileSystemException", "IOException") || containsAny(combined, "accessdeniedexception",
                "filenotfoundexception", "nosuchfileexception", "filesystemexception")) {
            return new Diagnosis("ARCHIVO/PERMISOS", location,
                    "No se pudo leer, crear o modificar un archivo necesario durante el inicio.",
                    "Comprueba que la ruta exista, tenga espacio libre y que el usuario del servidor tenga permisos de lectura y escritura. Verifica también que otro proceso no bloquee el archivo.");
        }
        if (containsAny(type, "SQLException", "SQLTransient", "SQLNonTransient")
                || containsAny(combined, "database", "jdbc", "sqlite", "mysql", "mariadb")) {
            return new Diagnosis("BASE DE DATOS", location,
                    "Falló una operación o conexión de base de datos durante el inicio.",
                    "Verifica host, puerto, credenciales, esquema y permisos; confirma que el servicio esté disponible y revisa la excepción causal del stack trace.");
        }
        if (containsAny(type, "ConnectException", "UnknownHostException", "SocketTimeoutException", "SSLException")
                || containsAny(combined, "connectexception", "unknownhostexception", "sockettimeoutexception", "sslexception")) {
            return new Diagnosis("RED", location,
                    "Una conexión externa falló, expiró o no pudo validar el destino.",
                    "Comprueba DNS, host, puerto, proxy/firewall y certificados, y confirma que el servicio remoto esté disponible.");
        }
        if (containsAny(type, "OutOfMemoryError", "StackOverflowError")
                || containsAny(combined, "outofmemoryerror", "stackoverflowerror")) {
            return new Diagnosis("RECURSOS JVM", location,
                    "La JVM agotó memoria o profundidad de pila mientras cargaba el servidor.",
                    "Conserva este stack trace, revisa memoria disponible y argumentos de Java. Si es StackOverflowError, busca llamadas recursivas en la primera clase de plugin indicada.");
        }
        if (rootCause instanceof NullPointerException || rootCause instanceof ClassCastException
                || rootCause instanceof IndexOutOfBoundsException || rootCause instanceof IllegalStateException
                || rootCause instanceof IllegalArgumentException || type.endsWith("Error")
                || containsAny(combined, "nullpointerexception", "classcastexception",
                "indexoutofboundsexception", "illegalstateexception", "illegalargumentexception")) {
            return new Diagnosis("CÓDIGO", location,
                    "Una condición no prevista en el código produjo la excepción " + simpleName(type) + ".",
                    "Actualiza primero el plugin propietario de la clase indicada. Si desarrollas ese código, corrige la primera línea propia del stack trace y valida allí los valores de entrada y el estado requerido.");
        }
        return new Diagnosis(error == null ? "MENSAJE DE INICIO" : "CÓDIGO/ENTORNO", location,
                error == null
                        ? "El componente registró una advertencia o error sin adjuntar una excepción; la causa exacta depende del mensaje original."
                        : "La excepción " + simpleName(type) + " llegó hasta el sistema de logs durante el inicio.",
                "Revisa el primer bloque 'Caused by' y la primera línea perteneciente a un plugin en el stack trace. Actualiza ese componente y verifica su configuración; si se repite, adjunta este archivo al informar del problema.");
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && current.getCause() != null
                && current.getCause() != current && depth < 64; depth++) current = current.getCause();
        return current;
    }

    private static String locate(Throwable error, String loggerName) {
        if (error != null) {
            StackTraceElement fallback = null;
            for (StackTraceElement frame : error.getStackTrace()) {
                if (fallback == null) fallback = frame;
                String name = frame.getClassName();
                if (!isFrameworkClass(name)) {
                    return frame.toString();
                }
            }
            if (fallback != null) return fallback.toString();
        }
        return emptyAs(loggerName, "origen no indicado por el logger");
    }

    private static String locationFromText(String text, String fallback) {
        Matcher matcher = STACK_FRAME.matcher(text);
        String first = null;
        while (matcher.find()) {
            String frame = matcher.group(1);
            if (first == null) first = frame;
            String className = frame.substring(0, frame.indexOf('('));
            int method = className.lastIndexOf('.');
            if (method > 0) className = className.substring(0, method);
            if (!isFrameworkClass(className)) return frame;
        }
        return first == null ? emptyAs(fallback, "origen no indicado por el log") : first;
    }

    private static String currentSession(String text) {
        int marker = text.lastIndexOf("Running Java ");
        if (marker < 0) marker = text.lastIndexOf("Starting minecraft server version");
        if (marker < 0) return text;
        int line = text.lastIndexOf('\n', marker);
        return text.substring(line < 0 ? 0 : line + 1);
    }

    private static boolean looksLikeNewLogEntry(String line) {
        return line.matches("^\\[[^]]+](?: \\[[^]]+])?:.*");
    }

    private static ParsedLine parseLogLine(String line) {
        Matcher matcher = THREADED_LOG_LINE.matcher(line);
        if (!matcher.matches()) matcher = SIMPLE_LOG_LINE.matcher(line);
        if (!matcher.matches()) return null;
        return new ParsedLine(parseLevel(matcher.group(1)), matcher.group(2));
    }

    private static Level parseLevel(String value) {
        return switch (value) {
            case "WARN", "WARNING" -> Level.WARNING;
            default -> Level.SEVERE;
        };
    }

    private static String sourceFrom(String body) {
        if (body.startsWith("[")) {
            int end = body.indexOf(']');
            if (end > 1) return body.substring(1, end);
        }
        return "latest.log";
    }

    private static boolean isFrameworkClass(String name) {
        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("org.bukkit.") || name.startsWith("io.papermc.")
                || name.startsWith("net.minecraft.") || name.startsWith("com.destroystokyo.paper.")
                || name.startsWith("org.spigotmc.") || name.startsWith("org.yaml.snakeyaml.")
                || name.startsWith("com.google.") || name.startsWith("org.slf4j.");
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String signature(Level level, String message) {
        String firstLine = safe(message).lines().findFirst().orElse("").strip();
        return level.intValue() >= Level.SEVERE.intValue() ? "ERROR|" + firstLine : "WARNING|" + firstLine;
    }

    private synchronized void append(String text) {
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(output, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception writeError) {
            if (!reporting.get()) console.log(Level.WARNING,
                    "No se pudo escribir el diagnóstico de inicio en " + output + ": " + writeError.getMessage());
        }
    }

    private static String format(LogRecord record) {
        String message = safe(record.getMessage());
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) return message;
        try { return java.text.MessageFormat.format(message, parameters); }
        catch (IllegalArgumentException ignored) { return message; }
    }

    private static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle) || value.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String simpleName(String type) {
        if (type.isBlank()) return "desconocida";
        int separator = Math.max(type.lastIndexOf('.'), type.lastIndexOf('$'));
        return separator < 0 ? type : type.substring(separator + 1);
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String emptyAs(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }

    record Diagnosis(String category, String location, String cause, String solution) {}
    private record ParsedLine(Level level, String body) {}
}
