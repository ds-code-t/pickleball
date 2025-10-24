package tools.dscode.util.cucumberutils;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.Runner;
import io.cucumber.messages.IdGenerator;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleStepType;
import tools.dscode.extensions.StepExtension;

import java.util.ArrayList;
import java.util.UUID;

import static tools.dscode.common.util.Reflect.invokeAnyMethod;
import static tools.dscode.state.ScenarioState.getDialect;
import static tools.dscode.state.ScenarioState.getGherkinMessagesPickle;
import static tools.dscode.state.ScenarioState.getKeyword;
import static tools.dscode.state.ScenarioState.getRunner;

public class StepBuilder {

    public static IdGenerator idGenerator = () -> UUID.randomUUID().toString();

    public static StepExtension createStepExtension(PickleStepTestStep pickleStepTestStep, String stepText) {
        return createStepExtension(pickleStepTestStep, stepText, null);
    }

    public static StepExtension createStepExtension(
            PickleStepTestStep pickleStepTestStep, String stepText, PickleStepArgument pickleStepArgument
    ) {
        return new StepExtension(createPickleStepTestStep(pickleStepTestStep, stepText, pickleStepArgument));
    }

    public static PickleStepTestStep createPickleStepTestStep(
            PickleStepTestStep pickleStepTestStep, String stepText, PickleStepArgument pickleStepArgument
    ) {
        PickleStep pickleStep = new PickleStep(pickleStepArgument, new ArrayList<>(),
            idGenerator.newId(), PickleStepType.CONTEXT,
            stepText);
        GherkinMessagesStep gherikinMessageStep = new GherkinMessagesStep(
            pickleStep,
            getDialect(),
            getKeyword(),
            pickleStepTestStep.getStep().getLocation(),
            getKeyword());
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getDefinition(getRunner(),
            getGherkinMessagesPickle(), gherikinMessageStep);
        return new io.cucumber.core.runner.PickleStepTestStep(
            UUID.randomUUID(), // java.util.UUID
            pickleStepTestStep.getUri(), // java.net.URI
            gherikinMessageStep, // io.cucumber.core.gherkin.Step (public)
            pickleStepDefinitionMatch);
    }

    public static PickleStepDefinitionMatch getDefinition(Runner runner, Pickle scenarioPickle, Step step) {
        return matchStepToStepDefinition(runner, scenarioPickle, step);
    }

    public static PickleStepDefinitionMatch matchStepToStepDefinition(Runner runner, Pickle pickle, Step step) {
        return (PickleStepDefinitionMatch) invokeAnyMethod(runner, "matchStepToStepDefinition", pickle, step);
    }

    public static PickleStepTestStep createMessageStep(PickleStepTestStep step, String message) {
        return createPickleStepTestStep(
            step,
            "MESSAGE:\"" + message + "\"",
            null);
    }

}
