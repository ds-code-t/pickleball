//package io.pickleball.cucumberutilities;
//
//import java.net.URI;
//import java.util.Collections;
//import java.util.List;
//import java.util.Locale;
//import java.util.UUID;
//
//public class CucumberObjectFactory2 {
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesFeature createGherkinMessagesFeature(
//            io.cucumber.messages.types.Feature feature,
//            URI uri,
//            String gherkinSource,
//            List<io.cucumber.core.gherkin.Pickle> pickles) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesFeature(feature, uri, gherkinSource, pickles, Collections.emptyList());
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesPickle createGherkinMessagesPickle(
//            io.cucumber.messages.types.Pickle pickle,
//            URI uri,
//            io.cucumber.gherkin.GherkinDialect dialect,
//            io.cucumber.core.gherkin.messages.CucumberQuery cucumberQuery) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesPickle(pickle, uri, dialect, cucumberQuery);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesScenario createGherkinMessagesScenario(
//            io.cucumber.core.gherkin.messages.GherkinMessagesFeature parent,
//            io.cucumber.messages.types.Scenario scenario) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesScenario(parent, scenario);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesScenarioOutline createGherkinMessagesScenarioOutline(
//            io.cucumber.core.gherkin.messages.GherkinMessagesFeature parent,
//            io.cucumber.messages.types.Scenario scenarioOutline) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesScenarioOutline(parent, scenarioOutline);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesStep createGherkinMessagesStep(
//            io.cucumber.messages.types.PickleStep step,
//            io.cucumber.gherkin.GherkinDialect dialect,
//            String previousKeyword,
//            io.cucumber.plugin.event.Location location,
//            String keyword) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesStep(step, dialect, previousKeyword, location, keyword);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument createGherkinMessagesDataTableArgument(
//            io.cucumber.messages.types.PickleTable table,
//            int line) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument(table, line);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument createGherkinMessagesDocStringArgument(
//            io.cucumber.messages.types.PickleDocString docString,
//            int line) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument(docString, line);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesExample createGherkinMessagesExample(
//            io.cucumber.plugin.event.Node examples,
//            io.cucumber.messages.types.TableRow tableRow,
//            int examplesIndex,
//            int rowIndex) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesExample(examples, tableRow, examplesIndex, rowIndex);
//    }
//
//    public static io.cucumber.core.gherkin.messages.GherkinMessagesExamples createGherkinMessagesExamples(
//            io.cucumber.plugin.event.Node parent,
//            io.cucumber.messages.types.Examples examples,
//            int examplesIndex) {
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesExamples(parent, examples, examplesIndex);
//    }
//
//    public static io.cucumber.core.runner.TestCase createTestCase(
//            UUID id,
//            List<io.cucumber.core.runner.PickleStepTestStep> testSteps,
//            List<io.cucumber.core.runner.HookTestStep> beforeHooks,
//            List<io.cucumber.core.runner.HookTestStep> afterHooks,
//            io.cucumber.core.gherkin.Pickle pickle,
//            boolean dryRun) {
//        return new io.cucumber.core.runner.TestCase(id, testSteps, beforeHooks, afterHooks, pickle, dryRun);
//    }
//
//    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(
//            UUID id,
//            URI uri,
//            io.cucumber.core.gherkin.Step step,
//            io.cucumber.core.runner.PickleStepDefinitionMatch definitionMatch) {
//        return new io.cucumber.core.runner.PickleStepTestStep(id, uri, step, Collections.emptyList(), Collections.emptyList(), definitionMatch);
//    }
//
//    public static io.cucumber.core.runner.PickleStepDefinitionMatch createPickleStepDefinitionMatch(
//            List<io.cucumber.core.stepexpression.Argument> arguments,
//            io.cucumber.core.backend.StepDefinition stepDefinition,
//            URI uri,
//            io.cucumber.core.gherkin.Step step) {
//        return new io.cucumber.core.runner.PickleStepDefinitionMatch(arguments, stepDefinition, uri, step);
//    }
//
//    public static io.cucumber.core.gherkin.DataTableArgument createDataTableArgument(List<List<String>> rawTable) {
//        io.cucumber.messages.types.PickleTable table = new io.cucumber.messages.types.PickleTable(Collections.emptyList());
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument(table, 1);
//    }
//
//    public static io.cucumber.core.gherkin.DocStringArgument createDocStringArgument(String mediaType, String content, int line) {
//        io.cucumber.messages.types.PickleDocString docString = new io.cucumber.messages.types.PickleDocString(mediaType, content);
//        return new io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument(docString, line);
//    }
//
//    public static io.cucumber.core.stepexpression.StepExpression createStepExpression(
//            io.cucumber.core.backend.StepDefinition stepDefinition) {
//        io.cucumber.core.stepexpression.StepExpressionFactory factory =
//                new io.cucumber.core.stepexpression.StepExpressionFactory(new io.cucumber.core.stepexpression.StepTypeRegistry(Locale.ENGLISH), null);
//        return factory.createExpression(stepDefinition);
//    }
//}
