package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import tools.dscode.common.exceptions.StepCreationException;
import tools.dscode.common.mappings.ParsingMap;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.cucumber.core.gherkin.messages.CucumberDeepCloneUtil.deepCloneGherkinMessagesStep;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.argumentToGherkinText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.createGherkinMessagesPickle;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.createGherkinMessagesStep;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.ArgStepFunctions.updatePickleStepDefinitionMatch;
import static io.cucumber.core.runner.CurrentScenarioState.getGlue;
import static io.cucumber.core.runner.GlobalState.getGherkinDialect;
import static io.cucumber.core.runner.GlobalState.getGlobalCachingGlue;
import static io.cucumber.core.runner.modularexecutions.FilePathResolver.toAbsoluteFileUri;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class NPickleStepTestStepFactory {

    public static List<PickleStepTestStep> createPickleStepTestStepsFromPickle(Pickle pickle) {
        return pickle.getSteps().stream().map(step -> {
                    Step cloneStep = deepCloneGherkinMessagesStep(step);
                    return createPickleStepTestStep(pickle.getUri(), cloneStep, getStepDefinitionMatch(pickle.getUri(), cloneStep));
                }
        ).toList();
    }

    public static PickleStepTestStep createPickleStepTestStep(URI uri, Step step, PickleStepDefinitionMatch pickleStepDefinitionMatch) {
        if (pickleStepDefinitionMatch == null)
            throw new StepCreationException("Failed to find PickleStepDefinitionMatch for step text: '" + step.getText() + "'.  Ensure that a matching step definition is on the glue path.");
        pickleStepDefinitionMatch = updatePickleStepDefinitionMatch(pickleStepDefinitionMatch);
        try {
            return new PickleStepTestStep(UUID.randomUUID(), toAbsoluteFileUri(uri), step, pickleStepDefinitionMatch);
        } catch (Exception e) {
            throw new StepCreationException("Failed step text: '" + step.getText() + "'.  Ensure that a matching step definition is on the glue path. " + e.getMessage(), e);
        }
    }


    public static io.cucumber.core.runner.PickleStepTestStep resolvePickleStepTestStep(PickleStepTestStep pickleStepTestStep, ParsingMap parsingMap) {
        Step gherkinMessagesStep = pickleStepTestStep.getStep();
        String resolvedStepString = parsingMap.resolveWholeText(pickleStepTestStep.getStepText());
        String resolvedArgString = parsingMap.resolveWholeText(getGherkinArgumentText(gherkinMessagesStep));
        PickleStepTestStep returnStep = getPickleStepTestStepFromStrings(pickleStepTestStep, gherkinMessagesStep.getKeyword(), resolvedStepString, resolvedArgString);
        returnStep.unresolvedText = pickleStepTestStep.unresolvedText == null ? pickleStepTestStep.getStepText() : pickleStepTestStep.unresolvedText;
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
        if (pickleStepDefinitionMatch == null)
            throw new StepCreationException("Failed step text: '" + stepText + "'.  Ensure that a matching step definition is on the glue path.");
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
        try {
            return attemptStepDefinitionMatch(uri, step);
        } catch (AmbiguousStepDefinitionsException e) {
            throw new RuntimeException("Failed to find Step Definition match for step '" + step.getText() + "'", e);
        }
    }

    private static PickleStepDefinitionMatch attemptStepDefinitionMatch(URI uri, Step step)
            throws AmbiguousStepDefinitionsException {

        final CachingGlue glue = getGlue();
        final CachingGlue globalGlue = getGlobalCachingGlue();
        final String text = step.getText();

        final boolean preferGlobal = glue == null || text.startsWith(SCENARIO_STEP) || text.equals(ROOT_STEP);

        final CachingGlue first = preferGlobal ? globalGlue : glue;
        final CachingGlue second = preferGlobal ? glue : globalGlue;

        final PickleStepDefinitionMatch match;
        synchronized (first) {
            match = first.stepDefinitionMatch(uri, step);
        }
        if (match != null) {
            return match;
        }
        synchronized (second) {
            return second.stepDefinitionMatch(uri, step);
        }
    }

}
