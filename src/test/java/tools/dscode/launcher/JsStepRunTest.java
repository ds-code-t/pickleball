package tools.dscode.launcher;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.Values;
import tools.dscode.engine.EngineCli;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.store.StoreHydrator;
import tools.dscode.store.StoreScope;
import tools.dscode.store.StoreStepCatalog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsStepRunTest {
    @TempDir
    Path project;

    @AfterEach
    void clearScope() {
        ForeignStepHosts.end();
        ForeignStepHosts.install(null);
        StoreScope.clear();
    }

    @Test
    void javaScriptDefineStepSavesAValueTheNextStepReads() throws Exception {
        writeStep();
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        RunResult result = PickleballEngine.run("""
                Feature: Js steps
                  Scenario: count
                    * I count 2 for the js step
                    * the saved js step count equals "2"
                """, EngineOptions.of(CountCheck.class).project(project));
        assertEquals(Status.PASSED, result.status(), result.scenarios().toString());
    }

    @Test
    void engineStepRunsOneDefinition() throws Exception {
        writeStep();
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        String id = StoreStepCatalog.list(project).stream()
                .filter(row -> "js".equals(row.host()))
                .findFirst()
                .orElseThrow()
                .id();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EngineCli.execute(new String[]{
                "engine", "step",
                "--project=" + project,
                "--definition=" + id,
                "--text=I count 2 for the js step"
        }, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8) + out.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("PASSED"), out.toString(StandardCharsets.UTF_8));
    }

    private void writeStep() throws Exception {
        Path script = project.resolve("src/test/resources/glue/js/sample-step.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                defineStep(/I count (\\d+) for the js step/, function (n) {
                  saveValue("jsStepCount", n);
                });
                """, StandardCharsets.UTF_8);
    }

    public static class CountCheck {
        @Given("the saved js step count equals {string}")
        public void check(String expected) {
            Object value = Values.getValue("jsStepCount");
            if (!expected.equals(String.valueOf(value))) {
                throw new AssertionError("jsStepCount was " + value);
            }
        }
    }
}
