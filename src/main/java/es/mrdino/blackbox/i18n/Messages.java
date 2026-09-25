package es.mrdino.blackbox.i18n;

import java.text.MessageFormat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Localized messages shared by commands, logs, reports and exported documents. */
public final class Messages {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm z")
            .withZone(ZoneId.systemDefault());
    private final Language language;
    private final Map<String, String> messages;
    private final Map<String, String> reportTranslations;

    private Messages(Language language) {
        this.language = language;
        this.messages = loadYaml("lang/" + language.code() + ".yml");
        this.reportTranslations = this.messages.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("report.")
                        && entry.getKey().substring("report.".length()).length() > 2)
                .sorted(Map.Entry.<String, String>comparingByKey(
                        Comparator.comparingInt(key -> key.length())).reversed())
                .collect(java.util.stream.Collectors.toMap(entry -> entry.getKey().substring("report.".length()),
                        Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public static Messages forLanguage(Language language) {
        return new Messages(language == null ? Language.ENGLISH : language);
    }

    public static Messages english() { return forLanguage(Language.ENGLISH); }
    public static Messages spanish() { return forLanguage(Language.SPANISH); }

    public Language language() { return language; }
    public Locale locale() { return language.locale(); }
    public String date(Instant instant) { return DATE.withLocale(locale()).format(instant); }

    public String get(String key, Object... arguments) {
        String template = messages.getOrDefault(key, key);
        return arguments.length == 0 ? template : MessageFormat.format(template, arguments);
    }

    /**
     * Translates generated report text while preserving server supplied values such as names,
     * commands and log messages. Spanish is the source language of the existing report engine.
     */
    public String text(String value) {
        if (value == null || language == Language.SPANISH) return value;
        String result = value;
        for (Map.Entry<String, String> entry : reportTranslations.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, String> loadYaml(String resource) {
        InputStream input = Messages.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) throw new IllegalStateException("Missing language resource: " + resource);
        Map<String, String> result = new LinkedHashMap<>();
        ArrayDeque<Node> parents = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
                int indent = raw.length() - raw.stripLeading().length();
                String line = raw.strip();
                int separator = yamlSeparator(line);
                if (separator <= 0) continue;
                String key = unquote(line.substring(0, separator).trim());
                String value = line.substring(separator + 1).trim();
                while (!parents.isEmpty() && parents.peekLast().indent >= indent) parents.removeLast();
                String prefix = parents.stream().map(Node::key).reduce((a, b) -> a + "." + b).orElse("");
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                if (value.isEmpty()) parents.addLast(new Node(indent, key));
                else result.put(path, unquote(value));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not load language resource: " + resource, error);
        }
        return result;
    }

    private static int yamlSeparator(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if ((current == '\'' || current == '"') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!quoted) { quoted = true; quote = current; }
                else if (quote == current) quoted = false;
            } else if (current == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private record Node(int indent, String key) {}
}
