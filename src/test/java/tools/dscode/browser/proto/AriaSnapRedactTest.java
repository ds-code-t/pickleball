package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HCR-07: aria-snap printed {@code textbox "Password": secret1}. Secret values and secret-named textboxes are masked. */
class AriaSnapRedactTest {
    static final String CONSUMER = String.join("\n",
            "- heading \"Sign in\" [level=1]",
            "- textbox \"User name\": ann",
            "- textbox \"Password\": secret1",
            "- button \"Sign in\"");

    @Test
    void passwordValueIsMaskedInDefaultMode() {
        ArrayNode redacted = ProtoJson.MAPPER.createArrayNode();
        String out = InputCommands.redactAria(CONSUMER, List.of("secret1"), redacted);
        assertFalse(out.contains("secret1"), out);
        assertTrue(out.contains("- textbox \"Password\": " + ProtoRedact.MASK), out);
        assertTrue(out.contains("- textbox \"User name\": ann"), "non-secret values stay: " + out);
        assertEquals("aria:Password", redacted.get(0).asText());
    }

    @Test
    void aiModeRefAndBoxesKeepTheirPlaceAndValueIsMasked() {
        String ai = "- textbox \"Password\" [ref=e5] [box=10,20,200,30]: secret1\n- textbox \"Code\" [ref=e6]: 4711x";
        String out = InputCommands.redactAria(ai, List.of("secret1", "4711x"), null);
        assertEquals("- textbox \"Password\" [ref=e5] [box=10,20,200,30]: " + ProtoRedact.MASK
                + "\n- textbox \"Code\" [ref=e6]: " + ProtoRedact.MASK, out);
    }

    @Test
    void secretNamedTextboxIsMaskedEvenWhenTheValueWasNotRead() {
        String out = InputCommands.redactAria("- textbox \"Password\": hunter22\n- textbox \"API key\": abc123", List.of(), null);
        assertFalse(out.contains("hunter22"), out);
        assertFalse(out.contains("abc123"), out);
    }

    @Test
    void quotedValueAndValueEchoedElsewhereAreMasked() {
        String out = InputCommands.redactAria("- textbox \"Secret field\": \"a: b\"\n- text: your code is zq9-77", List.of("a: b", "zq9-77"), null);
        assertFalse(out.contains("a: b"), out);
        assertFalse(out.contains("zq9-77"), out);
    }

    @Test
    void ordinaryTextboxIsUnchanged() {
        String plain = "- textbox \"Search\": shoes\n- textbox: plain";
        assertEquals(plain, InputCommands.redactAria(plain, List.of(), null));
    }
}
