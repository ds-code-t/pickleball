package tools.dscode.coredefinitions;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import org.intellij.lang.annotations.Language;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.ScenarioStep.createScenarioStep;
import static tools.dscode.common.GlobalConstants.COMPONENT_TAG_META_CHAR;
import static tools.dscode.common.util.Reflect.getProperty;

public class ModularScenarios extends CoreSteps {


//    @DefinitionFlags(NO_LOGGING)
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

    static final @Language("RegExp") String tagRegexReplacement = "(?<!@)("+ COMPONENT_TAG_META_CHAR + "[A-Za-z])";

    public static void filterAndExecutePickles(List<Map<String, String>> maps, String... messageString) {
        StepExtension currentStep = getRunningStep();
        StepExtension lastScenarioNameStep = null;

        for (Map<String, String> map : maps) {
            String tagString = map.getOrDefault("Tags", "");
            tagString = tagString.replaceAll(tagRegexReplacement, "@$1");
            List<Pickle> pickles = CucumberScanUtil.listPicklesByTags(tagString);

            ScenarioStep currentScenarioNameStep;
            for (Pickle gherkinMessagesPickle : pickles) {
                currentScenarioNameStep = createScenarioStep(gherkinMessagesPickle);
                io.cucumber.messages.types.Pickle pickle = (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");
                NodeMap passedMap = new NodeMap(MapConfigurations.MapType.PASSED_MAP);
                passedMap.merge(map);
                currentScenarioNameStep.getStepParsingMap().addMaps(passedMap);
                    if(pickle.getValueRow()!=null && !pickle.getValueRow().isEmpty()) {
                        NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
                        examples.merge(pickle.getHeaderRow(), pickle.getValueRow());
                        currentScenarioNameStep.getStepParsingMap().addMaps(examples);
                    }

                currentStep.childSteps.add(currentScenarioNameStep);

                if (lastScenarioNameStep != null) {
                    lastScenarioNameStep.nextSibling = currentScenarioNameStep;
                    currentScenarioNameStep.previousSibling = lastScenarioNameStep;
                }
                lastScenarioNameStep = currentScenarioNameStep;
            }
        }
    }
}
