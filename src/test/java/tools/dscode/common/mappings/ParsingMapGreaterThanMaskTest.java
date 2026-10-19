package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before/after goldens for {@code >} while a reference is resolved.
 * Plain comparison text stays literal. A saved value that contains {@code >}
 * must not close an outer reference.
 */
class ParsingMapGreaterThanMaskTest {

    @BeforeEach
    void resetMaps() {
        MappingProcessor.resetCommonMaps();
    }

    @Test
    void plainValuesWithGreaterThanStayLiteral() {
        ParsingMap map = new ParsingMap();
        assertGolden(map, "->", "->");
        assertGolden(map, "a > b", "a > b");
        assertGolden(map, "a>=b", "a>=b");
        assertGolden(map, "score >= 1", "score >= 1");
        assertGolden(map, "go -> home, a > b", "go -> home, a > b");
    }

    @Test
    void greaterThanInsideANestedReferenceDoesNotCloseTheOuter() {
        ParsingMap map = new ParsingMap();
        map.put("arrow", "->");
        map.put("spaced", "a > b");
        map.put("gte", "a>=b");
        map.put("mid", "L <arrow> M <spaced> R <gte>");
        map.put("outer", "pre <mid> post");

        assertGolden(map, "<arrow>", "->");
        assertGolden(map, "<spaced>", "a > b");
        assertGolden(map, "<gte>", "a>=b");
        assertGolden(map, "<mid>", "L -> M a > b R a>=b");
        assertGolden(map, "<outer>", "pre L -> M a > b R a>=b post");
        assertGolden(map, "pre <outer> post", "pre pre L -> M a > b R a>=b post post");
    }

    @Test
    void greaterThanInsideLegacyExpressionSyntaxStaysInTheExpression() {
        ParsingMap map = new ParsingMap();
        map.put("n", "5");
        map.put("cmp", "5>1");
        map.put("spacedCmp", "5 > 1");
        map.put("arrow", "->");

        assertGolden(map, "<{ 2 > 1 }>", "true");
        assertGolden(map, "<{ 2 >= 2 }>", "true");
        assertGolden(map, "<{ <n> > 1 }>", "true");
        assertGolden(map, "<{ <n> >= 5 }>", "true");
        assertGolden(map, "<{ <cmp> }>", "true");
        assertGolden(map, "<{ <spacedCmp> }>", "true");
        assertGolden(map, "~[~{ 2 > 1 }~]~", "true");
        assertGolden(map, "~[~{ <n> >= 5 }~]~", "true");
        assertGolden(map, "~[~{ <cmp> }~]~", "true");
        assertGolden(map, "~[~arrow~]~", "->");
        map.put("legacy", "see ~[~arrow~]~ / <gte>");
        map.put("gte", "a>=b");
        assertGolden(map, "<legacy>", "see -> / a>=b");
        assertGolden(map, "~[~legacy~]~", "see -> / a>=b");
    }

    @Test
    void wholeObjectReferenceStaysTheObject() {
        ParsingMap map = new ParsingMap();
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", "Ada");
        person.put("note", "a > b");
        map.put("person", person);
        List<String> symbols = List.of("->", "a > b", "a>=b");
        map.put("symbols", symbols);

        Object personResolved = map.resolveWholeValue("<person>");
        assertFalse(personResolved instanceof String, String.valueOf(personResolved));
        JsonNode personNode = (JsonNode) personResolved;
        assertEquals("Ada", personNode.path("name").asText());
        assertEquals("a > b", personNode.path("note").asText());

        Object symbolsResolved = map.resolveWholeValue("<symbols[]>");
        assertFalse(symbolsResolved instanceof String, String.valueOf(symbolsResolved));
        String symbolsText = String.valueOf(symbolsResolved);
        assertTrue(symbolsText.contains("->"), symbolsText);
        assertTrue(symbolsText.contains("a > b"), symbolsText);
        assertTrue(symbolsText.contains("a>=b"), symbolsText);
        assertEquals("a>=b", String.valueOf(map.resolveWholeValue("<symbols>")));

        String embedded = map.resolveWholeText("pre <person> post");
        assertFalse(embedded.contains("pre <person>"), embedded);
        assertTrue(embedded.contains("a > b"), embedded);
        assertTrue(embedded.contains("Ada"), embedded);
    }

    private static void assertGolden(ParsingMap map, String before, String after) {
        assertEquals(after, map.resolveWholeText(before), "before: " + before);
        assertEquals(after, String.valueOf(map.resolveWholeValue(before)), "value before: " + before);
    }
}
