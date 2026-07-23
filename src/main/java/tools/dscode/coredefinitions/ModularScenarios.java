package tools.dscode.coredefinitions;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.ScenarioStep.createScenarioStep;
import static tools.dscode.common.util.Reflect.getProperty;

public class ModularScenarios extends CoreSteps {

    static final String RUN_TAGS = "Run Tags";
    static final String TAGS = "Tags";
    static final String CUCUMBER_FEATURES = "cucumber.features";

    @Given("^RUN SCENARIOS?:?(.*)?$")
    public static void runScenarios(String inlineTags, DataTable dataTable) {
        populateRunScenariosStep(getRunningStep(), inlineTags, dataTable);
    }

    public static void populateRunScenariosStep(
            StepExtension topStep,
            String inlineTags,
            DataTable dataTable
    ) {
        filterAndParsePickles(topStep, buildRunScenarioMaps(inlineTags, dataTable));
    }

    public static void populateRunScenariosStep(
            StepExtension topStep,
            String inlineTags,
            DataTable dataTable,
            String featuresPath,
            String singleMatchType,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer
    ) {
        populateRunScenariosStep(
                topStep,
                inlineTags,
                dataTable,
                featuresPath,
                singleMatchType,
                scenarioInitializer,
                null
        );
    }

    /**
     * Creates component ScenarioSteps and optionally inserts one synthetic step
     * immediately after each component scenario.
     *
     * <p>The synthetic step is a sibling, not a descendant. This lets it run
     * after a component scenario has completed or used END SCENARIO.</p>
     */
    public static void populateRunScenariosStep(
            StepExtension topStep,
            String inlineTags,
            DataTable dataTable,
            String featuresPath,
            String singleMatchType,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer,
            BiFunction<ScenarioStep, Map<String, String>, StepExtension> scenarioFinalizerFactory
    ) {
        filterAndParsePickles(
                topStep,
                buildRunScenarioMaps(inlineTags, dataTable),
                featuresPath,
                singleMatchType,
                scenarioInitializer,
                scenarioFinalizerFactory
        );
    }

    static List<Map<String, String>> buildRunScenarioMaps(
            String inlineTags,
            DataTable dataTable
    ) {
        List<Map<String, String>> maps = dataTable == null
                ? new ArrayList<>()
                : dataTable.asMaps().stream()
                .map(row -> {
                    Map<String, String> copy = new HashMap<>();
                    row.forEach((key, value) -> copy.put(key, value == null ? "" : value));
                    return copy;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (inlineTags != null && !inlineTags.isBlank()) {
            if (maps.isEmpty()) {
                Map<String, String> singleMap = new HashMap<>();
                singleMap.put(RUN_TAGS, inlineTags);
                maps.add(singleMap);
            } else {
                maps.forEach(map -> map.put(
                        RUN_TAGS,
                        (inlineTags + " " + map.getOrDefault(RUN_TAGS, map.getOrDefault(TAGS, ""))).trim()
                ));
            }
        }

        return maps;
    }

    public static void filterAndParsePickles(
            StepExtension topStep,
            List<Map<String, String>> maps,
            String... messageString
    ) {
        filterAndParsePickles(topStep, maps, null, null, null, null);
    }

    static void filterAndParsePickles(
            StepExtension topStep,
            List<Map<String, String>> maps,
            String featuresPath,
            String singleMatchType,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer,
            BiFunction<ScenarioStep, Map<String, String>, StepExtension> scenarioFinalizerFactory
    ) {
        StepExtension lastLinkedStep = null;

        for (Map<String, String> map : maps) {
            String runTags = map.getOrDefault(RUN_TAGS, map.getOrDefault(TAGS, "")).trim();
            if (singleMatchType != null && runTags.isBlank()) {
                continue;
            }

            Map<String, String> scanOptions = new HashMap<>(map);
            if (featuresPath != null && !featuresPath.isBlank()) {
                scanOptions.put(CUCUMBER_FEATURES, featuresPath);
            }

            List<Pickle> pickles;
            try {
                pickles = CucumberScanUtil.listPickles(scanOptions);
            } catch (IllegalArgumentException exception) {
                if (singleMatchType == null) {
                    throw exception;
                }
                throw new IllegalArgumentException(
                        "No " + singleMatchType + " matched Run Tags [" + runTags
                                + "] under [" + featuresPath + "]",
                        exception
                );
            }

            if (singleMatchType != null && pickles.size() != 1) {
                throw new IllegalArgumentException(
                        "Run Tags [" + runTags + "] matched " + pickles.size() + " "
                                + singleMatchType + " scenarios under [" + featuresPath + "]: "
                                + pickles.stream().map(Pickle::getName).distinct().toList()
                );
            }

            for (Pickle gherkinMessagesPickle : pickles) {
                ParsingMap scenarioStepParsingMap = new ParsingMap();

                NodeMap passedMap = new NodeMap(MapConfigurations.MapType.PASSED_MAP);
                passedMap.merge(map);
                scenarioStepParsingMap.addMaps(passedMap);

                io.cucumber.messages.types.Pickle pickle =
                        (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");

                if (pickle.getValueRow() != null && !pickle.getValueRow().isEmpty()) {
                    NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
                    examples.merge(pickle.getHeaderRow(), pickle.getValueRow());
                    scenarioStepParsingMap.addMaps(examples);
                }

                ScenarioStep scenarioStep = createScenarioStep(
                        gherkinMessagesPickle,
                        scenarioStepParsingMap
                );

                if (scenarioInitializer != null) {
                    scenarioInitializer.accept(scenarioStep, map);
                }

                lastLinkedStep = appendChild(topStep, lastLinkedStep, scenarioStep);

                if (scenarioFinalizerFactory != null) {
                    StepExtension finalizer = scenarioFinalizerFactory.apply(scenarioStep, map);
                    if (finalizer != null) {
                        lastLinkedStep = appendChild(topStep, lastLinkedStep, finalizer);
                    }
                }
            }
        }
    }

    private static StepExtension appendChild(
            StepExtension parent,
            StepExtension previous,
            StepExtension child
    ) {
        child.parentStep = parent;
        child.previousSibling = previous;
        child.nextSibling = null;
        parent.childSteps.add(child);

        if (previous != null) {
            previous.nextSibling = child;
        }

        return child;
    }
}
