package es.mrdino.blackbox.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {
    @Test
    void resolvesFullLocaleNamesAndYamlMessages() {
        assertEquals(Language.ENGLISH, Language.parse("en_US"));
        assertEquals(Language.SPANISH, Language.parse("es-ES"));
        assertEquals("You do not have permission.", Messages.english().get("command.permission"));
        assertEquals("No tienes permiso.", Messages.spanish().get("command.permission"));
    }

    @Test
    void translatesReportTextFromTheEnglishYamlCatalog() {
        String spanish = "## Diagnostico\n\nCausa probable: revisa el servidor.";
        String english = Messages.english().text(spanish);
        assertEquals("## Diagnostics\n\nProbable cause: revisa el servidor.", english);
        assertEquals(spanish, Messages.spanish().text(spanish));
    }
}
