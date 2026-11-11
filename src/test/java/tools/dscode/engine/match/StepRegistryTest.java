package tools.dscode.engine.match;

import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.override.StepOverridePatternType;
import tools.dscode.control.override.StepOverrideRule;
import tools.dscode.coredefinitions.DynamicSteps;
import tools.dscode.coredefinitions.ModularScenarios;
import tools.dscode.coredefinitions.ServiceCallSteps;
import tools.dscode.engine.EngineLog;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRegistryTest {

    @Test
    void annotationValuesStayByteIdenticalAndMatchTheCucumberExpressionFactory() throws Exception {
        StepRegistry registry = new StepRegistry().scan(
                DynamicSteps.class,
                ModularScenarios.class,
                ServiceCallSteps.class,
                ProbeGlue.class
        );
        Method dynamic = DynamicSteps.class.getMethod("executeDynamicStep", String.class);
        String pattern = dynamic.getAnnotation(Given.class).value();
        GlueStep glue = registry.steps().stream().filter(step -> dynamic.equals(step.method())).findFirst().orElseThrow();
        assertEquals(pattern, glue.pattern());
        assertEquals("^,(?s)(.*)$", glue.pattern());
        assertEquals(StepKind.DYNAMIC, glue.kind());

        ExpressionFactory factory = new ExpressionFactory(new ParameterTypeRegistry(Locale.ENGLISH));
        assertSameCapture(registry, factory, ", save \"v\" as \"name\"");
        assertSameCapture(registry, factory, "IF: , log \"a\"");
        assertSameCapture(registry, factory, "SCENARIO: Checkout");
        assertSameCapture(registry, factory, "COMPONENT: Header");
        assertSameCapture(registry, factory, "CALL: orders");
        assertSameCapture(registry, factory, "SERVICE CALL: orders");
        assertSameCapture(registry, factory, "native echo \"Ava\"");
        assertSameCapture(registry, factory, "native count 4");

        StepMatch echo = registry.match("native echo \"Ava\"");
        assertEquals(StepKind.GLUE, echo.kind());
        assertEquals(List.of("Ava"), echo.captures());
        assertEquals("stub:dynamic", StepRegistry.dispatchStub(registry.match(", save x")));
        assertEquals("stub:block_conditional", StepRegistry.dispatchStub(registry.match("ELSE-IF: ready")));
        assertEquals("stub:run", StepRegistry.dispatchStub(registry.match("RUN SCENARIO: Checkout")));
        assertEquals("stub:service_call", StepRegistry.dispatchStub(registry.match("CALL: orders")));
        assertEquals(StepKind.UNDEFINED, registry.match("this step is not defined anywhere").kind());
    }

    @Test
    void twoGlueStepsForTheSameTextAreAmbiguous() {
        StepRegistry registry = new StepRegistry().scan(AmbiguousGlue.class);
        AmbiguousMatchException failure = assertThrows(
                AmbiguousMatchException.class,
                () -> registry.match("native same hello")
        );
        assertTrue(failure.locations().size() >= 2, failure.getMessage());
    }

    @Test
    void stepOverrideWinsWithoutALiveCucumberScenario() {
        StepOverrideRule rule = new StepOverrideRule(
                "replace-echo",
                StepOverridePatternType.REGEX,
                "native echo (.*)",
                context -> null
        );
        StepRegistry registry = new StepRegistry().scan(ProbeGlue.class);
        StepMatch match = registry.match("native echo hi", List.of(rule));
        assertEquals(StepKind.OVERRIDE, match.kind());
        assertEquals("replace-echo", match.overrideId());
        assertEquals(List.of("hi"), match.captures());
        assertEquals("stub:override", StepRegistry.dispatchStub(match));
    }

    @Test
    void matchLogIsToggleableDateStampedAndSizeCapped(@TempDir Path project) throws Exception {
        System.setProperty("pkb_engine_log", "match");
        System.setProperty("pkb_engine_log_max_bytes", "220");
        try {
            StepRegistry registry = new StepRegistry().scan(ProbeGlue.class).logTo(project);
            registry.match("native echo \"Ava\"");
            registry.match("native count 4");
            Path log = project.resolve(".pickleball/engine/engine.log");
            assertTrue(Files.isRegularFile(log));
            String text = Files.readString(log);
            assertTrue(text.contains("match"), text);
            assertTrue(text.startsWith("20"), text);
            assertTrue(Files.size(log) <= 220, "size=" + Files.size(log));
            assertTrue(EngineLog.enabled("match"));
            assertTrue(!EngineLog.enabled("other"));
        } finally {
            System.clearProperty("pkb_engine_log");
            System.clearProperty("pkb_engine_log_max_bytes");
        }
    }

    private static void assertSameCapture(StepRegistry registry, ExpressionFactory factory, String text) {
        StepMatch match = registry.match(text);
        assertTrue(match.matched(), text + " -> " + match.kind());
        Expression expression = factory.createExpression(match.step().pattern());
        List<Argument<?>> arguments = expression.match(text, match.step().expressionTypes().toArray(Type[]::new));
        assertTrue(arguments != null, text);
        List<String> expected = arguments.stream().map(argument -> argument.getValue() == null ? null : String.valueOf(argument.getValue())).toList();
        assertEquals(expected, match.captures(), text);
    }

    public static class ProbeGlue {
        @Given("native echo {string}")
        public void echo(String value) {
        }

        @Given("^native count (\\d+)$")
        public void count(int value) {
        }

        @Given("native table")
        public void table(DataTable table) {
        }
    }

    public static class AmbiguousGlue {
        @Given("native same {word}")
        public void word(String value) {
        }

        @Given("^native same (.*)$")
        public void rest(String value) {
        }
    }
}
