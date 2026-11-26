package tools.dscode.engine.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PatternMatcher and cucumber-expressions 18.0.1 agree on the core annotation strings.
 * Cucumber is imported only here.
 */
class PatternMatcherParityTest {
    @Test
    void fixturesAgreeWithTheCucumberExpressionFactory() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ExpressionFactory factory = new ExpressionFactory(new ParameterTypeRegistry(Locale.ENGLISH));
        int matched = 0;
        int captured = 0;
        int rejected = 0;
        try (InputStream in = PatternMatcherParityTest.class.getResourceAsStream("/engine/pattern-matcher-fixtures.txt")) {
            assertTrue(in != null, "fixture missing");
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : body.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                JsonNode node = json.readTree(line);
                String pattern = node.get("pattern").asText();
                if (node.path("reject").asBoolean(false)) {
                    assertTrue(PatternMatcher.translation(pattern).rejected(), pattern);
                    assertTrue(cucumberRejects(factory, pattern), pattern);
                    rejected++;
                    continue;
                }
                String text = node.get("text").asText();
                List<String> expected = cucumberMatch(factory, pattern, text);
                List<String> actual = PatternMatcher.match(pattern, text);
                assertEquals(expected, actual, pattern + " / " + text);
                if (expected != null) {
                    matched++;
                    for (String value : expected) {
                        if (value != null && !value.isEmpty()) captured++;
                    }
                }
            }
        }
        assertTrue(matched > 0, "fixture never matched");
        assertTrue(captured > 0, "fixture never captured a group");
        assertTrue(rejected > 0, "fixture has no rejected pattern");
    }

    private static boolean cucumberRejects(ExpressionFactory factory, String pattern) {
        try {
            factory.createExpression(pattern);
            return false;
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private static List<String> cucumberMatch(ExpressionFactory factory, String pattern, String text) {
        Expression expression = factory.createExpression(pattern);
        List<Argument<?>> arguments = expression.match(text);
        if (arguments == null) return null;
        List<String> values = new ArrayList<>();
        for (Argument<?> argument : arguments) {
            Object value = argument.getValue();
            values.add(value == null ? null : String.valueOf(value));
        }
        return List.copyOf(values);
    }
}
