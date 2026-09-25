package es.mrdino.blackbox.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern VALUE = Pattern.compile("^(\\d+)([mhd])$");
    private DurationParser() {}

    public static Duration parse(String raw) {
        Matcher matcher = VALUE.matcher(raw.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new IllegalArgumentException("Usa una duracion como 30m, 6h o 2d");
        long amount = Long.parseLong(matcher.group(1));
        if (amount < 1) throw new IllegalArgumentException("La duracion debe ser mayor que cero");
        if (amount > 5_256_000) throw new IllegalArgumentException("La duracion es demasiado grande");
        return switch (matcher.group(2)) {
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unidad desconocida");
        };
    }
}
