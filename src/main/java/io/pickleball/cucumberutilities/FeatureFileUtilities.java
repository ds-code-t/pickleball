package io.pickleball.cucumberutilities;

import io.cucumber.core.gherkin.messages.*;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class FeatureFileUtilities {

    /**
     * Reads the feature file content from a URI.
     */
    public static String readFeatureFile(URI uri) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Paths.get(uri));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read feature file at URI: " + uri, e);
        }
    }

    /**
     * Parses a feature file source into a GherkinMessagesFeature.
     */
    public static io.cucumber.core.gherkin.messages.GherkinMessagesFeature parseFeature(URI uri) {
        String featureSource = readFeatureFile(uri);
        io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser parser = new io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser();
        return (io.cucumber.core.gherkin.messages.GherkinMessagesFeature) parser.parse(uri, featureSource, UUID::randomUUID)
                .orElseThrow(() -> new RuntimeException("Failed to parse feature file at URI: " + uri));
    }

    /**
     * Collects all Gherkin components from the feature and its children, mapping them by line number.
     */
    private static void collectComponentsByLine(
            GherkinMessagesFeature feature,
            Map<Integer, Object> componentMap
    ) {
        feature.getChildren().forEach(node -> {
            if (node instanceof GherkinMessagesScenario) {
                GherkinMessagesScenario scenario =
                        (GherkinMessagesScenario) node;
                componentMap.put(scenario.getLocation().getLine(), scenario);
            } else if (node instanceof GherkinMessagesScenarioOutline) {
                GherkinMessagesScenarioOutline scenarioOutline =
                        (GherkinMessagesScenarioOutline) node;
                componentMap.put(scenarioOutline.getLocation().getLine(), scenarioOutline);

                // Collect Examples and their rows
                // ERROR:  Cannot resolve method 'getExamples' in 'GherkinMessagesScenarioOutline'
                List<GherkinMessagesExamples> examplesList = scenarioOutline.getExamples();
                examplesList.forEach(examples -> {
                    componentMap.put(examples.getLocation().getLine(), examples);
                    // ERROR: Cannot resolve method 'getTableBody' in 'GherkinMessagesExamples'
                    // ERROR:  Cannot resolve method 'getLocation()'
                    List<GherkinMessagesExample> exampleList = examples.getChildren();
                    exampleList.forEach(example -> {
                        componentMap.put(example.getLocation().getLine(), example);

                        componentMap.put(Math.toIntExact(example.getTableRow().getLocation().getLine()), example);

                    });

                });
            }
        });
    }

    /**
     * Retrieves any Gherkin component (Scenario, ScenarioOutline, Examples, etc.) by line number.
     */
    public static Object getComponentByLine(URI uri, int lineNumber) {
        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
        Map<Integer, Object> componentMap = new HashMap<>();
        collectComponentsByLine(feature, componentMap);

        return Optional.ofNullable(componentMap.get(lineNumber))
                .orElseThrow(() -> new RuntimeException("No component found at line " + lineNumber + " in the feature file."));
    }

    /**
     * Retrieves a list of all scenarios in the feature file.
     */
    public static List<io.cucumber.messages.types.Scenario> getAllScenarios(URI uri) {
        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
        return feature.getChildren().stream()
                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
                .map(child -> (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child)
                .map(io.cucumber.core.gherkin.messages.GherkinMessagesScenario::getScenario)
                .collect(Collectors.toList());
    }
}
