package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PhraseSyntax;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PlaceHolderMatch;
import tools.dscode.common.treeparsing.preparsing.LineData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhraseSyntaxTest {
    @Test
    void missingValueIsASyntaxErrorNotANullFailure() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> PhraseSyntax.missing("value"));
        assertTrue(missing.getMessage().contains("syntax error: missing value"), missing.getMessage());

        PlaceHolderMatch unresolved = new PlaceHolderMatch(null);
        IllegalArgumentException placeholder = assertThrows(IllegalArgumentException.class, unresolved::getValues);
        assertTrue(placeholder.getMessage().contains("syntax error: missing element"), placeholder.getMessage());

        LineData line = new SaveLine("not-a-comma-line");
        Phrase save = new Phrase(", save E", '.', line, null, false);
        assertEquals(ActionOperations.SAVE, save.actionOperation, save.toString());
        IllegalArgumentException stripped = assertThrows(
                IllegalArgumentException.class,
                () -> save.actionOperation.execute(save)
        );
        assertEquals("syntax error: missing value", stripped.getMessage());
    }

    @Test
    void displaysIsASyntaxErrorNotANullOperation() {
        LineData line = new SaveLine("the Cell displays \"hello\"");
        Phrase cell = new Phrase("the Cell displays \"hello\"", '.', line, null, false);
        assertNull(cell.actionOperation);
        assertNull(cell.assertionOperation);

        IllegalArgumentException operation = assertThrows(IllegalArgumentException.class, cell::runOperation);
        assertClear(operation);

        IllegalArgumentException until = assertThrows(IllegalArgumentException.class, cell::runUntilOperation);
        assertClear(until);
    }

    private static void assertClear(IllegalArgumentException error) {
        String message = error.getMessage();
        assertTrue(message.contains("displays"), message);
        assertTrue(message.contains("no comparison or action word"), message);
        assertTrue(message.contains("equals"), message);
        assertTrue(message.contains("contains"), message);
        assertTrue(message.contains("starts with"), message);
        assertTrue(message.contains("ends with"), message);
        assertTrue(message.contains("displayed"), message);
        assertFalse(message.contains("\"operation\" is null"), message);
        assertTrue(error instanceof IllegalArgumentException);
    }

    private static final class SaveLine extends LineData {
        private SaveLine(String input) {
            super(input);
        }

        @Override
        public PhraseData runPhraseFromLine(PhraseData phrase) {
            return phrase;
        }

        @Override
        public void runPhrases() {
        }
    }
}
