package tools.dscode.coredefinitions;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.NoLogging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.ScenarioStep.createScenarioStep;
import static tools.dscode.common.GlobalConstants.COMPONENT_TAG_META_CHAR;
import static tools.dscode.common.GlobalConstants.COMPONENT_TAG_PREFIX;

public class ModularScenarios extends CoreSteps {


    @NoLogging
    @Given("^RUN SCENARIOS:?(.*)?$")
    public static void runScenarios(String inlineTags, DataTable dataTable) {
        List<Map<String, String>> maps = dataTable.asMaps().stream()
                .map(HashMap::new) // copy each to a mutable map
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (inlineTags != null && !inlineTags.isBlank()) {
            final String scenarioTags = inlineTags;
            if (maps.isEmpty()) {
                Map<String, String> singleMap = new HashMap<>();
                singleMap.put("Tags", scenarioTags);
                maps.add(singleMap);

            } else {
                maps.forEach(map -> {
                    String mapTags = (scenarioTags + " " + map.getOrDefault(" Tags ", "")).trim();
                    map.put("Tags", mapTags);
                });
            }
        }
        filterAndExecutePickles(maps);
    }


    public static void filterAndExecutePickles(List<Map<String, String>> maps, String... messageString) {
        System.out.println("@@runScenarios - " + maps);
        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
        StepExtension lastScenarioNameStep = null;

        for (Map<String, String> map : maps) {
            String tagString = map.getOrDefault("Tags", "");
            tagString = tagString.replaceAll(COMPONENT_TAG_META_CHAR, COMPONENT_TAG_PREFIX);
            List<Pickle> pickles = CucumberScanUtil.listPicklesByTags(tagString);

            ScenarioStep currentScenarioNameStep;
            System.out.println("@@pickle/. size: " + pickles.size());
            for (Pickle pickle : pickles) {
                currentScenarioNameStep = createScenarioStep(pickle);
                currentStep.childSteps.add(currentScenarioNameStep);

                if (lastScenarioNameStep != null) {
                    System.out.println("@@pairSiblings!!");
                    System.out.println("@@lastScenarioNameStep: " + lastScenarioNameStep);
                    System.out.println("@@currentScenarioNameStep: " + currentScenarioNameStep);
                    lastScenarioNameStep.nextSibling = currentScenarioNameStep;
                    currentScenarioNameStep.previousSibling = lastScenarioNameStep;
                }
                lastScenarioNameStep = currentScenarioNameStep;
            }
        }
    }
}
