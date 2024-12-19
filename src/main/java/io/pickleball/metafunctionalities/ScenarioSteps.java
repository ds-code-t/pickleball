package io.pickleball.metafunctionalities;

import io.cucumber.core.runner.TestCase;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import java.util.*;

import static io.pickleball.cacheandstate.GlobalCache.getState;
import static io.pickleball.configs.Constants.COMPONENT_PATH;
import static io.pickleball.configs.Constants.SCENARIO_TAGS;


public class ScenarioSteps {
    @Given("^Scenario:(.*)$")
    public MetaStepData ScenarioRun(String scenarioName, DataTable dataTable) {
        // Parse command-line arguments into RuntimeOptions
        for (LinkedMultiMap<String, String> map : dataTable.asLinkedMultiMaps(String.class, String.class)) {
            String tags = map.getValueByStringOrDefault(SCENARIO_TAGS, "");
            String componentPaths = map.getValueByStringOrDefault(COMPONENT_PATH, "src/test/resources/features");
            List<String> argvList = new ArrayList<>();
            argvList.add(componentPaths); // Always include the component paths

            if (tags != null && !tags.isEmpty()) {
                argvList.add("--tags");
                argvList.add(tags);
            }

            if (scenarioName != null && !scenarioName.isEmpty()) {
                argvList.add("--name");
                argvList.add(scenarioName);
            }
            String[] argv = argvList.toArray(new String[0]);

            List<TestCase> testCases = getState().getCurrentScenario().getAndSortTestcases(argv, map);
            getState().getCurrentScenario().executeTestCases(testCases);
        }


        return (new MetaStepData()).set("scenarioName", scenarioName).set("dataTable", dataTable);
    }
}
