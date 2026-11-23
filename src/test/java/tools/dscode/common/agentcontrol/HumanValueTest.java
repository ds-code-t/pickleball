package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quoted concrete values stay in the sentence and are painted as a chip. */
class HumanValueTest {
    @Test
    void quotesAValueAndKeepsTheInstructionAroundIt() {
        assertEquals("\"abc\"", HumanValue.quote("abc"));
        assertEquals("Enter \"abc\" in this textbox", HumanValue.distinguish("Enter abc in this textbox", "abc"));
        assertEquals("Enter \"abc\" in this textbox", HumanValue.distinguish("Enter \"abc\" in this textbox", "abc"));
        assertEquals("Enter \"a\\\"b\"", HumanValue.distinguish("Enter", "a\"b"));
    }

    @Test
    void htmlChipsTheQuotedSpanAndEscapesMarkup() {
        String html = HumanValue.html("Enter \"abc\" in this textbox");
        assertTrue(html.contains("<span class=\"pkb-value\">&quot;abc&quot;</span>"), html);
        assertTrue(html.startsWith("Enter "), html);
        assertTrue(html.endsWith(" in this textbox"), html);
        String unsafe = HumanValue.html("Enter \"<b>\" here");
        assertFalse(unsafe.contains("<b>"), unsafe);
        assertTrue(unsafe.contains("&lt;b&gt;"), unsafe);
        assertEquals("plain", HumanValue.html("plain"));
        String literal = VisualKinds.stepMarkup("2", "<b>Go</b>");
        assertTrue(literal.contains("&lt;b&gt;Go&lt;/b&gt;"), literal);
        assertFalse(literal.contains("<b>"), literal);
        String badge = VisualKinds.stepMarkup("2", "Enter \"abc\" here");
        assertTrue(badge.contains("pkb-value"), badge);
        assertTrue(badge.contains("&quot;abc&quot;"), badge);
        assertFalse(badge.contains("<b>"), badge);
        String magnifier = VisualKinds.magnifierMarkup("zoom \"2\"", "2");
        assertTrue(magnifier.contains("pkb-value"), magnifier);
    }

    @Test
    void pageRuntimeAndReportsUseTheSameChip() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        String popup = Files.readString(Path.of("src/main/resources/tools/dscode/common/agentsurface/agent-surface-popup.js"));
        String report = Files.readString(Path.of("src/main/java/tools/dscode/common/reporting/logging/simplehtml/SimpleHtmlReportConverter.java"));
        String cards = Files.readString(Path.of("src/main/java/tools/dscode/common/agentcontrol/AgentControl.java"));
        assertTrue(js.contains("function appendDistinguished"));
        assertTrue(js.contains("function quoteValue"));
        assertTrue(js.contains("function instruction"));
        assertTrue(js.contains("Answer sent: "));
        assertTrue(js.contains("Answer sent: ' + picked"));
        assertTrue(popup.contains("function appendDistinguished"));
        assertTrue(popup.contains("pkb-value"));
        assertTrue(report.contains("HumanValue.chip"));
        assertTrue(report.contains(".pkb-value"));
        assertTrue(cards.contains("HumanValue.html"));
    }
}
