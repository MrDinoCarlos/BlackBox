package es.mrdino.blackbox.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvTest {
    @Test void roundTripsSpecialCharacters() {
        String encoded = Csv.row("normal", "comma,value", "a \"quote\"", "two\nlines", "");
        assertEquals(List.of("normal", "comma,value", "a \"quote\"", "two\nlines", ""), Csv.parse(encoded));
    }
}
