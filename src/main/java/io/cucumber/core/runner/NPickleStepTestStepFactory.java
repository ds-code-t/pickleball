package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.StepExpression;
import io.cucumber.messages.types.PickleStep;
import tools.dscode.common.mappings.ParsingMap;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.createGherkinMessagesPickle;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.createGherkinMessagesStep;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.ArgStepFunctions.updatePickleStepDefinitionMatch;
import static io.cucumber.core.runner.GlobalState.getCachingGlue;
import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
import static io.cucumber.core.runner.GlobalState.getOptions;
import static io.cucumber.core.runner.GlobalState.getRunner;
import static io.cucumber.core.runner.GlobalState.getRuntime;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class NPickleStepTestStepFactory {

    public static PickleStepTestStep createPickleStepTestStep(URI uri, Step step, PickleStepDefinitionMatch pickleStepDefinitionMatch) {
        return new PickleStepTestStep(UUID.randomUUID(), uri, step, updatePickleStepDefinitionMatch(pickleStepDefinitionMatch));
    }


    public static io.cucumber.core.runner.PickleStepTestStep resolvePickleStepTestStep(PickleStepTestStep pickleStepTestStep, ParsingMap parsingMap) {
        Step gherkinMessagesStep = pickleStepTestStep.getStep();
        String resolvedStepString = parsingMap.resolveWholeText(gherkinMessagesStep.getText());
        String resolvedArgString = parsingMap.resolveWholeText(getGherkinArgumentText(gherkinMessagesStep));
        return getPickleStepTestStepFromStrings(gherkinMessagesStep.getKeyword(), resolvedStepString, resolvedArgString);
    }


    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(String keyword, String stepText, String argument) {
        System.out.println("@@getPickleStepTestStepFromStrings1: " + stepText);
//        Pickle pickle = createGherkinMessagesPickle("* " + stepText, argument);
        Pickle pickle = createGherkinMessagesPickle(keyword , stepText, argument);
        Step onlyStep =  pickle.getSteps().getFirst();
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getStepDefinitionMatch(pickle.getUri(), onlyStep);
        return createPickleStepTestStep(pickle.getUri(), onlyStep, pickleStepDefinitionMatch);
    }

    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(PickleStepTestStep modelStep, String keyword, String stepText, String argument) {
        System.out.println("@@getPickleStepTestStepFromStrings1: " + stepText);
//        Pickle pickle = createGherkinMessagesPickle("* " + stepText, argument);
        Pickle pickle = createGherkinMessagesPickle(keyword , stepText, argument);
        Step onlyStep =  pickle.getSteps().getFirst();
        PickleStep pickleStep = (PickleStep) getProperty(modelStep, "pickleStep");
        Step copiedStep = createGherkinMessagesStep(pickleStep, getGherkinDialect(),onlyStep.getPreviousGivenWhenThenKeyword(),  modelStep.getStep().getLocation(), onlyStep.getKeyword());
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getStepDefinitionMatch(modelStep.getUri(), copiedStep);
        return createPickleStepTestStep(modelStep.getUri(), copiedStep, pickleStepDefinitionMatch);
    }






    public static PickleStepDefinitionMatch getStepDefinitionMatch(URI uri, Step step) {
        System.out.println("@@getOptions().getGlue(): " + getOptions().getGlue());
        Map<String, CoreStepDefinition> stepDefinitionsByPattern = getCachingGlue().getStepDefinitionsByPattern();
        System.out.println("@@stepDefinitionsByPattern: " + stepDefinitionsByPattern.values());
        io.cucumber.core.gherkin.Argument arg = step.getArgument();
        List<PickleStepDefinitionMatch> matches = new ArrayList<>();
        System.out.println("@@MAtching step text = " + step.getText());
        for (CoreStepDefinition coreStepDefinition : stepDefinitionsByPattern.values()) {
            System.out.println("@@coreStepDefinition-coreStepDefinition.getPattern(): " + coreStepDefinition.getPattern());
            Type[] types = (Type[]) getProperty(coreStepDefinition, "types");

            StepExpression stepExpression = coreStepDefinition.getExpression();
            List<Argument> args = stepExpression.match(step.getText(), types);
            if (args != null) {
                System.out.println("arrgs match!!: " + args);
                matches.add(new PickleStepDefinitionMatch(args, coreStepDefinition, uri, step));
            }
        }
        if (matches.size() > 1)
            throw new RuntimeException("Multiple matches found for step: " + step.getText());
        if (matches.isEmpty())
            throw new RuntimeException("No matches found for step: " + step.getText());
        return matches.getFirst();
    }









}
