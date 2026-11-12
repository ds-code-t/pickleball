package tools.dscode.control.api;

import io.cucumber.core.runner.GlobalState;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicControlNoScenarioTest {
    public static class EchoGlue {
        @Given("native control echo {string}")
        public String echo(String value) {
            return value;
        }
    }

    @Test
    void executesAStepWithoutALiveCucumberScenario() {
        assertNull(GlobalState.getCurrentScenarioState());
        NativeDynamicSteps.useGlue(EchoGlue.class);
        ControlCallResult<Object> result = DynamicControl.executeStep("Given native control echo \"hi\"");
        assertEquals(ControlCallStatus.SUCCESS, result.status());
        assertEquals("hi", result.value());
        assertNull(GlobalState.getCurrentScenarioState());

        ControlCallResult<Object> missing = DynamicControl.executeStep("native control echo missing step");
        assertEquals(ControlCallStatus.UNAVAILABLE, missing.status());
    }

    public static class ReadGlue {
        @Given("native echo {string}")
        public String echo(String value) {
            return value;
        }

        @Given("native read {string}")
        public String read(String key) {
            Object value = MappingProcessor.getRunMap() == null ? null : MappingProcessor.getRunMap().get(key);
            return value == null ? "" : String.valueOf(value);
        }
    }

    public static class CountGlue {
        static final AtomicInteger hits = new AtomicInteger();

        @Given("native count")
        public void count() {
            hits.incrementAndGet();
        }
    }

    @Test
    void dynamicPhrasesRunThroughDynamicControlWithoutCucumber() {
        assertNull(GlobalState.getTestCase());
        NativeDynamicSteps.useGlue(ReadGlue.class, CountGlue.class);
        CountGlue.hits.set(0);
        String feature = """
                Feature: Control
                  Scenario: Caller
                    * , save "A" as "result"
                    * native echo "<result>"
                    * IF: "a" equals "a" THEN: , save "yes" as "branch"
                    * native read "branch"
                    * ELSE: , save "no" as "else-branch"
                    * native read "else-branch"
                    * , until the Step Repetition is greater than 2:
                      : * native count
                    * RUN SCENARIO: Worker
                    * RUN COMPONENT SCENARIO: Worker
                    * RUN SERVICE CALL: Worker
                    * CALL: Worker
                    * native read "made"
                  Scenario: Worker
                    * , save "done" as "made"
                """;
        ControlCallResult<Object> result = DynamicControl.executeScenario(feature, "Caller");
        assertEquals(ControlCallStatus.SUCCESS, result.status(), String.valueOf(result.error()));
        RunResult run = (RunResult) result.value();
        assertEquals(Status.PASSED, run.status(), run.message());
        assertEquals("A", run.scenarios().getFirst().steps().get(1).returned());
        assertEquals("yes", run.scenarios().getFirst().steps().get(3).returned());
        assertEquals("", run.scenarios().getFirst().steps().get(5).returned());
        assertEquals(2, CountGlue.hits.get());
        assertEquals("done", run.scenarios().getFirst().steps().get(11).returned());
        assertNull(GlobalState.getCurrentScenarioState());
        assertTrue(run.scenarios().getFirst().steps().getFirst().text().contains("save"));
    }
}
