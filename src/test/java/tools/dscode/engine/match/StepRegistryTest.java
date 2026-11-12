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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
    void scanPackageReadsGlueFromAJar(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("gluejar/only/JarOnlyGlue.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package gluejar.only;
                import io.cucumber.java.en.Given;
                public class JarOnlyGlue {
                    @Given("jar only echo {string}")
                    public void echo(String value) {}
                }
                """);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JDK compiler is required");
        int compiled = compiler.run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                source.toString());
        assertEquals(0, compiled);
        Path jar = dir.resolve("glue.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("gluejar/only/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("gluejar/only/JarOnlyGlue.class"));
            out.write(Files.readAllBytes(classes.resolve("gluejar/only/JarOnlyGlue.class")));
            out.closeEntry();
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            StepRegistry registry = new StepRegistry().scanPackage("gluejar.only");
            StepMatch match = registry.match("jar only echo \"hi\"");
            assertEquals(StepKind.GLUE, match.kind());
            assertEquals(List.of("hi"), match.captures());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
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

    @Test
    void everyCoreDefinitionMatchesTheCucumberExpressionFactory() {
        StepRegistry registry = new StepRegistry().scanPackage("tools.dscode.coredefinitions");
        assertTrue(registry.steps().size() > 50, "scanned " + registry.steps().size());
        ExpressionFactory factory = new ExpressionFactory(new ParameterTypeRegistry(Locale.ENGLISH));
        List<String> problems = new ArrayList<>();
        for (GlueStep step : registry.steps()) {
            String pattern = step.pattern();
            assertEquals(annotationValue(step), pattern, step.location());
            if (step.dataTable()) {
                assertTrue(hasParameter(step, DataTable.class), step.location());
            }
            if (step.docString()) {
                assertTrue(hasParameter(step, io.cucumber.docstring.DocString.class), step.location());
            }
            String probe = satisfy(pattern);
            Set<String> fromRegistry = new LinkedHashSet<>();
            try {
                StepMatch match = registry.match(probe);
                if (match.matched() && match.step() != null) fromRegistry.add(match.step().location());
            } catch (AmbiguousMatchException ambiguous) {
                fromRegistry.addAll(ambiguous.locations());
            }
            Set<String> fromFactory = new LinkedHashSet<>();
            for (GlueStep candidate : registry.steps()) {
                Expression expression = factory.createExpression(candidate.pattern());
                List<Argument<?>> arguments = expression.match(probe, candidate.expressionTypes().toArray(Type[]::new));
                if (arguments != null) fromFactory.add(candidate.location());
            }
            if (!fromFactory.contains(step.location())) {
                problems.add("probe missed its own pattern " + step.location() + " /" + pattern + "/ probe=[" + probe + "]");
                continue;
            }
            if (!fromRegistry.equals(fromFactory)) {
                problems.add("registry " + fromRegistry + " factory " + fromFactory + " probe=[" + probe + "] pattern=" + pattern);
                continue;
            }
            if (fromRegistry.size() == 1) {
                StepMatch match = registry.match(probe);
                assertSameCapture(registry, factory, probe);
                assertEquals(step.method(), match.step().method(), probe + " /" + pattern + "/");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static boolean hasParameter(GlueStep step, Class<?> type) {
        for (Class<?> parameter : step.method().getParameterTypes()) {
            if (parameter == type) return true;
        }
        return false;
    }

    private static String annotationValue(GlueStep step) {
        for (java.lang.annotation.Annotation annotation : step.method().getAnnotations()) {
            try {
                Method value = annotation.annotationType().getMethod("value");
                if (value.getReturnType() != String.class) continue;
                Object text = value.invoke(annotation);
                if (step.pattern().equals(text)) return step.pattern();
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return "";
    }

    /** A string the pattern matches. Used only to compare matchers. */
    static String satisfy(String pattern) {
        String source = pattern == null ? "" : pattern;
        boolean cucumber = !(source.startsWith("^") || source.startsWith("(?") || source.contains("\\"));
        if (cucumber) {
            String text = source;
            text = text.replace("{string}", "\"x\"");
            text = text.replace("{int}", "1");
            text = text.replace("{long}", "1");
            text = text.replace("{float}", "1.5");
            text = text.replace("{double}", "1.5");
            text = text.replace("{word}", "word");
            text = text.replace("{}", "x");
            return text;
        }
        return new Satisfier(source).parse();
    }

    private static final class Satisfier {
        private final String source;
        private int index;

        private Satisfier(String source) {
            this.source = source;
        }

        private String parse() {
            String text = sequence(true);
            return text;
        }

        private String sequence(boolean top) {
            StringBuilder out = new StringBuilder();
            while (index < source.length()) {
                char current = source.charAt(index);
                if (top && (current == '$')) {
                    index++;
                    break;
                }
                if (!top && current == ')') break;
                if (!top && current == '|') break;
                out.append(atom());
            }
            return out.toString();
        }

        private String atom() {
            if (index >= source.length()) return "";
            char current = source.charAt(index);
            if (current == '^') {
                index++;
                return "";
            }
            if (current == '(') return group();
            if (current == '\\') return escape();
            if (current == '[') return clazz();
            if (current == '.') {
                index++;
                return quantified("x");
            }
            index++;
            return quantified(String.valueOf(current));
        }

        private String group() {
            index++;
            if (source.startsWith("?:", index)) index += 2;
            else if (source.startsWith("?i:", index) || source.startsWith("?s:", index)) index += 3;
            else if (source.startsWith("?is:", index) || source.startsWith("?si:", index)) index += 4;
            else if (source.startsWith("?i)", index) || source.startsWith("?s)", index)
                    || source.startsWith("?m)", index)) {
                index += 3;
                return "";
            } else if (source.startsWith("?is)", index) || source.startsWith("?si)", index)) {
                index += 4;
                return "";
            }
            int start = index;
            String chosen = sequence(false);
            while (index < source.length() && source.charAt(index) == '|') {
                index++;
                sequence(false);
            }
            if (index < source.length() && source.charAt(index) == ')') index++;
            if (start == index) {
                return "";
            }
            return quantified(chosen);
        }

        private String escape() {
            index++;
            if (index >= source.length()) return "";
            char code = source.charAt(index++);
            String value = switch (code) {
                case 'd', 'D' -> "1";
                case 's' -> " ";
                case 'S', 'w', 'W' -> "x";
                case 'n' -> "\n";
                case 't' -> "\t";
                default -> String.valueOf(code);
            };
            return quantified(value);
        }

        private String clazz() {
            index++;
            boolean negated = index < source.length() && source.charAt(index) == '^';
            if (negated) index++;
            char chosen = 'x';
            if (!negated && index < source.length() && source.charAt(index) != ']') {
                if (source.charAt(index) == '\\' && index + 1 < source.length()) {
                    index++;
                    chosen = source.charAt(index++);
                } else {
                    chosen = source.charAt(index++);
                }
            }
            while (index < source.length() && source.charAt(index) != ']') index++;
            if (index < source.length()) index++;
            return quantified(String.valueOf(chosen));
        }

        private String quantified(String value) {
            if (index >= source.length()) return value;
            char current = source.charAt(index);
            if (current == '?' || current == '*' ) {
                index++;
                return value;
            }
            if (current == '+') {
                index++;
                return value;
            }
            if (current == '{') {
                index++;
                while (index < source.length() && source.charAt(index) != '}') index++;
                if (index < source.length()) index++;
                return value;
            }
            return value;
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
