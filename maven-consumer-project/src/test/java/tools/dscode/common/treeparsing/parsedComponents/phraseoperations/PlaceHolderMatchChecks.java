package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlaceHolderMatchChecks {

    @Test
    void parsedItKeepsMatchNodeStartAfterTheAction() {
        MatchNode phrase = parsePhrase("click it");
        Integer operationIndex = (Integer) phrase.getFromLocalState("operationIndex");
        assertNotNull(operationIndex, "click should record an operationIndex");

        MatchNode itNode = placeholderElement(phrase);
        PlaceHolderMatch parsedIt = new PlaceHolderMatch(null, itNode);

        assertTrue(parsedIt.isPlaceHolder());
        assertEquals(itNode.start, parsedIt.startIndex);
        assertTrue(
                parsedIt.startIndex > operationIndex,
                "parsed it startIndex=" + parsedIt.startIndex
                        + " should follow click operationIndex=" + operationIndex
        );
    }

    @Test
    void synthesizedPlaceholderKeepsSentinelStart() {
        PlaceHolderMatch synthesized = new PlaceHolderMatch(null);
        assertTrue(synthesized.isPlaceHolder());
        assertEquals(-1, synthesized.startIndex);
        assertEquals(-1, synthesized.position);
    }

    @Test
    void trailingWaitDoesNotConsumeTheItPlaceholderNode() {
        MatchNode clickPhrase = parsePhrase("click it");
        MatchNode waitPhrase = parsePhrase("and wait 1 seconds");

        MatchNode itNode = placeholderElement(clickPhrase);
        Integer clickIndex = (Integer) clickPhrase.getFromLocalState("operationIndex");
        Integer waitIndex = (Integer) waitPhrase.getFromLocalState("operationIndex");
        assertNotNull(clickIndex);
        assertNotNull(waitIndex);

        PlaceHolderMatch parsedIt = new PlaceHolderMatch(null, itNode);
        assertTrue(parsedIt.startIndex > clickIndex);

        List<MatchNode> waitElements = waitPhrase.getOrderedChildren("elementMatch");
        assertEquals(1, waitElements.size());
        assertTrue(
                waitElements.getFirst().getStringFromLocalState("type")
                        .startsWith(ElementType.VALUE_TYPE_MATCH)
        );
    }

    @Test
    void ensureItPlacesThePlaceholderBeforeTheAssertion() {
        MatchNode phrase = parsePhrase("ensure it is displayed");
        Integer operationIndex = (Integer) phrase.getFromLocalState("operationIndex");
        assertNotNull(operationIndex, "displayed should record an operationIndex");

        PlaceHolderMatch parsedIt = new PlaceHolderMatch(null, placeholderElement(phrase));
        assertTrue(
                parsedIt.startIndex < operationIndex,
                "parsed it startIndex=" + parsedIt.startIndex
                        + " should precede displayed operationIndex=" + operationIndex
        );
    }

    @Test
    void explicitButtonIsNotAPlaceholder() {
        MatchNode phrase = parsePhrase("click the Submit Form Button");
        List<MatchNode> elements = phrase.getOrderedChildren("elementMatch");
        assertEquals(1, elements.size());
        assertEquals("Submit Form Button", elements.getFirst().getStringFromLocalState("type"));
    }

    private static MatchNode parsePhrase(String text) {
        MatchNode parsed = DefinitionContext.getNodeDictionary().parse(text);
        MatchNode phrase = parsed.getChild("phrase");
        assertNotNull(phrase, "expected a phrase node for: " + text);
        return phrase;
    }

    private static MatchNode placeholderElement(MatchNode phrase) {
        List<MatchNode> elements = phrase.getOrderedChildren("elementMatch");
        assertEquals(1, elements.size(), "expected one elementMatch in: " + phrase);
        MatchNode element = elements.getFirst();
        assertEquals(
                ElementType.PLACE_HOLDER_MATCH,
                element.getStringFromLocalState("type")
        );
        return element;
    }
}
