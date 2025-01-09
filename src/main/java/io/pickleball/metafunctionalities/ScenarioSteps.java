package io.pickleball.metafunctionalities;

import io.cucumber.core.backend.Status;
import io.cucumber.core.runner.TestCase;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.annotations.NoEventEmission;
import io.pickleball.cacheandstate.StepContext;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import java.util.*;

import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.configs.Constants.COMPONENT_PATH;
import static io.pickleball.configs.Constants.SCENARIO_TAGS;


public class ScenarioSteps {

    @NoEventEmission
    @Given("^Scenario:(.*)$")
    public void scenarioRun(String scenarioName, DataTable dataTable) {

        StepContext originalStep = getCurrentStep();

        // Parse command-line arguments into RuntimeOptions
        for (LinkedMultiMap<String, String> map : dataTable.asLinkedMultiMaps(String.class, String.class)) {
            String tags = map.getValueByStringOrDefault(SCENARIO_TAGS, "");
            map.put("_calling tags", tags);

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

            List<TestCase> testCases = getCurrentScenario().getAndSortTestcases(argv, map);

            TestCase lastTestCase = null;
            for (TestCase testCase : testCases) {
                lastTestCase = testCase;
                testCase.addChildScenarioContext(testCase);
                testCase.runComponent(getRunner().bus);
                if (testCase.getTestCaseState().isFailed()) {
                    Status status = testCase.getTestCaseState().getStatus();
                    originalStep.addStatus(status);
                    break;
                }
            }
            if (lastTestCase != null && lastTestCase.getTestCaseState().isFailed()) {
                break;
            }
        }

    }
}
