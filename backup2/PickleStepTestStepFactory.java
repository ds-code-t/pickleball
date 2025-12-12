//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.core.stepexpression.Argument;
//import io.cucumber.core.stepexpression.StepExpression;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepType;
//import tools.dscode.common.mappings.ParsingMap;
//
//import java.lang.reflect.Type;
//import java.net.URI;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.UUID;
//
//import static io.cucumber.core.gherkin.messages.SingleStepPickleFactory.createGherkinMessagesPickle;
//import static io.cucumber.core.gherkin.messages.SingleStepPickleFactory.getGherkinArgumentText;
//import static io.cucumber.core.gherkin.messages.SingleStepPickleFactory.getGherkinStepText;
//import static io.cucumber.core.runner.GlobalState.getCachingGlue;
//import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
//import static io.cucumber.core.runner.GlobalState.getRunner;
//import static io.cucumber.core.runner.StepBuilder.buildGherkinMessagesStepAsStep;
//import static tools.dscode.common.util.Reflect.getProperty;
//import static tools.dscode.common.util.Reflect.invokeAnyMethod;
//import static tools.dscode.pickleruntime.CucumberOptionResolver.getGlobalContext;
//
//
//public class PickleStepTestStepFactory {
//
//
//    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(String stepText) {
//        return getPickleStepTestStepFromStrings(stepText, null);
//    }
//
//    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(String stepText, String argument) {

////        Pickle pickle = createGherkinMessagesPickle("* " + stepText, argument);
//        Pickle pickle = createGherkinMessagesPickle("* " + stepText, argument);
//        io.cucumber.messages.types.Pickle gPickle = (io.cucumber.messages.types.Pickle) getProperty(pickle, "pickle");



//        List<PickleStepTestStep> testSteps = (List<PickleStepTestStep>) invokeAnyMethod(getRunner(), "createTestStepsForPickleSteps", pickle);
//        return testSteps.getFirst();
//    }
//
////        TestCase testCase = getGlobalContext().createTestCase(pickle);

////        return (PickleStepTestStep) testCase.getTestSteps().getFirst();
////    }
//
//
//    public static io.cucumber.core.runner.PickleStepTestStep resolvePickleStepTestStep(PickleStepTestStep pickleStepTestStep, ParsingMap parsingMap) {
//        Step gherkinMessagesStep = pickleStepTestStep.getStep();
//        String resolvedStepString = parsingMap.resolveWholeText(getGherkinStepText(gherkinMessagesStep));
//        String resolvedArgString = parsingMap.resolveWholeText(getGherkinArgumentText(gherkinMessagesStep));
//        return getPickleStepTestStepFromStrings(resolvedStepString, resolvedArgString);
//    }
//
//    public static PickleStepDefinitionMatch getStepDefinitionMatch(URI uri, Step step) {
//        List<PickleStepDefinitionMatch> result = new ArrayList<>();
//        Map<String, CoreStepDefinition> stepDefinitionsByPattern = getCachingGlue().getStepDefinitionsByPattern();

//        io.cucumber.core.gherkin.Argument arg = step.getArgument();
//        List<PickleStepDefinitionMatch> matches = new ArrayList<>();
//        for (CoreStepDefinition coreStepDefinition : stepDefinitionsByPattern.values()) {





//            Type[] types = (Type[]) getProperty(coreStepDefinition, "types");

//            StepExpression stepExpression = coreStepDefinition.getExpression();
//            List<Argument> args = stepExpression.match(step.getText(), types);
//            if (args != null)
//                matches.add(new PickleStepDefinitionMatch(args, coreStepDefinition, uri, step));
//        }
//        if (matches.size() > 1)
//            throw new RuntimeException("Multiple matches found for step: " + step.getText());
//        if (matches.isEmpty())
//            throw new RuntimeException("No matches found for step: " + step.getText());
//        return matches.getFirst();
//    }
//
//
//    public static PickleStepTestStep cloneAndResolvePickleStepTestStep(PickleStepTestStep originalPickleStepTestStep, ParsingMap parsingMap) {

//        System.out.println(getCachingGlue());
//        System.out.println(getCachingGlue());

//        System.out.println(originalPickleStepTestStep.getStepText());
//        System.out.println(originalPickleStepTestStep.getStepText());

//
//
//        System.out.println(originalPickleStepTestStep.getDefinitionMatch());
//        System.out.println(originalPickleStepTestStep.getDefinitionArgument());
//        System.out.println(originalPickleStepTestStep.getCodeLocation());
//        System.out.println(originalPickleStepTestStep.getUri());
//        System.out.println("--------\n");
//
//        PickleStepTestStep resolvedPickleStepTestStep = resolvePickleStepTestStep(originalPickleStepTestStep, parsingMap);



//
//        PickleStep originalPickleStep = originalPickleStepTestStep.getPickleStep();
//        PickleStep resolvedPickleStep = resolvedPickleStepTestStep.getPickleStep();
//        PickleStep newPickleStep = new PickleStep(resolvedPickleStep.getArgument().orElse(null), originalPickleStep.getAstNodeIds(), originalPickleStep.getId(), originalPickleStep.getType().orElse(PickleStepType.CONTEXT), resolvedPickleStep.getText());
//        Step newGherkinMessagesStep = buildGherkinMessagesStepAsStep(newPickleStep, originalPickleStepTestStep.getStep().getLocation(), originalPickleStepTestStep.getStep().getKeyword(), originalPickleStepTestStep.getStep().getPreviousGivenWhenThenKeyword(), getGherkinDialect());
//        PickleStepTestStep returnPickleStepTestStep = new PickleStepTestStep(UUID.randomUUID(), originalPickleStepTestStep.getUri(), newGherkinMessagesStep, getStepDefinitionMatch(resolvedPickleStepTestStep.getUri(), newGherkinMessagesStep));

//        System.out.println(returnPickleStepTestStep.getStepText());

//
//        System.out.println(returnPickleStepTestStep.getDefinitionMatch());
//        System.out.println(returnPickleStepTestStep.getDefinitionArgument());
//        System.out.println(returnPickleStepTestStep.getCodeLocation());
//        System.out.println(returnPickleStepTestStep.getUri());
//        System.out.println("-----------\n\n");
//        return returnPickleStepTestStep;
//    }
//
//
//}
//
//
