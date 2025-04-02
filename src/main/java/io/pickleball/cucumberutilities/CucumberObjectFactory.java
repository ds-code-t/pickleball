package io.pickleball.cucumberutilities;

import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
import io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument;
import io.cucumber.core.runner.AmbiguousStepDefinitionsException;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.TestStep;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.cucumber.core.gherkin.messages.GherkinMessagesStep.*;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentStep;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getRunner;
import static io.pickleball.configs.Constants.PREFIXES;
import static java.util.Arrays.asList;

public class CucumberObjectFactory {


    ///

    private static final String MINIMAL_FEATURE_TEMPLATE = """
            Feature: Minimal Feature Template            
              Scenario: Minimal Scenario Template
                {dynamicStep}
            """;

    public static io.cucumber.core.runner.PickleStepTestStep createDummuPickleStepTestStep(
            String dynamicStep) {
        PickleStepTestStep currentStep = getCurrentStep();
        return createPickleStepTestStep(dynamicStep, null, null, currentStep.getUri(), currentStep.getStepLine());
    }

    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
            String dynamicStep) {
        PickleStepTestStep currentStep = getCurrentStep();
        return createPickleStepTestStep(dynamicStep, currentStep.getGherkinMessagesDataTableArgument(), currentStep.getGherkinMessagesDocStringArgument(), currentStep.getUri(), currentStep.getStepLine());
    }

    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStepWithArgs(
            String dynamicStep,
            GherkinMessagesDataTableArgument dataTableArg,
            GherkinMessagesDocStringArgument docStringArg,
            URI overrideUri,
            int overrideLineNumber) {
        return createPickleStepTestStep(dynamicStep, dataTableArg, docStringArg, overrideUri, overrideLineNumber);
   }



    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
            String dynamicStep,
            io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument dataTable,
            io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument docString,
            URI overrideUri,
            int overrideLineNumber
    ) {
        io.cucumber.core.runner.Runner runner = getRunner();
        // Use the provided URI and line number, or defaults
        URI uriToUse = overrideUri != null ? overrideUri : URI.create("file://minimal.feature");
        int lineToUse = overrideLineNumber > 2 ? overrideLineNumber : 3;
        // Build the Gherkin step with DataTable or DocString arguments
        String stepText = dynamicStep.stripLeading();
        if (Arrays.stream(prefixWords).noneMatch(stepText::startsWith))
            stepText = "* " + stepText;
        StringBuilder stepBuilder = new StringBuilder(stepText);
        if (dataTable != null) {
            stepBuilder.append("\n").append(dataTable.cells().stream()
                    .map(row -> "| " + String.join(" | ", row) + " |")
                    .reduce("", (rows, currentRow) -> rows + "\n" + currentRow));
        }
        if (docString != null) {
            stepBuilder.append("\n\"\"\"\n")
                    .append(docString.getContent())
                    .append("\n\"\"\"");
        }

        // Adjust the minimal feature template to ensure the line numbers match
        StringBuilder minimalFeatureBuilder = new StringBuilder();
        for (int i = 1; i < lineToUse - 3; i++) {
            minimalFeatureBuilder.append("\n");
        }
        minimalFeatureBuilder.append("Feature: Minimal Feature Template\n\n  Scenario: Minimal Scenario Template\n    ");
        minimalFeatureBuilder.append(stepBuilder);

        String featureSource = minimalFeatureBuilder.toString();

        // Parse the minimal feature
        io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser parser =
                new io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser();
        io.cucumber.core.gherkin.Feature feature = parser.parse(uriToUse, featureSource, UUID::randomUUID)
                .orElseThrow(() -> new RuntimeException("Failed to parse feature"));

        // Get the first pickle and step
        io.cucumber.core.gherkin.Pickle pickle = feature.getPickles().get(0);
        io.cucumber.core.gherkin.Step step = pickle.getSteps().get(0);

        // Resolve the step definition from the glue
        io.cucumber.core.runner.CachingGlue glue = runner.getGlue();
        io.cucumber.core.runner.PickleStepDefinitionMatch definitionMatch = null;
        try {
            definitionMatch = glue.stepDefinitionMatch(uriToUse, step);
        } catch (AmbiguousStepDefinitionsException e) {
            throw new RuntimeException(e);
        }
        if (definitionMatch == null) {
            throw new RuntimeException("No step definition found for: " + stepText);
        }

        // Create the PickleStepTestStep
        return new io.cucumber.core.runner.PickleStepTestStep(
                UUID.randomUUID(),
                uriToUse,
                step,
                Collections.emptyList(), // HookTestSteps before
                Collections.emptyList(), // HookTestSteps after
                definitionMatch
        );
    }


    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep2(
            String dynamicStep,
            io.cucumber.datatable.DataTable dataTable,
            io.cucumber.docstring.DocString docString,
            URI overrideUri,
            int overrideLineNumber) {

        // Use the provided URI and line number, or defaults
        URI uriToUse = overrideUri != null ? overrideUri : URI.create("file://minimal.feature");
        int lineToUse = overrideLineNumber > 0 ? overrideLineNumber : 3;

        // Adjust the minimal feature template to ensure the line numbers match
        StringBuilder minimalFeatureBuilder = new StringBuilder();
        for (int i = 1; i < lineToUse; i++) {
            minimalFeatureBuilder.append("\n");
        }
        minimalFeatureBuilder.append("Feature: Minimal Feature Template\n\n  Scenario: Minimal Scenario Template\n    ");
        String stepText = PREFIXES.stream().anyMatch(dynamicStep.toLowerCase()::startsWith) ? dynamicStep : "* " + dynamicStep;
        minimalFeatureBuilder.append(stepText);

        String featureSource = minimalFeatureBuilder.toString();
        // Parse the minimal feature
        io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser parser =
                new io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser();
        io.cucumber.core.gherkin.Feature feature = parser.parse(uriToUse, featureSource, UUID::randomUUID)
                .orElseThrow(() -> new RuntimeException("Failed to parse feature"));

        // Get the first pickle and step
        io.cucumber.core.gherkin.Pickle pickle = feature.getPickles().get(0);
        io.cucumber.core.gherkin.Step step = pickle.getSteps().get(0);

        // Create a dummy StepDefinition
        io.cucumber.core.backend.StepDefinition dummyStepDefinition = new io.cucumber.core.backend.StepDefinition() {
            @Override
            public String getPattern() {
                return dynamicStep;
            }

            //            @Override
            public List<String> matchedArguments(String dynamicStep) {
                return Collections.emptyList();
            }

            @Override
            public void execute(Object[] args) {
            }

            @Override
            public List<io.cucumber.core.backend.ParameterInfo> parameterInfos() {
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
                        uriToUse,
                        step
                );

        if (dataTable != null)
            definitionMatch.setDefaultDataTableArg(dataTable.toDataTableArgument());

        if (docString != null)
            definitionMatch.setDefaultDocStringArg(docString.toDocStringArgument());

        // Create the PickleStepTestStep
        return new io.cucumber.core.runner.PickleStepTestStep(
                UUID.randomUUID(),
                uriToUse,
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
