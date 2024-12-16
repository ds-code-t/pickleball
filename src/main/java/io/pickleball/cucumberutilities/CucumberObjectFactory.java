package io.pickleball.cucumberutilities;

import io.cucumber.core.backend.ParameterInfo;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class CucumberObjectFactory {

    private static final String MINIMAL_FEATURE_TEMPLATE = """
        Feature: Minimal Feature Template
        
          Scenario: Minimal Scenario Template
            {stepText}
        """;

    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
            String stepText) {
        return createPickleStepTestStep(stepText, null, null);
    }

    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
            String stepText,
            io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument dataTable,
            io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument docString) {
        String featureSource = MINIMAL_FEATURE_TEMPLATE.replace("{stepText}", "Given " + stepText);
        URI dummyUri = URI.create("file://minimal.feature");

        // Parse the minimal feature
        io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser parser =
                new io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser();
        io.cucumber.core.gherkin.Feature feature = parser.parse(dummyUri, featureSource, UUID::randomUUID)
                .orElseThrow(() -> new RuntimeException("Failed to parse feature"));

        // Get the first pickle and step
        io.cucumber.core.gherkin.Pickle pickle = feature.getPickles().get(0);
        io.cucumber.core.gherkin.Step step = pickle.getSteps().get(0);

        // Create a dummy StepDefinition
        io.cucumber.core.backend.StepDefinition dummyStepDefinition = new io.cucumber.core.backend.StepDefinition() {
            @Override
            public String getPattern() {
                return stepText;
            }

//            @Override
            public List<String> matchedArguments(String stepText) {
                return Collections.emptyList();
            }

            @Override
            public void execute(Object[] args) {
//                return null;
            }

            @Override
            public List<ParameterInfo> parameterInfos() {
                return List.of();
            }

            @Override
            public boolean isDefinedAt(StackTraceElement stackTraceElement) {
                return false;
            }

            @Override
            public String getLocation() {
                return "dummy location";
            }
        };

        // Create the PickleStepDefinitionMatch
        io.cucumber.core.runner.PickleStepDefinitionMatch definitionMatch =
                new io.cucumber.core.runner.PickleStepDefinitionMatch(
                        Collections.emptyList(), // No arguments
                        dummyStepDefinition,     // Dummy step definition
                        dummyUri,
                        step
                );

        // Create the PickleStepTestStep
        return new io.cucumber.core.runner.PickleStepTestStep(
                UUID.randomUUID(),
                dummyUri,
                step,
                Collections.emptyList(), // HookTestSteps before
                Collections.emptyList(), // HookTestSteps after
                definitionMatch
        );
    }

    public static io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument createDataTableArgument(
            String tableSource) {
        // Convert table source into a 2D list
        List<List<String>> rawTable = List.of(
                List.of(tableSource.split("\\|")) // Split columns using "|"
        );

        // Create a DataTableArgument
        return new io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument(
                new io.cucumber.messages.types.PickleTable(
                        rawTable.stream()
                                .map(row -> new io.cucumber.messages.types.PickleTableRow(
                                        row.stream()
                                                .map(io.cucumber.messages.types.PickleTableCell::new)
                                                .toList()))
                                .toList()),
                1 // Line number (mocked)
        );
    }

    public static io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument createDocStringArgument(
            String docStringContent) {
        // Create a DocStringArgument
        return new io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument(
                new io.cucumber.messages.types.PickleDocString(
                        null,                 // Content type
                        docStringContent     // DocString content
                ),
                1 // Line number (mocked)
        );
    }
}
