package tools.ds.modkit.coredefinitions;

import annotations.NoLogging;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.ds.modkit.executions.StepExecution;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.mappings.NodeMap;
import tools.ds.modkit.mappings.ParsingMap;

import java.util.*;

//import static tools.ds.modkit.coredefinitions.MetaSteps.RUN_SCENARIO;
import static tools.ds.modkit.blackbox.BlackBoxBootstrap.ComponentTagPrefix;
import static tools.ds.modkit.executions.StepExecution.setNesting;
import static tools.ds.modkit.extensions.StepRelationships.pairSiblings;
import static tools.ds.modkit.modularexecutions.CucumberScanUtil.listPickles;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.stepbuilder.StepUtilities.createPickleStepTestStep;

public class ModularScenarios {

//    public static final String componentPrefix = "@_COMPONENT_";

    @NoLogging
    @Given("^RUN COMPONENT SCENARIO:?(.*)?$")
    public static void runComponentScenarios(String scenario, DataTable dataTable) {
        List<Map<String, String>> maps = dataTable.asMaps().stream()
                .map(HashMap::new)                    // copy each to a mutable map
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (scenario != null && !scenario.isBlank()) {
            Map<String, String> map = new HashMap<>();
            map.put("Tags", scenario);
            maps.add(map);
        }
        maps.forEach(map -> {
                    map.computeIfPresent("Tags", (key, value) -> {
                        System.out.println("@@key: " + key);
                        System.out.println("@@value: " + value);
                        String cleaned = value.startsWith("@") ? value.substring(1) : value;
                        return ComponentTagPrefix + cleaned;
                    });

                }
        );

        System.out.println("@@RUN COMPONENT SCENA " + maps);
        filterAndExecutePickles(maps, "'RUN COMPONENT SCENARIOS' step");
    }

    @NoLogging
    @Given("^RUN SCENARIOS:?(.*)?$")
    public static void runScenarios(String scenario, DataTable dataTable) {
        List<Map<String, String>> maps = dataTable.asMaps().stream()
                .map(HashMap::new)                    // copy each to a mutable map
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (scenario != null && !scenario.isBlank()) {
            Map<String, String> map = new HashMap<>();
            map.put("Tags", scenario);
            maps.add(map);
        }
        filterAndExecutePickles(maps, "'RUN SCENARIOS' step");
    }

    public static void filterAndExecutePickles(List<Map<String, String>> maps, String... messageString) {
        System.out.println("@@runScenarios - " + maps);
        String messagePrefix = String.join("," + Arrays.stream(messageString).toList());
        StepExtension currentStep = getScenarioState().getCurrentStep();
        if (maps.isEmpty()) {
            StepExtension messageStep = getScenarioState().getCurrentStep().createMessageStep(messagePrefix + " No scenario data");
            currentStep.insertNextSibling(messageStep);
            return;
        }

        try {
            StepExtension lastScenarioNameStep = null;
            EventBus bus = getScenarioState().getBus();
//            List<Map<String, String>> maps = dataTable.asMaps();
            Map<String, String> cucumberProps = new HashMap<>();
            for (Map<String, String> map : maps) {
                System.out.println(" " + map);
                if(!map.containsKey("Tags") && !map.containsKey("Features")) {
                    StepExtension messageStep = getScenarioState().getCurrentStep().createMessageStep(messagePrefix + " No 'Tags' , or 'Features' set");
                    messageStep.storedThrowable = new RuntimeException("Scenario execution steps set with missing or incorrect parameters.  Check the datatatable");
                    currentStep.insertNextSibling(messageStep);
                    return;
                }
                String scenarioTags = map.get("Tags");
                String featurePaths = map.get("Features");
                if((scenarioTags ==null || scenarioTags.isBlank()) && (featurePaths ==null || featurePaths.isBlank()))
                {
                    StepExtension messageStep = getScenarioState().getCurrentStep().createMessageStep(messagePrefix + " No 'Tags' , or 'Features' set");
                    currentStep.insertNextSibling(messageStep);
                    lastScenarioNameStep  = messageStep;
                    continue;
                }
                System.out.println("@@scenarioTags: " + scenarioTags);
                if (scenarioTags != null)
                    cucumberProps.put("cucumber.filter.tags", scenarioTags);
                if (featurePaths != null)
                    cucumberProps.put("cucumber.features", featurePaths);
                List<Pickle> pickles = listPickles(cucumberProps);

                int startingNestingLevel = getScenarioState().getCurrentStep().getNestingLevel() + 1;

                StepExecution stepExecution = getScenarioState().stepExecution;
                StepExtension currentScenarioNameStep;
                System.out.println("@@pickle/. size: " + pickles.size());
                for (Pickle pickle : pickles) {
                    System.out.println("@@pickle*: " + pickle.getName());
                    System.out.println("@@currentStep=: " + currentStep);
//                final String overRideStepText = RUN_SCENARIO + pickle.getName();
                    NodeMap scenarioMap = getScenarioState().getScenarioMap(pickle);

                    List<StepExtension> stepExtensions = pickle.getSteps().stream().map(s -> new StepExtension(createPickleStepTestStep(s, bus.generateId(), pickle.getUri()), stepExecution, pickle)).toList();
                    currentScenarioNameStep = new StepExtension(pickle, stepExecution, stepExtensions.getFirst().delegate);

                    currentStep.addChildStep(currentScenarioNameStep);
                    System.out.println("@@currentStep: " + currentStep);
                    System.out.println("@@currentStep-getChildSteps " + currentStep.getChildSteps());
                    System.out.println("@@currentScenarioNameStep: " + currentScenarioNameStep);
                    System.out.println("@@currentScenarioNameStep-getChildSteps " + currentScenarioNameStep.getChildSteps());
                    if (scenarioMap != null) {
                        scenarioMap.setMapType(ParsingMap.MapType.STEP_MAP);
                        scenarioMap.setDataSource(NodeMap.DataSource.PASSED_TABLE);
                        currentScenarioNameStep.getStepParsingMap().replaceMaps(scenarioMap);
                    }

                    System.out.println("@@lastScenarioNameStep:-- " + lastScenarioNameStep);
                    System.out.println("@@currentScenarioNameStep:-- " + currentScenarioNameStep);
                    if (lastScenarioNameStep != null) {
                        System.out.println("@@pairSiblings!!");
                        System.out.println("@@lastScenarioNameStep: " + lastScenarioNameStep);
                        System.out.println("@@currentScenarioNameStep: " + currentScenarioNameStep);

                        pairSiblings(lastScenarioNameStep, currentScenarioNameStep);
                    }

                    Map<Integer, StepExtension> nestingMap = new HashMap<>();
                    nestingMap.put(startingNestingLevel - 1, currentScenarioNameStep);
                    setNesting(stepExtensions, startingNestingLevel, nestingMap);
                    lastScenarioNameStep = currentScenarioNameStep;

                }
                if (pickles.isEmpty()) {
                    StepExtension messageStep = getScenarioState().getCurrentStep().createMessageStep(messagePrefix + " step had No Matching Scenarios for " + map);
                    messageStep.storedThrowable = new RuntimeException("Scenario execution step No Matching Scenarios for " + map);
                    currentStep.insertNextSibling(messageStep);
                }
            }


        } catch (Throwable t) {
            t.printStackTrace();
            StepExtension messageStep = getScenarioState().getCurrentStep().createMessageStep("ERROR in 'RUN SCENARIOS' " + t.getMessage());
            messageStep.storedThrowable = t;
            currentStep.insertNextSibling(messageStep);
        }

    }
}
