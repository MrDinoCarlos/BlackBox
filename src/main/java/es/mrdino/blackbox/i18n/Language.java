package es.mrdino.blackbox.i18n;

import java.util.Locale;

/** Supported languages for BlackBox generated and user-facing text. */
public enum Language {
    ENGLISH("en_US", Locale.US),
    SPANISH("es_ES", new Locale("es", "ES"));

    private final String code;
    private final Locale locale;

    Language(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    public String code() { return code; }
    public Locale locale() { return locale; }

    public static Language parse(String raw) {
        if (raw == null) return ENGLISH;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "es", "es_es", "spanish", "español", "espanol" -> SPANISH;
            case "en", "en_us", "en_gb", "english", "inglés", "ingles" -> ENGLISH;
            default -> ENGLISH;
        };
    }
}
