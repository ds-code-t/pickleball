package tools.dscode.engine.adapter.cucumber;

import io.cucumber.tagexpressions.TagExpressionParser;

import java.util.List;

/** Tag expressions for the native engine. The parser stays in this adapter. */
public final class CucumberTags {
    private CucumberTags() {
    }

    public static boolean evaluate(String expression, List<String> tags) {
        if (expression == null || expression.isBlank()) return true;
        List<String> present = tags == null ? List.of() : tags;
        return TagExpressionParser.parse(expression.trim()).evaluate(present);
    }
}
