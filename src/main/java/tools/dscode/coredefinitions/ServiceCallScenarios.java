package tools.dscode.coredefinitions;

import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.NodeMap;

import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.variables.RunVars.resolveFromVars;

public class ServiceCallScenarios extends CoreSteps {

    static final String CALL_NAME = ServiceCallContext.CALL_NAME;
    static final String DEFAULT_CALLS_PATH = "src/test/resources/calls";

    @Given("^SERVICE CALLS?:?(.*)?$")
    public static void serviceCalls(String inlineTags, DataTable dataTable) {
        ModularScenarios.populateRunScenariosStep(
            getRunningStep(),
            inlineTags,
            dataTable,
            callsPath(),
            "service call",
            ServiceCallScenarios::initializeServiceCall
        );
    }

    private static void initializeServiceCall(
        ScenarioStep scenarioStep,
        Map<String, String> passedValues
    ) {
        NodeMap scenarioMap = scenarioStep.getDefaultStepNodeMap();
        String scenarioName = ServiceCallContext.scenarioName(scenarioMap);
        String explicitlyPassedName = normalize(passedValues.get("name"));
        String resolvedCallName = explicitlyPassedName.isBlank()
            ? scenarioName
            : explicitlyPassedName;

        ServiceCallContext.initialize(
            scenarioMap,
            getRunMap(),
            resolvedCallName,
            scenarioName
        );
    }

    private static String callsPath() {
        Object configuredPath = resolveFromVars("pkb_callspath");

        return configuredPath == null || configuredPath.toString().isBlank()
            ? DEFAULT_CALLS_PATH
            : configuredPath.toString().trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
