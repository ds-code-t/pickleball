package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnyParentContextTest {
    @Test
    void inAnyParentSetsTheScopeAndKeepsTheAncestorContext() throws Exception {
        LineData line = new SaveLine("in any parent");
        Phrase outer = new Phrase("in the \"Outer\" Button", ',', line, null, false);
        outer.isTopContext = true;
        Phrase inner = new Phrase("in the \"Inner\" Button", ',', line, outer, false);
        inner.isTopContext = true;
        Phrase any = new Phrase("in any parent", ',', line, inner, false);
        Phrase click = new Phrase("click the \"Go\" Button", '.', line, any, false);

        assertEquals("any-parent", any.contextScope);
        assertEquals("in", any.context);
        assertNotNull(outer.contextXPathy, outer.toString());
        assertNotNull(inner.contextXPathy, inner.toString());

        List<PhraseData> expanded = click.getPhraseContextList();
        assertTrue(contains(expanded, "Outer"), expanded.toString());
        assertTrue(contains(expanded, "Inner"), expanded.toString());

        Phrase stopped = new Phrase("click the \"Go\" Button", '.', line, inner, false);
        List<PhraseData> nearest = stopped.getPhraseContextList();
        assertTrue(contains(nearest, "Inner"), nearest.toString());
        assertFalse(contains(nearest, "Outer"), nearest.toString());

        Method process = Phrase.class.getDeclaredMethod("processContextPhrase");
        process.setAccessible(true);
        process.invoke(any);
    }

    private static boolean contains(List<PhraseData> phrases, String token) {
        for (PhraseData phrase : phrases) {
            if (phrase.text != null && phrase.text.contains(token)) return true;
        }
        return false;
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
