package tools.dscode.control.api;

import io.cucumber.core.runner.GlobalState;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
