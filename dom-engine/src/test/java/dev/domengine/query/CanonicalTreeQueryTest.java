package dev.domengine.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** xpath= and css= on the canonical tree. The index answers absolute tag[n] paths. */
class CanonicalTreeQueryTest {
    private static final String HTML = """
            <html><body>
              <button id="one">One</button>
              <button id="two">Two</button>
              <ul><li id="row"><a id="inner">x</a></li></ul>
              <button id="lead" class="primary extra">Lead</button>
              <span id="next" data-kind="note item">Next</span>
              <span id="later" data-lang="en-US">Later</span>
            </body></html>
            """;

    @Test
    void indexQualifiedXPathResolvesTheNthElement() {
        ObjectNode hit = Query.run(tree(), Map.of("xpath", "/html[1]/body[1]/button[2]"));
        assertEquals(List.of("two"), ids(hit));
        ObjectNode miss = Query.run(tree(), Map.of("xpath", "/html[1]/body[1]/button[9]"));
        assertTrue(miss.path("ok").asBoolean());
        assertEquals(0, miss.path("nodes").size());
    }

    @Test
    void xpathOneEvaluatesDescendantsIndexesAndBooleanPredicates() {
        DomNode root = tree();
        assertEquals(List.of("inner"), ids(Query.run(root, Map.of("xpath", "//li/a"))));
        assertEquals(List.of("two"), ids(Query.run(root, Map.of("xpath", "(//button)[2]"))));
        assertEquals(List.of("lead"), ids(Query.run(root, Map.of(
                "xpath", "//button[@id='lead' and @class='primary extra']"))));
        CdpException invalid = assertThrows(CdpException.class, () -> Query.run(root, Map.of("xpath", "//*[")));
        assertTrue(invalid.getMessage().contains("invalid"), invalid.getMessage());
    }

    @Test
    void cssCombinatorsAndAttributeOperatorsTheIndexRefuses() {
        DomNode root = tree();
        assertEquals(List.of("next"), ids(Query.run(root, Map.of("css", "button + span"))));
        assertEquals(List.of("later"), ids(Query.run(root, Map.of("css", "#lead ~ #later"))));
        assertEquals(List.of("next"), ids(Query.run(root, Map.of("css", "span[data-kind~=note]"))));
        assertEquals(List.of("lead"), ids(Query.run(root, Map.of("css", "[class~=primary]"))));
        assertEquals(List.of("later"), ids(Query.run(root, Map.of("css", "[data-lang|=en]"))));
        assertEquals(List.of("row"), ids(Query.run(root, Map.of("css", "ul > li"))));
    }

    @Test
    void cssPseudoClassesMatchElementSiblingsInTheSameTree() {
        DomNode root = HtmlTree.parse(PSEUDO_HTML);
        assertEquals(List.of("a"), ids(css(root, "li:first-child")));
        assertEquals(List.of("d"), ids(css(root, "li:last-child")));
        assertEquals(List.of("a", "c"), ids(css(root, "#list > li:nth-child(odd)")));
        assertEquals(List.of("b", "d"), ids(css(root, "#list > li:nth-child(even)")));
        assertEquals(List.of("a", "c"), ids(css(root, "#list > li:nth-child(2n + 1)")));
        assertEquals(List.of("a", "b"), ids(css(root, "#list > li:nth-child(-n+2)")));
        assertEquals(List.of("c"), ids(css(root, "#list > li:nth-child(3)")));
        assertEquals(List.of("a", "b", "c", "d"), ids(css(root, "#list > li:nth-child(n)")));
        assertEquals(List.of("a", "c"), ids(css(root, "#list > li:NTH-CHILD(2N + 1)")));
        assertEquals(List.of("d"), ids(css(root, "#list > li:nth-last-child(1)")));
        assertEquals(List.of("b", "d"), ids(css(root, "#list > li:nth-last-child(odd)")));
        assertEquals(List.of("c"), ids(css(root, "#list > li:nth-last-child(2)")));
        assertEquals(List.of("m1"), ids(css(root, "#mix > span:first-of-type")));
        assertEquals(List.of("m3"), ids(css(root, "#mix > span:last-of-type")));
        assertEquals(List.of("m3"), ids(css(root, "#mix > span:nth-of-type(2)")));
        assertEquals(List.of("m4"), ids(css(root, "#mix > p:nth-last-of-type(1)")));
        assertEquals(List.of("m2", "m4"), ids(css(root, "#mix > p:nth-of-type(n)")));
        assertEquals(List.of(), ids(css(root, "#mix > span:only-of-type")));
        assertEquals(List.of("ps"), ids(css(root, "#pair > span:only-of-type")));
        assertEquals(List.of("pp"), ids(css(root, "#pair > p:only-of-type")));
        assertEquals(List.of("solo"), ids(css(root, "#solo:only-child")));
        assertEquals(List.of("solo"), ids(css(root, "#solo:only-of-type")));
        assertEquals(List.of(), ids(css(root, "#list > li:only-child")));
        assertEquals(List.of("m1"), ids(css(root, "#m1:first-child")));
        assertEquals(List.of("empty"), ids(css(root, "#empty:empty")));
        assertEquals(List.of(), ids(css(root, "#texty:empty")));
        assertEquals(List.of(), ids(css(root, "#solo:empty")));
        assertEquals(List.of("top"), ids(css(root, "html:root")));
        assertEquals(List.of(), ids(css(root, "body:root")));
        assertEquals(List.of("box", "radio", "o2"), ids(css(root, ":checked")));
        assertEquals(List.of("box", "radio"), ids(css(root, "input:checked")));
        assertEquals(List.of("o2"), ids(css(root, "option:checked")));
        assertEquals(List.of(), ids(css(root, "#plain:checked")));
        assertEquals(List.of("box"), ids(css(root, "#box:checked:enabled")));
        assertEquals(List.of("go"), ids(css(root, "button:disabled")));
        assertEquals(List.of("ok"), ids(css(root, "button:enabled")));
        assertEquals(List.of("off"), ids(css(root, "#off:disabled")));
        assertEquals(List.of("fs"), ids(css(root, "#fs:disabled")));
        assertEquals(List.of("inside"), ids(css(root, "#inside:disabled")));
        assertEquals(List.of("legend-in"), ids(css(root, "#legend-in:enabled")));
        assertEquals(List.of(), ids(css(root, "div:enabled")));
        assertEquals(List.of(), ids(css(root, "div:disabled")));
        assertEquals(List.of("b", "d"), ids(css(root, "#list > li:not(.note)")));
        assertEquals(List.of("b", "c", "d"), ids(css(root, "#list > li:not(:first-child)")));
        assertEquals(List.of("b", "d"), ids(css(root, "#list > li:not(.note):nth-child(even)")));
        assertEquals(List.of("ok"), ids(css(root, "#ok:not([disabled])")));
        assertEquals(List.of(), ids(css(root, "#go:not([disabled])")));
        assertEquals(List.of("b"), ids(css(root, "#list > li:first-child + li")));
        assertEquals(List.of("b", "c", "d"), ids(css(root, "#list > li:first-child ~ li")));
        assertEquals(List.of("m2", "m4"), ids(css(root, "#mix > span:first-of-type ~ p")));
        assertEquals(List.of("a", "d"), ids(css(root, "#list > li:first-child, #list > li:last-child")));
        assertEquals(List.of("a"), ids(css(root, "#list > LI:FIRST-CHILD")));
        assertEquals(List.of("light"), ids(css(root, "#light:only-child")));
        assertEquals(List.of(), ids(css(root, "#bold:first-child")));
        assertEquals(List.of("bold"), ids(css(root, "#bold:first-child", "all")));
        assertEquals(List.of(), ids(css(root, "#s1:first-child", "all")));
        assertEquals(List.of("s1"), ids(css(root, "#s1:nth-child(2)", "all")));
        assertEquals(List.of("s1"), ids(css(root, "#s1:first-of-type", "all")));
        assertEquals(List.of("s2"), ids(css(root, "#s2:last-child", "all")));
        assertEquals(List.of("s2"), ids(css(root, "#s2:nth-of-type(2)", "all")));
        assertEquals(List.of("s2"), ids(css(root, "#s2:nth-last-of-type(1)", "all")));
        assertEquals(List.of("inner-root"), ids(pipeline(root, "frame=#child >> css=#inner-root:root")));
        assertEquals(List.of("framed"), ids(pipeline(root, "frame=#child >> css=#framed:first-child")));
        assertEquals(List.of(), ids(css(root, "#framed:first-child")));
        ObjectNode miss = Query.run(root, Map.of("css", "li#missing:first-child"));
        assertTrue(miss.path("ok").asBoolean());
        assertEquals(0, miss.path("nodes").size());
    }

    @Test
    void cssPseudoElementsAndUnsupportedPseudosAreErrors() {
        DomNode root = HtmlTree.parse("<html><body><p id='p'>x</p></body></html>");
        assertInvalid(root, "li:hover");
        assertInvalid(root, "li::before");
        assertInvalid(root, ":before");
        assertInvalid(root, "::before");
        assertInvalid(root, ":first-line");
        assertInvalid(root, "div:has(span)");
        assertInvalid(root, ":not(.a .b)");
        assertInvalid(root, ":not(:not(li))");
        assertInvalid(root, ":nth-child()");
        assertInvalid(root, ":nth-child(2 n)");
        assertInvalid(root, ":first-child()");
        assertInvalid(root, "zzz:hover");
        assertInvalid(root, ":not()");
        assertInvalid(root, ":not(span, div)");
    }

    private static final String PSEUDO_HTML = """
            <html id="top"><body>
              <ul id="list">
                <li id="a" class="note">A</li>
                <li id="b">B</li>
                <li id="c" class="note">C</li>
                <li id="d">D</li>
              </ul>
              <div id="mix">text<span id="m1">s</span><p id="m2">p</p><span id="m3">s</span><p id="m4">p</p></div>
              <div id="pair"><span id="ps">s</span><p id="pp">p</p></div>
              <p id="only"><span id="solo">S</span></p>
              <div id="empty"></div>
              <div id="texty">hi</div>
              <form>
                <input id="box" type="checkbox" checked>
                <input id="plain" type="checkbox">
                <input id="radio" type="radio" checked>
                <input id="off" type="text" disabled>
                <button id="go" disabled>Go</button>
                <button id="ok">Ok</button>
                <select id="sel">
                  <option id="o1">One</option>
                  <option id="o2" selected>Two</option>
                </select>
                <fieldset id="fs" disabled>
                  <legend><input id="legend-in" type="text"></legend>
                  <input id="inside" type="text">
                </fieldset>
              </form>
              <div id="host">
                <span id="light">L</span>
                <template shadowrootmode="open">
                  <b id="bold">B</b>
                  <span id="s1">one</span>
                  <span id="s2">two</span>
                </template>
              </div>
              <iframe id="child" srcdoc='<html id="inner-root"><body><p id="framed">Hi</p></body></html>'></iframe>
            </body></html>
            """;

    private static JsonNode css(DomNode root, String selector) {
        return Query.run(root, Map.of("css", selector));
    }

    private static JsonNode css(DomNode root, String selector, String pierce) {
        return Query.run(root, Map.of("css", selector, "pierce", pierce));
    }

    private static JsonNode pipeline(DomNode root, String pipeline) {
        return Query.run(root, Map.of("pipeline", pipeline));
    }

    private static void assertInvalid(DomNode root, String selector) {
        CdpException error = assertThrows(CdpException.class, () -> Query.run(root, Map.of("css", selector)));
        assertTrue(error.getMessage().contains("invalid css: " + selector), error.getMessage());
    }

    private static DomNode tree() {
        return HtmlTree.parse(HTML);
    }

    private static List<String> ids(JsonNode json) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) ids.add(node.path("attributes").path("id").asText());
        return ids;
    }
}
