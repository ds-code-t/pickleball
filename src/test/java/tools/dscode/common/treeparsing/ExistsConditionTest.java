package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.AssertionOperations;
import tools.dscode.common.treeparsing.preparsing.LineData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExistsConditionTest {
    @Test
    void elementExistsMatchesIsPresentAndRejectsAHiddenElement() {
        Phrase present = phrase("ensure the Button is present");
        assertEquals("displayed", present.getAssertion());
        assertEquals(AssertionOperations.DISPLAYED, present.assertionOperation);

        Phrase exists = phrase("ensure the Button exists");
        assertEquals("exists", exists.getAssertion());
        assertEquals(AssertionOperations.EXISTS, exists.assertionOperation);
        assertEquals(AssertionOperations.DISPLAYED, AssertionOperations.elementBranch(exists));
        assertTrue(AssertionOperations.matchesPresent(true));
        assertFalse(AssertionOperations.matchesPresent(false));
    }

    @Test
    void valueExistsRequiresASavedNonNullValue() {
        assertFalse(AssertionOperations.valueExists(null));
        assertFalse(AssertionOperations.valueExists(ValueWrapper.createValueWrapper((String) null)));
        assertFalse(AssertionOperations.valueExists(ValueWrapper.createValueWrapper("")));
        assertFalse(AssertionOperations.valueExists(ValueWrapper.createValueWrapper("   ")));
        assertFalse(AssertionOperations.valueExists(ValueWrapper.createValueWrapper("<gone>")));
        assertTrue(AssertionOperations.valueExists(ValueWrapper.createValueWrapper("saved")));
        assertTrue(AssertionOperations.valueExists(ValueWrapper.createValueWrapper("hello")));

        Phrase saved = phrase("ensure \"saved\" exists");
        assertEquals("exists", saved.getAssertion());
        assertEquals(AssertionOperations.EXISTS, saved.assertionOperation);
        assertNull(AssertionOperations.elementBranch(saved));
    }

    private static Phrase phrase(String text) {
        return new Phrase(text, '.', new SaveLine(text), null, false);
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
