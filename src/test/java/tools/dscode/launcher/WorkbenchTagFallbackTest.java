package tools.dscode.launcher;

import io.cucumber.tagexpressions.TagExpressionException;
import io.cucumber.tagexpressions.TagExpressionParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchTagFallbackTest {
    @Test
    void xorIsRewrittenToAndOrNotAndTheEscapedFormStaysLiteral() {
        String rewritten = WorkbenchTagFallback.cucumberTags("@smoke xor @wip");
        assertFalse(rewritten.toLowerCase().contains("xor"), rewritten);
        TagExpressionParser.parse(rewritten);
        assertTrue(rewritten.contains("@smoke"), rewritten);
        assertTrue(rewritten.contains("@wip"), rewritten);
        assertTrue(rewritten.contains(" or "), rewritten);
        assertTrue(rewritten.contains("not"), rewritten);

        String chained = WorkbenchTagFallback.cucumberTags("@a xor @b xor @c");
        TagExpressionParser.parse(chained);

        String escaped = "@smoke\\ xor\\ @wip";
        assertEquals(escaped, WorkbenchTagFallback.cucumberTags(escaped));
        TagExpressionParser.parse(escaped);
        assertEquals("@smoke\\ xor\\ @wip", WorkbenchTagFallback.escapedForm("@smoke xor @wip"));

        String warning = WorkbenchTagFallback.warning("unsupported tag expression: @smoke xor @wip", "@smoke xor @wip");
        assertTrue(warning.contains("@smoke\\ xor\\ @wip"), warning);
        assertTrue(warning.contains("Maven pkb_tags="), warning);
        assertTrue(warning.contains("not xor"), warning);

        assertEquals("@smoke and @wip", WorkbenchTagFallback.cucumberTags("@smoke and @wip"));
    }

    @Test
    void aQuotedXorIsNotAnOperator() {
        String expression = "@smoke and \"xor\" or @wip";
        assertEquals(expression, WorkbenchTagFallback.cucumberTags(expression));
        try {
            TagExpressionParser.parse(expression);
        } catch (TagExpressionException ignored) {
            // A quoted xor is not the operator this rewrite targets. The point is it is not rewritten.
        }
    }
}
