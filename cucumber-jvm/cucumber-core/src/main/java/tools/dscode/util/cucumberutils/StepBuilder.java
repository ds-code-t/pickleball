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
import io.cucumber.plugin.event.Location;
import tools.dscode.extensions.StepExtension;

import java.net.URI;
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

    public static PickleStepTestStep createPickleStepTestStep(
            GherkinMessagesStep gherikinMessageStep, UUID uuid, URI uri
    ) {
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getDefinition(getRunner(),
            getGherkinMessagesPickle(), gherikinMessageStep);
        return new PickleStepTestStep(
            uuid,
            uri, // java.net.URI
            gherikinMessageStep, // io.cucumber.core.gherkin.Step (public)
            pickleStepDefinitionMatch // io.cucumber.core.runner.PickleStepDefinitionMatch
        );
    }

    public static PickleStepTestStep createPickleStepTestStepFromGherkinMessagesStep(
            GherkinMessagesStep gherkinMessagesStep, String stepText, PickleStepArgument pickleStepArgument
    ) {
        PickleStep pickleStep = new PickleStep(pickleStepArgument, new ArrayList<>(),
            idGenerator.newId(), PickleStepType.CONTEXT,
            stepText);
        GherkinMessagesStep gherikinMessageStep = new GherkinMessagesStep(
            pickleStep,
            getDialect(),
            getKeyword(),
            gherkinMessagesStep.getLocation(),
            getKeyword());
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getDefinition(getRunner(),
            getGherkinMessagesPickle(), gherikinMessageStep);
        return new io.cucumber.core.runner.PickleStepTestStep(
            UUID.randomUUID(), // java.util.UUID
            URI.create(""), // java.net.URI
            gherikinMessageStep, // io.cucumber.core.gherkin.Step (public)
            pickleStepDefinitionMatch);
    }

    // public static PickleStepTestStep
    // createPickleStepTestStep(PickleStepTestStep pickleStepTestStep, String
    // newText, PickleStepArgument pickleStepArgument)
    // {
    // PickleStepDefinitionMatch pickleStepDefinitionMatch =
    // getDefinition(getRunner(),
    // getGherkinMessagesPickle(), gherikinMessageStep);
    // return new PickleStepTestStep(
    // uuid,
    // uri, // java.net.URI
    // gherikinMessageStep, // io.cucumber.core.gherkin.Step (public)
    // pickleStepDefinitionMatch //
    // io.cucumber.core.runner.PickleStepDefinitionMatch
    // );
    // }

    public static PickleStep cloneStep(PickleStep pickleStep) {
        return new PickleStep(
            pickleStep.argument,
            pickleStep.astNodeIds,
            pickleStep.id,
            pickleStep.type,
            pickleStep.text);
    }

    public static PickleStepTestStep updatePickleStepTestStep(
            PickleStepTestStep pickleStepTestStep, String overRideText, PickleStepArgument pickleStepArgument
    ) {
        GherkinMessagesStep gherkinMessagesStep = (GherkinMessagesStep) pickleStepTestStep.step;
        // PickleStep pickleStep = cloneStep(gherkinMessagesStep.pickleStep);
        Location location = gherkinMessagesStep.getLocation();
        Location newLocation = new Location(location.getLine() + 2, location.getColumn());
        System.out.println("@@oldLocation: " + location.getLine());
        System.out.println("@@newLocation: " + newLocation.getLine());
        PickleStep newPickleStep = new PickleStep(pickleStepArgument, gherkinMessagesStep.pickleStep.astNodeIds,
            gherkinMessagesStep.pickleStep.id,
            gherkinMessagesStep.pickleStep.type, overRideText);
        GherkinMessagesStep newGherkinMessagesStep = new GherkinMessagesStep(newPickleStep, getDialect(),
            gherkinMessagesStep.getPreviousGivenWhenThenKeyword(), gherkinMessagesStep.getLocation(),
            gherkinMessagesStep.getKeyword());
        PickleStepDefinitionMatch pickleStepDefinitionMatch = getDefinition(getRunner(),
            getGherkinMessagesPickle(), newGherkinMessagesStep);
        return new PickleStepTestStep(pickleStepTestStep.getId(), pickleStepTestStep.getUri(), newGherkinMessagesStep,
            pickleStepDefinitionMatch);
    }

}
