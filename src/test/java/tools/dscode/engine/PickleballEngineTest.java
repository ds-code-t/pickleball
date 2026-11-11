package tools.dscode.engine;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dalton.DaltonHydrator;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.source.DaltonScenarioSource;

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
    void runsFromTextAStepListAndADaltonRowWithNothingElseOnDisk(@TempDir Path dir) throws Exception {
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
        DaltonHydrator.hydrate(dir, DaltonHydrator.Options.full());
        Files.delete(feature);
        RunResult dalton = PickleballEngine.run(new DaltonScenarioSource(dir, "features/echo.feature"), options.scenario("Hi"));
        assertEquals(Status.PASSED, dalton.status());
        assertEquals("hi", dalton.scenarios().getFirst().steps().getFirst().returned());
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
}
