package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleStepType;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.PickleStepTestStep;
import tools.dscode.common.util.Reflect;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.cucumber.core.runner.CurrentScenarioState.getGherkinDialect;
import static io.cucumber.core.runner.CurrentScenarioState.getGherkinMessagesPickle;
import static io.cucumber.core.runner.GlobalState.getEventBus;
import static io.cucumber.core.runner.GlobalState.getPickleFromPickleTestStep;
import static io.cucumber.core.runner.GlobalState.getRunner;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class StepBuilder {

    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(PickleStepTestStep pickleStepTestStep, String stepText, PickleStepArgument pickleStepArgument) {
        Step gherikinMessageStep = (Step) pickleStepTestStep.getStep();
        PickleStep pickleStep = (PickleStep) getProperty(gherikinMessageStep, "pickleStep");
        io.cucumber.core.gherkin.Pickle pickle = getPickleFromPickleTestStep(pickleStepTestStep);
        PickleStep newPickleStep = createPickleStep(pickleStepArgument, pickleStep.getAstNodeIds(), UUID.randomUUID().toString(), pickleStep.getType().get(), stepText);
        Step newGherikinMessageStep = createGherikinMessageStep(newPickleStep, (String) getProperty(gherikinMessageStep, "previousGwtKeyWord"), gherikinMessageStep.getLocation(), gherikinMessageStep.getKeyword());
        PickleStepDefinitionMatch newPickleStepDefinitionMatch = createPickleStepDefinitionMatch(pickle, newGherikinMessageStep);
        return createPickleStepTestStep(getEventBus().generateId(), pickle.getUri(), newGherikinMessageStep, newPickleStepDefinitionMatch);
    }

    public static io.cucumber.core.runner.PickleStepTestStep createPickleStepTestStep(UUID id, URI uri, Step step, PickleStepDefinitionMatch definitionMatch) {
        return new io.cucumber.core.runner.PickleStepTestStep(id, uri, step, definitionMatch);
    }


    public static PickleStep createPickleStep(
            PickleStepArgument pickleStepArgument, List<String> astNodeIds, String id, PickleStepType pickleStepType,
            String stepText
    ) {
        return new PickleStep(pickleStepArgument, astNodeIds, id, pickleStepType, stepText);
    }

    public static Step createGherikinMessageStep(
            PickleStep pickleStep, String previousGivenWhenThenKeyword, Location location, String keyword
    ) {
        return (Step) Reflect.newInstance(
                "io.cucumber.core.gherkin.messages.GherkinMessagesStep",
                pickleStep,
                getGherkinDialect(),
                previousGivenWhenThenKeyword,
                location,
                keyword);
    }

    public static PickleStepDefinitionMatch createPickleStepDefinitionMatch(Pickle gherkinMessagesPickle, Step gherikinMessageStep) {
        return (PickleStepDefinitionMatch) invokeAnyMethod(getRunner(), "matchStepToStepDefinition", gherkinMessagesPickle, gherikinMessageStep);
    }


}
