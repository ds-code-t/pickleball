package tools.dscode.engine;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreHydrator;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.source.StoreScenarioSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballEngineTest {
    public static class EchoGlue {
        @Given("native engine echo {string}")
        public String echo(String value) {
            return value;
        }
    }

    private static final String FEATURE = """
            Feature: Echo
              Scenario: Hi
                Given native engine echo "hi"
            """;

    @Test
    void runsFromTextAStepListAndAStoreRowWithNothingElseOnDisk(@TempDir Path dir) throws Exception {
        EngineOptions options = EngineOptions.of(EchoGlue.class).project(dir);
        RunResult text = PickleballEngine.run(FEATURE, options);
        assertEquals(Status.PASSED, text.status());
        assertEquals("hi", text.scenarios().getFirst().steps().getFirst().returned());
        assertFalse(Files.walk(dir).anyMatch(path -> path.toString().endsWith(".feature")));

        RunResult steps = PickleballEngine.run(List.of("Given native engine echo \"hi\""), options.scenario("runtime"));
        assertEquals(Status.PASSED, steps.status());
        assertEquals("hi", steps.scenarios().getFirst().steps().getFirst().returned());

        Path feature = dir.resolve("features/echo.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, FEATURE);
        StoreHydrator.hydrate(dir, StoreHydrator.Options.full());
        Files.delete(feature);
        RunResult store = PickleballEngine.run(new StoreScenarioSource(dir, "features/echo.feature"), options.scenario("Hi"));
        assertEquals(Status.PASSED, store.status());
        assertEquals("hi", store.scenarios().getFirst().steps().getFirst().returned());
        assertFalse(Files.exists(feature));
    }

    @Test
    void cliPrintsTheSameResult() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = EngineCli.execute(new String[]{
                "engine", "run",
                "--text=" + FEATURE,
                "--glue=" + EchoGlue.class.getName()
        }, new PrintStream(stdout), new PrintStream(stderr));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        assertTrue(out.contains("scenario PASSED Hi"));
        assertTrue(out.contains("step PASSED native engine echo \"hi\""));
    }

    public static class WriteGlue {
        @Given("native engine write next {string}")
        public void write(String value) {
            ExecutionContext.current().writeSteps(List.of(
                    EngineStep.of("Given ", "native engine echo \"" + value + "\"").withMetadata("source", "runtime")
            ));
        }
    }

    @Test
    void runtimeWrittenStepRunsNextAndIsRecorded() {
        String feature = """
                Feature: Write
                  Scenario: Mid
                    Given native engine write next "hi"
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(EchoGlue.class, WriteGlue.class));
        assertEquals(Status.PASSED, result.status());
        assertEquals(2, result.scenarios().getFirst().steps().size());
        assertEquals("hi", result.scenarios().getFirst().steps().get(1).returned());
        assertTrue(result.scenarios().getFirst().steps().get(1).message().contains("source=runtime"));
    }
}
