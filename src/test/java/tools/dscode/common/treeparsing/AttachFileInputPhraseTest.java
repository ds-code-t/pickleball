package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.treeparsing.DefinitionContext.FILE_INPUT;

class AttachFileInputPhraseTest {
    @Test
    void plainAttachNamesInternalFileInput() {
        Phrase phrase = phrase("attach the \"testFile.pdf\" file");
        assertEquals("attach", phrase.getAction());
        assertEquals(ActionOperations.ATTACH, phrase.actionOperation);
        assertEquals(List.of(FILE_INPUT), htmlCategories(phrase));
        assertTrue(phrase.resolvedText.contains(FILE_INPUT), phrase.resolvedText);
        assertFalse(phrase.text.contains(FILE_INPUT));
        assertUpload(phrase, "testFile.pdf");
    }

    @Test
    void attachesAndCreateAndAttachUseTheSameInsertion() {
        Phrase attaches = phrase("attaches the \"testFile.pdf\" file");
        assertEquals("attach", attaches.getAction());
        assertEquals(ActionOperations.ATTACH, attaches.actionOperation);
        assertEquals(List.of(FILE_INPUT), htmlCategories(attaches));
        assertUpload(attaches, "testFile.pdf");

        Phrase created = phrase(", create and attach the \"area-c-note.txt\" file");
        assertEquals("create and attach", created.getAction());
        assertEquals(ActionOperations.CREATE_AND_ATTACH, created.actionOperation);
        assertEquals(List.of(FILE_INPUT), htmlCategories(created));
        assertUpload(created, "area-c-note.txt");
    }

    @Test
    void explicitCategoryAndPlaceholderAreNotReplaced() {
        Phrase named = phrase("attach the InternalFileInput \"customers.yaml\"");
        assertEquals(List.of(FILE_INPUT), htmlCategories(named));
        assertEquals(1, named.resolvedText.split(FILE_INPUT, -1).length - 1);
        assertUpload(named, "customers.yaml");

        Phrase fileCategory = phrase("attach the File \"testFile.pdf\"");
        assertEquals(List.of("File"), htmlCategories(fileCategory));
        assertFalse(fileCategory.resolvedText.contains(FILE_INPUT), fileCategory.resolvedText);

        Phrase placeholder = phrase("attach it");
        assertEquals(List.of(), htmlCategories(placeholder));
        assertTrue(placeholder.getElementMatches().stream().anyMatch(ElementMatch::isPlaceHolder));
        assertFalse(placeholder.resolvedText.contains(FILE_INPUT));
    }

    @Test
    void lowercaseFileIsNotACategory() {
        Phrase phrase = phrase("attach the \"testFile.pdf\" file");
        assertFalse(phrase.getElementMatches().stream().anyMatch(match -> "file".equals(match.category)));
        assertEquals(List.of(FILE_INPUT), htmlCategories(phrase));
    }

    @Test
    void deferredResolveInsertsInternalFileInput() {
        LineData line = new IdleLine();
        Phrase deferred = new Phrase(quoted("attach the \"testFile.pdf\" file"), '.', line, null, false);
        assertEquals(List.of(FILE_INPUT), htmlCategories(deferred));
        deferred.setPhraseParsingMap(new ParsingMap());
        deferred.resolveForExecution();
        assertEquals("attach", deferred.getAction());
        assertEquals(ActionOperations.ATTACH, deferred.actionOperation);
        assertEquals(List.of(FILE_INPUT), htmlCategories(deferred));
        assertTrue(deferred.resolvedText.contains(FILE_INPUT), deferred.resolvedText);
        assertUpload(deferred, "testFile.pdf");
    }

    private static void assertUpload(PhraseData phrase, String fileName) {
        List<ElementMatch> following = phrase.getElementMatchesFollowingOperation();
        assertEquals(FILE_INPUT, following.get(0).category, dump(phrase));
        assertTrue(following.size() > 1, dump(phrase));
        assertTrue(following.get(1).elementTypes.contains(ElementType.RETURNS_VALUE), dump(phrase));
        assertTrue(String.valueOf(following.get(1).defaultText).contains(fileName), dump(phrase));
    }

    private static List<String> htmlCategories(PhraseData phrase) {
        return phrase.getElementMatches().stream()
                .filter(match -> match.elementTypes.contains(ElementType.HTML_TYPE))
                .map(match -> match.category)
                .toList();
    }

    private static String dump(PhraseData phrase) {
        StringBuilder out = new StringBuilder();
        out.append("action=").append(phrase.getAction());
        out.append(" opIndex=").append(phrase.operationIndex);
        out.append(" body=").append(phrase.body);
        if (phrase.phraseNode != null) {
            out.append(" masked=").append(phrase.phraseNode.maskedText());
            for (MatchNode child : phrase.phraseNode.children()) {
                out.append("\n  child=").append(child.name());
                out.append(" start=").append(child.start);
                out.append(" orig=").append(child.originalText());
                out.append(" type=").append(child.getStringFromLocalState("type"));
                out.append(" text=").append(child.getStringFromLocalState("text"));
            }
        }
        out.append("\n");
        for (ElementMatch match : phrase.getElementMatches()) {
            out.append("  cat=").append(match.category);
            out.append(" start=").append(match.startIndex);
            out.append(" types=").append(match.elementTypes);
            out.append(" text=").append(match.defaultText);
            out.append(" full=").append(match.fullText);
            out.append("\n");
        }
        out.append(" following=").append(phrase.getElementMatchesFollowingOperation().size());
        return out.toString();
    }

    private static Phrase phrase(String text) {
        return new Phrase(quoted(text), '.', new IdleLine(), null, false);
    }

    /** LineData restores quotes as bookends before a phrase is parsed. */
    private static String quoted(String text) {
        return text.replace("\"", BOOK_END);
    }

    private static final class IdleLine extends LineData {
        private IdleLine() {
            super("idle");
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
