package tools.dscode.modkit.coredefinitions;

import io.cucumber.java.en.Given;

import static tools.dscode.modkit.state.ScenarioState.getScenarioState;

public class GeneralSteps {

    @Given("MESSAGE:{string}")
    public static void setValues(String message) {
        System.out.println("MESSAGE: " + message);
        Throwable t = getScenarioState().getCurrentStep().storedThrowable;
        if (t != null) {
            getScenarioState().getCurrentStep().storedThrowable = null;
            throw new RuntimeException(t);
        }
    }

}
