package tools.ds.modkit.coredefinitions;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.ds.modkit.executions.StepExecution;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.mappings.NodeMap;
import tools.ds.modkit.mappings.ParsingMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

//import static tools.ds.modkit.coredefinitions.MetaSteps.RUN_SCENARIO;
import static tools.ds.modkit.executions.StepExecution.setNesting;
import static tools.ds.modkit.extensions.StepRelationships.pairSiblings;
import static tools.ds.modkit.modularexecutions.CucumberScanUtil.listPickles;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.stepbuilder.StepUtilities.createPickleStepTestStep;

public class ModularScenarios {


    @Given("RUN SCENARIOS")
    public static void runScenarios(DataTable dataTable) {
        StepExtension currentStep = getScenarioState().getCurrentStep();
        try {
            if(true)
                throw new RuntimeException("SSS");
            System.out.println("@@runScenarios==Datatble:\n" + dataTable);
            EventBus bus = getScenarioState().getBus();
            List<Map<String, String>> maps = dataTable.asMaps();
            Map<String, String> cucumberProps = new HashMap<>();
            for (Map<String, String> map : maps) {
                System.out.println("@@map-- " + map);
                String scenarioTags = map.get("Scenario Tags");
                String featurePaths = map.get("Features");
                System.out.println("@@scenarioTags: " + scenarioTags);
                if (scenarioTags != null)
                    cucumberProps.put("cucumber.filter.tags", scenarioTags);
                if (featurePaths != null)
                    cucumberProps.put("cucumber.features", featurePaths);
                List<Pickle> pickles = listPickles(cucumberProps);

//            StepExtension nextStep = currentStep.getNextSibling();

                int startingNestingLevel = getScenarioState().getCurrentStep().getNestingLevel() + 1;

                StepExecution stepExecution = getScenarioState().stepExecution;
                StepExtension currentScenarioNameStep;
                StepExtension lastScenarioNameStep = null;
                for (Pickle pickle : pickles) {
                    System.out.println("@@pickle*: " + pickle.getName());
                    System.out.println("@@currentStep=: " + currentStep);
//                final String overRideStepText = RUN_SCENARIO + pickle.getName();
                    NodeMap scenarioMap = getScenarioState().getScenarioMap(pickle);

                    List<StepExtension> stepExtensions = pickle.getSteps().stream().map(s -> new StepExtension(createPickleStepTestStep(s, bus.generateId(), pickle.getUri()), stepExecution, pickle)).toList();
                    currentScenarioNameStep = new StepExtension(pickle, stepExecution, stepExtensions.getFirst().delegate);

                    currentStep.addChildStep(currentScenarioNameStep);

                    if (scenarioMap != null) {
                        scenarioMap.setMapType(ParsingMap.MapType.STEP_MAP);
                        scenarioMap.setDataSource(NodeMap.DataSource.PASSED_TABLE);
                        currentScenarioNameStep.getStepParsingMap().replaceMaps(scenarioMap);
                    }

                    if (lastScenarioNameStep != null) {
                        pairSiblings(lastScenarioNameStep, currentScenarioNameStep);
                    }

                    lastScenarioNameStep = currentScenarioNameStep;
                    Map<Integer, StepExtension> nestingMap = new HashMap<>();
                    nestingMap.put(startingNestingLevel - 1, currentScenarioNameStep);
                    setNesting(stepExtensions, startingNestingLevel, nestingMap);
                }
                if (pickles.isEmpty()) {
                    StepExtension messageStep = getScenarioState().getCurrentStep().createMessageStep("Message tEst3");
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
