package io.pickleball.metafunctionalities;

import io.cucumber.core.backend.Status;
import io.cucumber.core.runner.TestCase;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.annotations.Metastep;
import io.pickleball.cacheandstate.ScenarioContext;
import io.pickleball.cacheandstate.StepContext;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import java.util.*;

import static io.pickleball.cacheandstate.GlobalCache.getState;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.configs.Constants.COMPONENT_PATH;
import static io.pickleball.configs.Constants.SCENARIO_TAGS;
import static io.pickleball.cucumberutilities.LoggingUtilities.getHighestStatus;


public class ScenarioSteps {

    @Metastep
    @Given("^Scenario:(.*)$")
    public void ScenarioRun(String scenarioName, DataTable dataTable) {
        startEvent();

        StepContext originalStep = getCurrentStep();

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

            List<TestCase> testCases = getCurrentScenario().getAndSortTestcases(argv, map);
            System.out.println("@@testCases:::  : " + testCases.size());
//            getCurrentScenario().executeTestCases(testCases);


            TestCase lastTestCase = null;
            for (TestCase testCase : testCases) {
                lastTestCase = testCase;
                System.out.println("@@start run");
                testCase.scenarioContext.addChildScenarioContext(testCase.scenarioContext);
                testCase.runComponent(getRunner().bus);
                System.out.println("@@getTestCaseState().isFailed():: " + testCase.scenarioContext.getTestCaseState().isFailed());
                if (testCase.scenarioContext.getTestCaseState().isFailed()) {
                    Status status = testCase.scenarioContext.getTestCaseState().getStatus();
                    originalStep.addStatus(status);
                    System.out.println("@@Break");
                    break;
                }
                System.out.println("@@end testcase run");
            }
            if (lastTestCase != null && lastTestCase.scenarioContext.getTestCaseState().isFailed()) {
                break;
            }
        }

        endEvent();
    }
}
