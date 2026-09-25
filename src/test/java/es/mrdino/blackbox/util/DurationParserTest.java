package es.mrdino.blackbox.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {
    @Test void parsesSupportedUnits() {
        assertEquals(Duration.ofMinutes(30), DurationParser.parse("30m"));
        assertEquals(Duration.ofHours(6), DurationParser.parse("6H"));
        assertEquals(Duration.ofDays(2), DurationParser.parse("2d"));
    }

    @Test void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0m"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("soon"));
    }
}
