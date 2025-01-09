package io.pickleball.cucumberutilities;

import io.cucumber.core.gherkin.messages.*;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.gherkin.PickleCompiler;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Pickle;
import io.cucumber.plugin.event.Node;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;


public class FeatureFileUtilities {

    public static void main(String[] args) throws IOException {
        String testFeatureSource = "Feature: Test feature\n" +
                "\n" +
                "  Scenario: Test scenario\n" +
                "    Given I am running a testlzz z and \n";

        List<Envelope> envelopes = GherkinParser.builder()
                .idGenerator(() -> java.util.UUID.randomUUID().toString())
                .build()
                .parse("feature", new ByteArrayInputStream(testFeatureSource.getBytes(StandardCharsets.UTF_8)))
                .toList();


    }


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

    public static List<Node> collectNodesByLineNumbers(URI uri, Set<Integer> lineNumbers) {
        if (lineNumbers == null || lineNumbers.isEmpty()) {
            return Collections.emptyList();
        }
        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
        // Sort the line numbers in ascending order for efficiency
        List<Integer> lineList = lineNumbers.stream().toList();
        List<Node> matchingNodes = new ArrayList<>();

        // Traverse feature children and match nodes with sorted line numbers
        feature.getChildren().forEach(node -> {
            if (lineList.contains(node.getLocation().getLine()))
                matchingNodes.add(node);

            if (node instanceof Node.ScenarioOutline) {
                ((GherkinMessagesScenarioOutline) node).getExamples().forEach(examples -> {
                    GherkinMessagesExamples gherkinMessagesExamples = (GherkinMessagesExamples) examples;
                    if (lineList.contains(gherkinMessagesExamples.getLocation().getLine()))
                        matchingNodes.add(gherkinMessagesExamples);
                  gherkinMessagesExamples.getChildren().forEach(example ->{
                      if (lineList.contains(example.getLocation().getLine()))
                          matchingNodes.add(example);
                  } );

                });
            }


        });

        return matchingNodes;
    }


    public static GherkinMessagesPickle getModifiedPickle(String scenarioSource, GherkinMessagesPickle gherkinMessagesPickle) throws IOException {
        if (scenarioSource == null || scenarioSource.isBlank()) {
            throw new IllegalArgumentException("Feature source cannot be null or blank");
        }

        String featureSource = "Feature: Test feature" + "\n".repeat(gherkinMessagesPickle.getScenarioLocation().getLine()-1) + (gherkinMessagesPickle.getKeyword().equals("Scenario Outline") ? scenarioSource.replaceFirst("Scenario Outline:", "Scenario:") : scenarioSource) ;
        featureSource = featureSource + "\n".repeat(10);

        // Parse the feature source into Gherkin Envelopes
        List<Envelope> envelopes = GherkinParser.builder()
                .idGenerator(() -> java.util.UUID.randomUUID().toString())
                .build()
                .parse("feature", new ByteArrayInputStream(featureSource.getBytes(StandardCharsets.UTF_8)))
                .toList();


        // Extract the GherkinDocument from the envelopes
        GherkinDocument gherkinDocument = envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No GherkinDocument found in feature source"));

        // Extract the Pickle (Scenario) from the envelopes
        Pickle pickle = envelopes.stream()
                .map(Envelope::getPickle)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No Pickle found for Scenario in feature source"));


        CucumberQuery cucumberQuery = new CucumberQuery();
        cucumberQuery.update(gherkinDocument.getFeature().get());

        return new GherkinMessagesPickle(pickle, gherkinMessagesPickle.getUri() , gherkinMessagesPickle.getDialect(), cucumberQuery, gherkinMessagesPickle.getTags());
    }


    public static GherkinDocument pickleCompiler(URI uri) throws IOException {
        String featureSource = readFeatureFile(uri);
        PickleCompiler pickleCompiler = new PickleCompiler(new SimpleIdGenerator());

        return createGherkinDocument(featureSource).get();

    }

    public static Optional<GherkinDocument> createGherkinDocument(String source) throws IOException {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Feature source cannot be null or blank");
        }

        // Convert the feature source string into an InputStream
        InputStream sourceStream = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));

        // Use GherkinParser to parse the feature content
        List<Envelope> envelopes = GherkinParser.builder()
                .idGenerator(() -> java.util.UUID.randomUUID().toString())
                .build()
                .parse("feature", sourceStream)
                .collect(Collectors.toList());

        // Extract and return the GherkinDocument from the envelopes
        return envelopes.stream()
                .map(Envelope::getGherkinDocument)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
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
    public static Node getComponentByLine(URI uri, int lineNumber) {
        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
        Map<Integer, Object> componentMap = new HashMap<>();
        collectComponentsByLine(feature, componentMap);

        return (Node) Optional.ofNullable(componentMap.get(lineNumber))
                .orElseThrow(() -> new RuntimeException("No component found at line " + lineNumber + " in the feature file."));
    }

    public static Node getComponentByLine(GherkinMessagesFeature feature, int lineNumber) {
        Map<Integer, Object> componentMap = new HashMap<>();
        collectComponentsByLine(feature, componentMap);

        return (Node) Optional.ofNullable(componentMap.get(lineNumber))
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
