//package io.pickleball.cucumberutilities;
//
//import java.net.URI;
//import java.util.List;
//import java.util.UUID;
//import java.util.stream.Collectors;
//
//public class FeatureFileUtilities2 {
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesFeature parseFeature(URI uri) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser parser = new io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser();
//        String featureSource = readFeatureFile(uri); // Ensure this reads the file correctly
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = (io.cucumber.core.gherkin.messages.GherkinMessagesFeature) parser.parse(uri, featureSource, UUID::randomUUID)
//                .orElseThrow(() -> new RuntimeException("Failed to parse feature file at URI: " + uri));
//
//        // Print all scenario IDs for debugging
//        System.out.println("Scenarios found in the feature:");
//        feature.getChildren().stream()
//                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                .forEach(child -> {
//                    io.cucumber.core.gherkin.messages.GherkinMessagesScenario scenario =
//                            (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child;
//                    System.out.println("Scenario ID: " + scenario.getScenario().getId());
//                    System.out.println("Scenario Name: " + scenario.getScenario().getName());
//                });
//
//        return feature;
//    }
//
//
//    public static int getScenarioCount(URI uri) {
//        io.cucumber.core.gherkin.Feature feature = parseFeature(uri);
//        return feature.getPickles().size();
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesScenario getScenarioSource(URI uri, String scenarioId) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
//
//        return feature.getChildren().stream()
//                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                .map(child -> (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child)
//                .filter(scenario -> scenario.getScenario().getId().equals(scenarioId))
//                .findFirst()
//                .orElseThrow(() -> {
//                    System.err.println("Error: Scenario with ID '" + scenarioId + "' not found.");
//                    System.err.println("Available Scenarios:");
//                    feature.getChildren().stream()
//                            .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                            .map(child -> (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child)
//                            .forEach(scenario -> {
//                                System.err.println("  ID: " + scenario.getScenario().getId() +
//                                        ", Name: " + scenario.getScenario().getName());
//                            });
//                    return new RuntimeException("Scenario with ID '" + scenarioId + "' not found in feature file.");
//                });
//    }
//
//
//
//    private static io.cucumber.messages.types.Scenario findScenarioByPickle(
//            io.cucumber.core.gherkin.Feature feature,
//            io.cucumber.messages.types.Pickle pickle
//    ) {
//        if (feature instanceof io.cucumber.core.gherkin.messages.GherkinMessagesFeature gherkinFeature) {
//            for (io.cucumber.messages.types.Scenario scenario : extractScenariosFromFeature(gherkinFeature)) {
//                if (pickle.getAstNodeIds().contains(scenario.getId())) {
//                    return scenario;
//                }
//            }
//        }
//        throw new RuntimeException("Scenario not found for Pickle ID: " + pickle.getId());
//    }
//
//    private static List<io.cucumber.messages.types.Scenario> extractScenariosFromFeature(
//            io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature
//    ) {
//        return feature.getChildren().stream()
//                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                .map(child -> {
//                    // Extract the `Scenario` from `GherkinMessagesScenario`
//                    io.cucumber.core.gherkin.messages.GherkinMessagesScenario scenarioNode =
//                            (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child;
//                    return scenarioNode.scenario; // Direct access to the wrapped `io.cucumber.messages.types.Scenario`
//                })
//                .collect(Collectors.toList());
//    }
//
//
//
//
//    public static Pair<List<String>, List<String>> getScenarioOutlineRow(URI uri, String scenarioId, int rowIndex) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesScenario scenario = getScenarioSource(uri, scenarioId);
//
//        List<io.cucumber.messages.types.Examples> examplesList = scenario.getExamples();
//
//        if (examplesList.isEmpty()) {
//            throw new RuntimeException("Scenario with ID " + scenarioId + " does not have examples.");
//        }
//
//        io.cucumber.messages.types.Examples examples = examplesList.get(0); // Assuming the first Examples block
//        io.cucumber.messages.types.TableRow headerRow = examples.getTableHeader()
//                .orElseThrow(() -> new RuntimeException("Scenario with ID " + scenarioId + " has no header row."));
//        List<io.cucumber.messages.types.TableRow> bodyRows = examples.getTableBody();
//
//        if (rowIndex < 0 || rowIndex >= bodyRows.size()) {
//            throw new RuntimeException("Row index " + rowIndex + " is out of bounds for the example table.");
//        }
//
//        io.cucumber.messages.types.TableRow dataRow = bodyRows.get(rowIndex);
//
//        return new Pair<>(
//                headerRow.getCells().stream().map(io.cucumber.messages.types.TableCell::getValue).collect(Collectors.toList()),
//                dataRow.getCells().stream().map(io.cucumber.messages.types.TableCell::getValue).collect(Collectors.toList())
//        );
//    }
//
//    public static io.cucumber.messages.types.Examples getScenarioOutlineExamples(URI uri, String scenarioId) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesScenario scenario = getScenarioSource(uri, scenarioId);
//
//        return scenario.getScenario().getExamples().stream()
//                .findFirst()
//                .orElseThrow(() -> {
//                    System.err.println("Error: No examples table found for Scenario ID '" + scenarioId + "'.");
//                    return new RuntimeException("Scenario with ID '" + scenarioId + "' does not have an examples table.");
//                });
//    }
//
//
//    private static String readFeatureFile(URI uri) {
//        try {
//            return java.nio.file.Files.readString(java.nio.file.Paths.get(uri));
//        } catch (java.io.IOException e) {
//            throw new RuntimeException("Failed to read feature file at URI: " + uri, e);
//        }
//    }
//
//    public static class Pair<K, V> {
//        private final K key;
//        private final V value;
//
//        public Pair(K key, V value) {
//            this.key = key;
//            this.value = value;
//        }
//
//        public K getKey() {
//            return key;
//        }
//
//        public V getValue() {
//            return value;
//        }
//
//        @Override
//        public String toString() {
//            return "Pair{" + "key=" + key + ", value=" + value + '}';
//        }
//    }
//
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesScenario getScenarioByName(URI uri, String scenarioName) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
//
//        return feature.getChildren().stream()
//                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                .map(child -> (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child)
//                .filter(scenario -> scenario.getScenario().getName().equalsIgnoreCase(scenarioName))
//                .findFirst()
//                .orElseThrow(() -> new RuntimeException("Scenario with name '" + scenarioName + "' not found in feature file."));
//    }
//
//
//    public static List<io.cucumber.core.gherkin.messages.GherkinMessagesScenario> getScenariosByTag(URI uri, String tagName) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = parseFeature(uri);
//
//        return feature.getChildren().stream()
//                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                .map(child -> (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child)
//                .filter(scenario -> scenario.getScenario().getTags().stream()
//                        .anyMatch(tag -> tag.getName().equalsIgnoreCase(tagName)))
//                .collect(Collectors.toList());
//    }
//
//    public static io.cucumber.messages.types.Scenario getScenarioByLine(URI uri, int lineNumber) {
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeature feature = (io.cucumber.core.gherkin.messages.GherkinMessagesFeature) parseFeature(uri);
//
//        return feature.getChildren().stream()
//                .filter(child -> child instanceof io.cucumber.core.gherkin.messages.GherkinMessagesScenario)
//                .map(child -> (io.cucumber.core.gherkin.messages.GherkinMessagesScenario) child)
//                .filter(scenario -> scenario.getLocation().getLine() == lineNumber)
//                .map(io.cucumber.core.gherkin.messages.GherkinMessagesScenario::getScenario)
//                .findFirst()
//                .orElseThrow(() -> new RuntimeException("Scenario at line " + lineNumber + " not found in feature file."));
//    }
//
//
//}
