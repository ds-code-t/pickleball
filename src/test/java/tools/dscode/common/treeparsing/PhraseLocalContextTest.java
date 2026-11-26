package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.LineData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhraseLocalContextTest {
    @Test
    void localAndAllContextModesGateTheLoadingScan() {
        Phrase local = phrase("wait Loading with local context");
        assertEquals("local", local.contextMode);
        assertFalse(loading(local).searchesAllContexts());

        Phrase all = phrase("wait Loading with all contexts");
        assertEquals("all", all.contextMode);
        assertTrue(loading(all).searchesAllContexts());

        Phrase inherit = phrase("wait Loading");
        assertEquals("inherit", inherit.contextMode);
        assertTrue(loading(inherit).searchesAllContexts());
    }

    private static Phrase phrase(String text) {
        return new Phrase(text, '.', new SaveLine(text), null, false);
    }

    private static ElementMatch loading(Phrase phrase) {
        for (ElementMatch match : phrase.getElementMatches()) {
            if ("Loading".equals(match.category)) return match;
        }
        throw new AssertionError("no Loading match in " + phrase + " matches=" + phrase.getElementMatches());
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
