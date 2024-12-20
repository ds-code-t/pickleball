package io.pickleball.cucumberutilities;

import io.cucumber.core.gherkin.messages.GherkinMessagesExample;
import io.cucumber.core.gherkin.messages.GherkinMessagesPickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesScenario;
import io.cucumber.core.gherkin.messages.GherkinMessagesScenarioOutline;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.plugin.event.Node;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;
import static io.pickleball.cucumberutilities.FeatureFileUtilities.collectNodesByLineNumbers;
import static io.pickleball.cucumberutilities.FeatureFileUtilities.getModifiedPickle;
import static io.pickleball.cucumberutilities.SourceParsing.pretrim;


public class SourceParser {

    public static GherkinMessagesPickle getComponentScenarioWrapper(GherkinMessagesPickle pickle, LinkedMultiMap<String, String>... listOfMaps) throws IOException {
        return getComponentScenarioWrapper(pickle, Arrays.stream(listOfMaps).toList());
    }

    public static GherkinMessagesPickle getComponentScenarioWrapper(GherkinMessagesPickle pickle, List<LinkedMultiMap<String, String>> maps) throws IOException {
        List<LinkedMultiMap<String, String>> listOfMaps = new ArrayList<>(maps);
        Set<Integer> lines = new HashSet<>();
        int startLine = pickle.getScenarioLocation().getLine();
        lines.add(startLine);
        lines.add(pickle.getLocation().getLine());
        List<Node> nodes = collectNodesByLineNumbers(pickle.getUri(), lines);

        Scenario scenario = null;
        for (Node node : nodes) {
            if (node instanceof Node.ScenarioOutline) {
                scenario = ((GherkinMessagesScenarioOutline) node).getScenario();
            } else if (node instanceof Node.Scenario) {
                scenario = ((GherkinMessagesScenario) node).getScenario();
            } else if (node instanceof Node.Example) {
                LinkedMultiMap<String, String> map = ((GherkinMessagesExample) node).getLinkedMultiMap();
                listOfMaps.add(map);
            }
        }
        ;
        String source = reconstructScenarioSource(scenario);

        String modified = replaceNestedBrackets(source, listOfMaps);
        return getModifiedPickle(modified, pickle);
//        modified = "Feature: Test feature" + "\n".repeat(startLine) + (isOutline ? modified.replaceFirst("Scenario Outline:", "Scenario:") : modified);


    }

    /**
     * Extracts the raw source string of a Scenario or Scenario Outline by its line number.
     *
     * @param featureSource The feature file content as a string.
     * @param lineNumber    The line number of the scenario or scenario outline.
     * @return The reconstructed Scenario source string.
     */
    public static Optional<String> getScenarioSourceByLine(String featureSource, int lineNumber) throws IOException {
        // Parse the feature source into a GherkinDocument
        GherkinDocument gherkinDocument = parseGherkinDocument(featureSource);
        if (gherkinDocument == null || gherkinDocument.getFeature().isEmpty()) {
            return Optional.empty();
        }

        // Search for the Scenario or Scenario Outline matching the line number
        return gherkinDocument.getFeature().get()
                .getChildren()
                .stream()
                .filter(featureChild -> isScenarioOrOutlineAtLine(featureChild, lineNumber))
                .map(FeatureChild::getScenario)  // Extract the Scenario Optional
                .filter(Optional::isPresent)     // Ensure the Scenario exists
                .map(Optional::get)              // Unwrap the Optional
                .map(SourceParser::reconstructScenarioSource) // Reconstruct the source
                .findFirst();
    }

    /**
     * Parses the feature source string into a GherkinDocument.
     */
    private static GherkinDocument parseGherkinDocument(String featureSource) throws IOException {
        List<Envelope> envelopes = GherkinParser.builder()
                .idGenerator(() -> java.util.UUID.randomUUID().toString())
                .build()
                .parse("feature", new ByteArrayInputStream(featureSource.getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.toList());

        return envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if a FeatureChild (Scenario or Scenario Outline) is at the given line number.
     */
    private static boolean isScenarioOrOutlineAtLine(FeatureChild featureChild, int lineNumber) {
        return featureChild.getScenario()
                .map(scenario -> scenario.getLocation().getLine() == lineNumber)
                .orElse(false);
    }

    /**
     * Reconstructs the raw Scenario or Scenario Outline source string from its components,
     * using blank lines to retain the original step positions.
     */
    public static String reconstructScenarioSource(io.cucumber.messages.types.Scenario scenario) {
        StringBuilder builder = new StringBuilder();
        builder.append("  ").append(scenario.getKeyword()).append(": ").append(scenario.getName()).append("\n");

        int currentLine = Math.toIntExact(scenario.getLocation().getLine() + 1); // Start from the next line after the Scenario line

        for (io.cucumber.messages.types.Step step : scenario.getSteps()) {
            int stepLine = Math.toIntExact(step.getLocation().getLine());

            // Insert blank lines to align steps correctly
            while (currentLine < stepLine) {
                builder.append("\n");
                currentLine++;
            }

            // Add the step line
            builder.append("    ").append(step.getKeyword()).append(pretrim(step.getText())).append("\n");
            currentLine++;
        }

        return builder.toString();
    }

//    /**
//     * Reconstructs the raw Scenario or Scenario Outline source string from its components.
//     */
//    public static String reconstructScenarioSource(io.cucumber.messages.types.Scenario scenario) {
//        StringBuilder builder = new StringBuilder();
//        builder.append("  ").append(scenario.getKeyword()).append(": ").append(scenario.getName()).append("\n");
//
//        // Append each step
//        for (Step step : scenario.getSteps()) {
//            builder.append("    ").append(step.getKeyword()).append(pretrim(step.getText())).append("\n");
//        }
//
//        return builder.toString();
//    }


//    ///
//        /**
//         * Extracts the raw source string of a Scenario by its name from a feature file source.
//         *
//         * @param featureSource The feature file source as a string.
//         * @param scenarioName  The name of the scenario to extract.
//         * @return The reconstructed Scenario source string.
//         */
//        public static Optional<String> getScenarioSource(String featureSource, String scenarioName) throws IOException {
//            // Parse the feature source into a GherkinDocument
//            GherkinDocument gherkinDocument = parseGherkinDocument(featureSource);
//            if (gherkinDocument == null || gherkinDocument.getFeature().isEmpty()) {
//                return Optional.empty();
//            }
//
//            // Traverse the feature to find the scenario by name
//            return gherkinDocument.getFeature().get()
//                    .getChildren()
//                    .stream()
//                    .filter(featureChild -> featureChild.getScenario().isPresent())
//                    .map(FeatureChild::getScenario)
//                    .filter(Optional::isPresent)
//                    .map(Optional::get)
//                    .filter(scenario -> scenario.getName().equals(scenarioName))
//                    .map(SourceParser::reconstructScenarioSource)
//                    .findFirst();
//        }

//        /**
//         * Parses the feature source string into a GherkinDocument.
//         */
//        private static GherkinDocument parseGherkinDocument(String featureSource) throws IOException {
//            List<Envelope> envelopes = GherkinParser.builder()
//                    .idGenerator(() -> java.util.UUID.randomUUID().toString())
//                    .build()
//                    .parse("feature", new ByteArrayInputStream(featureSource.getBytes(StandardCharsets.UTF_8)))
//                    .collect(Collectors.toList());
//
//            return envelopes.stream()
//                    .map(Envelope::getGherkinDocument)
//                    .filter(Optional::isPresent)
//                    .map(Optional::get)
//                    .findFirst()
//                    .orElse(null);
//        }
//
//        /**
//         * Reconstructs the raw Scenario source string from its components.
//         */
//        private static String reconstructScenarioSource(Scenario scenario) {
//            StringBuilder builder = new StringBuilder();
//            builder.append("  ").append(scenario.getKeyword()).append(": ").append(scenario.getName()).append("\n");
//
//            // Append each step
//            for (Step step : scenario.getSteps()) {
//                builder.append("    ").append(step.getKeyword()).append(step.getText()).append("\n");
//            }
//
//            return builder.toString();
//        }


}
