//package io.pickleball.cucumberutilities;
//
//import io.cucumber.core.backend.ParameterInfo;
//import io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument;
//import io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument;
//import io.cucumber.datatable.DataTable;
//import io.cucumber.docstring.DocString;
//
//import java.net.URI;
//import java.util.Collections;
//import java.util.List;
//import java.util.UUID;
//
//import static io.pickleball.configs.Constants.PREFIXES;
//
//public class CucumberObjectFactory2 {
//
//
//    ///
//
//    private static final String MINIMAL_FEATURE_TEMPLATE = """
//            Feature: Minimal Feature Template
//
//              Scenario: Minimal Scenario Template
//                {metaStep}
//            """;
//
//    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
//            String metaStep) {
//        return createPickleStepTestStep(metaStep, null, null, null, 0);
//    }
//
//    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStepWithArgs(
//            String metaStep,
//            GherkinMessagesDataTableArgument dataTableArg,
//            GherkinMessagesDocStringArgument docStringArg,
//            URI overrideUri,
//            int overrideLineNumber) {
//        DataTable dataTable = dataTableArg == null ? null : DataTableUtilities.convertToDataTable(dataTableArg);
//        DocString docString = docStringArg == null ? null : DocString.create(docStringArg.getContent());
//        return createPickleStepTestStep(metaStep, dataTable, docString, overrideUri, overrideLineNumber);
//    }
//
//    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
//            String metaStep,
//            DataTable dataTable,
//            DocString docString,
//            URI overrideUri,
//            int overrideLineNumber) {
//
//        // Use the provided URI and line number, or defaults
//        URI uriToUse = overrideUri != null ? overrideUri : URI.create("file://minimal.feature");
//        int lineToUse = overrideLineNumber > 0 ? overrideLineNumber : 3;
//
//        // Adjust the minimal feature template to ensure the line numbers match
//        StringBuilder minimalFeatureBuilder = new StringBuilder();
//        for (int i = 1; i < lineToUse; i++) {
//            minimalFeatureBuilder.append("\n");
//        }
//        minimalFeatureBuilder.append("Feature: Minimal Feature Template\n\n  Scenario: Minimal Scenario Template\n    ");
//        String stepText = PREFIXES.stream().anyMatch(metaStep.toLowerCase()::startsWith) ? metaStep : "* " + metaStep;
//        minimalFeatureBuilder.append(stepText);
//
//        String featureSource = minimalFeatureBuilder.toString();
//        System.out.println("@@featureSource: " + featureSource);
//        // Parse the minimal feature
//        io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser parser =
//                new io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser();
//        io.cucumber.core.gherkin.Feature feature = parser.parse(uriToUse, featureSource, UUID::randomUUID)
//                .orElseThrow(() -> new RuntimeException("Failed to parse feature"));
//
//        // Get the first pickle and step
//        io.cucumber.core.gherkin.Pickle pickle = feature.getPickles().get(0);
//        io.cucumber.core.gherkin.Step step = pickle.getSteps().get(0);
//
//        // Create a dummy StepDefinition
//        io.cucumber.core.backend.StepDefinition dummyStepDefinition = new io.cucumber.core.backend.StepDefinition() {
//            @Override
//            public String getPattern() {
//                return metaStep;
//            }
//
//            //            @Override
//            public List<String> matchedArguments(String metaStep) {
//                return Collections.emptyList();
//            }
//
//            @Override
//            public void execute(Object[] args) {
//            }
//
//            @Override
//            public List<ParameterInfo> parameterInfos() {
//                return List.of();
//            }
//
//            @Override
//            public boolean isDefinedAt(StackTraceElement stackTraceElement) {
//                return false;
//            }
//
//            @Override
//            public String getLocation() {
//                return "dummy location";
//            }
//        };
//
//        // Create the PickleStepDefinitionMatch
//        io.cucumber.core.runner.PickleStepDefinitionMatch definitionMatch =
//                new io.cucumber.core.runner.PickleStepDefinitionMatch(
//                        Collections.emptyList(), // No arguments
//                        dummyStepDefinition,     // Dummy step definition
//                        uriToUse,
//                        step
//                );
//
//        if (dataTable != null)
//            definitionMatch.setDefaultDataTableArg(dataTable.toDataTableArgument());
//
//        if (docString != null)
//            definitionMatch.setDefaultDocStringArg(docString.toDocStringArgument());
//
//        // Create the PickleStepTestStep
//        return new io.cucumber.core.runner.PickleStepTestStep(
//                UUID.randomUUID(),
//                uriToUse,
//                step,
//                Collections.emptyList(), // HookTestSteps before
//                Collections.emptyList(), // HookTestSteps after
//                definitionMatch
//        );
//    }
//
//
//    public static GherkinMessagesDataTableArgument createDataTableArgument(
//            String tableSource) {
//        // Convert table source into a 2D list
//        List<List<String>> rawTable = List.of(
//                List.of(tableSource.split("\\|")) // Split columns using "|"
//        );
//
//        // Create a DataTableArgument
//        return new GherkinMessagesDataTableArgument(
//                new io.cucumber.messages.types.PickleTable(
//                        rawTable.stream()
//                                .map(row -> new io.cucumber.messages.types.PickleTableRow(
//                                        row.stream()
//                                                .map(io.cucumber.messages.types.PickleTableCell::new)
//                                                .toList()))
//                                .toList()),
//                1 // Line number (mocked)
//        );
//    }
//
//    public static GherkinMessagesDocStringArgument createDocStringArgument(
//            String docStringContent) {
//        // Create a DocStringArgument
//        return new GherkinMessagesDocStringArgument(
//                new io.cucumber.messages.types.PickleDocString(
//                        null,                 // Content type
//                        docStringContent     // DocString content
//                ),
//                1 // Line number (mocked)
//        );
//    }
//}
