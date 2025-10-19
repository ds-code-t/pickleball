package tools.dscode.coredefinitions;

import io.cucumber.java.en.Given;

import static tools.dscode.state.ScenarioState.getScenarioState;

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

    @Given("^print (.*)$")
    public static void printVal(String message) {
        System.out.println("PRINT: " + message);
    }

}
