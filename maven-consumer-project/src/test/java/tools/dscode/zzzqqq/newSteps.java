package tools.dscode.zzzqqq;

import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.dscode.registry.GlobalRegistry;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.assertj.core.api.Assertions.assertThat;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;
import static tools.dscode.registry.GlobalRegistry.LOCAL;

public class newSteps {
    private int a, b, result;

    @Given("QQQ")
    public void qqq1() {
        System.out.println("@@qqq1");
    }

    @Given("^QQQ2(.*)$")
    public void qqq2(String arg0) {
        System.out.println("@@qqq2" + arg0);
    }

    @Given("^zdatatable (.*)$")
    public void dataTable(String arg0, DataTable table) {
        System.out.println("@@arg0: " + arg0);
        System.out.println("@@table: " + table);
    }

    @Given("^zprint (.*)$")
    public static void printVal(String message) {
        System.out.println("PRINT: " + message);
        PickleStepTestStep executingPickleStepTestStep =  getCurrentScenarioState().getCurrentStep().executingPickleStepTestStep;
        System.out.println("nestingLevel1-: " + getCurrentScenarioState().getCurrentStep().getNestingLevel());
        System.out.println("nestingLevel2-: " + executingPickleStepTestStep.getPickleStep().nestingLevel);

        System.out.println("zzz1: " + getCurrentScenarioState().getCurrentStep().executingPickleStepTestStep.getStepText());
        System.out.println("zzz2: " + getCurrentScenarioState().getCurrentStep().executingPickleStepTestStep.getStep().getText());
    }
}
