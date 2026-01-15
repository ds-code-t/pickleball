package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import tools.dscode.common.mappings.ParsingMap;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.argumentToGherkinText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.createGherkinMessagesPickle;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.createGherkinMessagesStep;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.ArgStepFunctions.updatePickleStepDefinitionMatch;
import static io.cucumber.core.runner.GlobalState.getCachingGlue;
import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
import static io.cucumber.core.runner.modularexecutions.FilePathResolver.toAbsoluteFileUri;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class NPickleStepTestStepFactory {

    public static List<PickleStepTestStep> createPickleStepTestStepsFromPickle(Pickle pickle) {
        return pickle.getSteps().stream().map(step ->
                createPickleStepTestStep(pickle.getUri(), step, getStepDefinitionMatch(pickle.getUri(), step))
        ).toList();
    }

    public static PickleStepTestStep createPickleStepTestStep(URI uri, Step step, PickleStepDefinitionMatch pickleStepDefinitionMatch) {
        try {
            return new PickleStepTestStep(UUID.randomUUID(), toAbsoluteFileUri(uri), step, updatePickleStepDefinitionMatch(pickleStepDefinitionMatch));
                    }
        catch (Throwable t) {
            throw new RuntimeException("Failed step text: " + step.getText(), t);
        }
    }


    public static io.cucumber.core.runner.PickleStepTestStep resolvePickleStepTestStep(PickleStepTestStep pickleStepTestStep, ParsingMap parsingMap) {
        Step gherkinMessagesStep = pickleStepTestStep.getStep();



        String resolvedStepString = parsingMap.resolveWholeText(pickleStepTestStep.getStepText());
        String resolvedArgString = parsingMap.resolveWholeText(getGherkinArgumentText(gherkinMessagesStep));
        PickleStepTestStep returnStep = getPickleStepTestStepFromStrings(pickleStepTestStep, gherkinMessagesStep.getKeyword(), resolvedStepString, resolvedArgString);
        returnStep.unresolvedText = pickleStepTestStep.unresolvedText == null ? pickleStepTestStep.getStepText(): pickleStepTestStep.unresolvedText;



        return returnStep;
    }

    public static io.cucumber.core.runner.PickleStepTestStep resolvePickleStepTestStep(PickleStepTestStep pickleStepTestStep, ParsingMap parsingMap, String newText) {
        Step gherkinMessagesStep = pickleStepTestStep.getStep();
        String resolvedStepString = parsingMap.resolveWholeText(newText);
        String resolvedArgString = parsingMap.resolveWholeText(getGherkinArgumentText(gherkinMessagesStep));
        return getPickleStepTestStepFromStrings(pickleStepTestStep, gherkinMessagesStep.getKeyword(), resolvedStepString, resolvedArgString);
    }

    public static io.cucumber.core.runner.PickleStepTestStep resolvePickleStepTestStep(PickleStepTestStep pickleStepTestStep, ParsingMap parsingMap, String newText, PickleStepArgument newPickleStepArgument) {
        Step gherkinMessagesStep = pickleStepTestStep.getStep();
        String resolvedStepString = parsingMap.resolveWholeText(newText);
        String resolvedArgString = parsingMap.resolveWholeText(argumentToGherkinText(newPickleStepArgument));
        return getPickleStepTestStepFromStrings(pickleStepTestStep, gherkinMessagesStep.getKeyword(), resolvedStepString, resolvedArgString);
    }


    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(String keyword, String stepText, String argument) {

        Pickle pickle = createGherkinMessagesPickle(keyword, stepText, argument);
        Step onlyStep = pickle.getSteps().getFirst();
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getStepDefinitionMatch(pickle.getUri(), onlyStep);
        return createPickleStepTestStep(pickle.getUri(), onlyStep, pickleStepDefinitionMatch);
    }

    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(Pickle modelPickle, String keyword, String stepText, String argument) {

        Pickle pickle = createGherkinMessagesPickle(keyword, stepText, argument);
        Step onlyStep = pickle.getSteps().getFirst();
        PickleStep pickleStep = (PickleStep) getProperty(onlyStep, "pickleStep");
        Step copiedStep = createGherkinMessagesStep(pickleStep, getGherkinDialect(), onlyStep.getPreviousGivenWhenThenKeyword(), modelPickle.getLocation(), onlyStep.getKeyword());
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getStepDefinitionMatch(modelPickle.getUri(), copiedStep);
        return createPickleStepTestStep(modelPickle.getUri(), copiedStep, pickleStepDefinitionMatch);
    }


    public static io.cucumber.core.runner.PickleStepTestStep getPickleStepTestStepFromStrings(PickleStepTestStep modelStep, String keyword, String stepText, String argument) {

        Pickle pickle = createGherkinMessagesPickle(keyword, stepText, argument);

        Step onlyStep = pickle.getSteps().getFirst();


        PickleStep pickleStep = (PickleStep) getProperty(onlyStep, "pickleStep");

        Step copiedStep = createGherkinMessagesStep(pickleStep, getGherkinDialect(), onlyStep.getPreviousGivenWhenThenKeyword(), modelStep.getStep().getLocation(), onlyStep.getKeyword());


        PickleStepDefinitionMatch pickleStepDefinitionMatch = getStepDefinitionMatch(modelStep.getUri(), copiedStep);
        return createPickleStepTestStep(modelStep.getUri(), copiedStep, pickleStepDefinitionMatch);
    }

    public static PickleStepDefinitionMatch getStepDefinitionMatch(URI uri, Step step) {
        return (PickleStepDefinitionMatch) invokeAnyMethod(getCachingGlue(), "stepDefinitionMatch", uri, step);
    }

}
