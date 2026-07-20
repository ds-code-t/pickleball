package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;

import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.variables.RunVars.resolveFromVars;

public class ServiceCallScenarios extends CoreSteps {

    static final String CALL_NAME = "SERVICE CALL NAME";
    static final String DEFAULT_CALLS_PATH = "src/test/resources/calls";

    @Given("^SERVICE CALLS?:?(.*)?$")
    public static void serviceCalls(String inlineTags, DataTable dataTable) {
        ModularScenarios.populateRunScenariosStep(
                getRunningStep(),
                inlineTags,
                dataTable,
                callsPath(),
                "service call",
                ServiceCallScenarios::setCallName
        );
    }

    private static void setCallName(
            ScenarioStep scenarioStep,
            Map<String, String> passedValues
    ) {
        String name = passedValues.getOrDefault("name", "").trim();
        if (name.isBlank()) {
            Object scenarioName = scenarioStep.getDefaultStepNodeMap().get("SCENARIO NAME");
            name = scenarioName instanceof JsonNode node
                    ? node.asText()
                    : String.valueOf(scenarioName);
        }
        scenarioStep.getDefaultStepNodeMap().put(CALL_NAME, name);
    }

    private static String callsPath() {
        Object configuredPath = resolveFromVars("pkb_callspath");
        return configuredPath == null || configuredPath.toString().isBlank()
                ? DEFAULT_CALLS_PATH
                : configuredPath.toString().trim();
    }
}
