package tools.dscode.util.stepbuilder;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.Runner;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleStepType;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.PickleStepTestStep;
import tools.dscode.common.util.Reflect;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static tools.dscode.common.GlobalConstants.defaultMatchFlag;
import static tools.dscode.common.SelfRegistering.localOrGlobalOf;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;
import static tools.dscode.state.ScenarioState.getScenarioState;

public class StepUtilities {


    // public static GherkinDialect dialect;
    //
    // static {
    //
    // }
    //
    // public static void main(String[] args) {
    // GherkinDialect dialect = GherkinDialects.getDialect(pickle.getLanguage())
    // .orElse(GherkinDialects.getDialect("en").get());
    // String keyword = dialect.getThenKeywords().getFirst();
    //
    // PickleStep pickleStep = createPickleStep(ROOT_STEP);
    // System.out.println("@@dialect: " + dialect);
    // System.out.println("@@pickleStep: " + pickleStep);
    // System.out.println("@@keyword: " + keyword);
    // Step gherikinMessageStep = (Step) Reflect.newInstance(
    // "io.cucumber.core.gherkin.messages.GherkinMessagesStep",
    // pickleStep,
    // dialect,
    // keyword,
    // new Location(1, 1),
    // keyword);
    // try {
    // System.out.println("@@##gherikinMessageStep: " + gherikinMessageStep);
    // PickleStepTestStep rootStep = (PickleStepTestStep) StepUtilities
    // .createPickleStepTestStep(gherikinMessageStep, UUID.randomUUID(), new
    // URI(""));
    // rootStep.run(this, bus, state, nextExecutionMode);
    //
    // } catch (URISyntaxException e) {
    // throw new RuntimeException(e);
    // }
    // }


    public static PickleStep createPickleStep(
            PickleStepArgument pickleStepArgument, List<String> astNodeIds, String id, PickleStepType pickleStepType,
            String stepText
    ) {
        return new PickleStep(pickleStepArgument, astNodeIds, id, pickleStepType, stepText);
    }

    public static PickleStep createPickleStep(String stepText) {
        return new PickleStep(null, new ArrayList<>(), "111111111", PickleStepType.CONTEXT, stepText);
    }

    public static Step createGherikinMessageStep(
            PickleStep pickleStep, String previousGivenWhenThenKeyword, Location location, String keyword
    ) {
        return (Step) Reflect.newInstance(
            "io.cucumber.core.gherkin.messages.GherkinMessagesStep",
            pickleStep,
            GherkinDialects.getDialect(getScenarioState().getPickleLanguage())
                    .orElse(GherkinDialects.getDialect("en").get()),
            previousGivenWhenThenKeyword,
            location,
            keyword);
    }

    public static PickleStepTestStep createPickleStepTestStep(Step gherikinMessageStep, UUID uuid, URI uri) {
        Object pickleStepDefinitionMatch = getDefinition(localOrGlobalOf("io.cucumber.core.runner.Runner"),
            localOrGlobalOf("io.cucumber.core.gherkin.messages.GherkinMessagesPickle"), gherikinMessageStep);
        System.out.println("@@gherikinMessageStep: " + gherikinMessageStep);
        System.out.println("@@gherikinMessageStep getText: " + gherikinMessageStep.getText());
        System.out.println("@@pickleStepDefinitionMatch: " + pickleStepDefinitionMatch);
        return (PickleStepTestStep) Reflect.newInstance(
            "io.cucumber.core.runner.PickleStepTestStep",
            uuid, // java.util.UUID
            uri, // java.net.URI
            gherikinMessageStep, // io.cucumber.core.gherkin.Step (public)
            pickleStepDefinitionMatch // io.cucumber.core.runner.PickleStepDefinitionMatch
                                      // (package-private instance is fine)
        );
    }

    public static PickleStepTestStep createPickleStepTestStep(
            PickleStepArgument pickleStepArgument, List<String> astNodeIds, String id, PickleStepType pickleStepType,
            String stepText, String previousGivenWhenThenKeyword, Location location, String keyword, UUID uuid, URI uri
    ) {
        PickleStep pickleStep = createPickleStep(pickleStepArgument, astNodeIds, id, pickleStepType, stepText);
        Step gherikinMessageStep = createGherikinMessageStep(pickleStep, previousGivenWhenThenKeyword, location,
            keyword);
        return createPickleStepTestStep(gherikinMessageStep, uuid, uri);
    }

    public static PickleStepTestStep createScenarioPickleStepTestStep(
            Pickle pickle, PickleStepTestStep pickleStepTestStep
    ) {
        try {
            String newStepText = defaultMatchFlag + pickle.getKeyword() + ":" + pickle.getName();
            Step gherikinMessageStep = (Step) pickleStepTestStep.getStep();
            PickleStepTestStep step = createPickleStepTestStep(null, new ArrayList<>(), UUID.randomUUID().toString(),
                PickleStepType.CONTEXT, newStepText, gherikinMessageStep.getPreviousGivenWhenThenKeyword(),
                pickle.getLocation(), gherikinMessageStep.getKeyword(), UUID.randomUUID(), pickle.getUri());
            return step;

        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    public static Object getDefinition(Runner runner, Pickle scenarioPickle, Step step) {
        return matchStepToStepDefinition(runner, scenarioPickle, step);
    }

    public static Object matchStepToStepDefinition(Runner runner, Pickle pickle, Step step) {
        return invokeAnyMethod(runner, "matchStepToStepDefinition", pickle, step);
    }

    public static PickleStepTestStep CreateOrphanStep(String stepText) throws URISyntaxException {
        String keyword = GherkinDialects.getDialect(getScenarioState().getPickleLanguage())
                .orElse(GherkinDialects.getDialect("en").get()).getThenKeywords().getFirst();
        return createPickleStepTestStep(
            null,
            new ArrayList<>(),
            UUID.randomUUID().toString(),
            PickleStepType.CONTEXT,
            stepText,
            keyword,
            new Location(1, 1),
            keyword, UUID.randomUUID(),
            URI.create(""));
    }

    public static PickleStepTestStep createMessageStep(String message) throws URISyntaxException {
        String keyword = GherkinDialects.getDialect(getScenarioState().getPickleLanguage())
                .orElse(GherkinDialects.getDialect("en").get()).getThenKeywords().getFirst();
        return createPickleStepTestStep(
            null,
            new ArrayList<>(),
            UUID.randomUUID().toString(),
            PickleStepType.CONTEXT,
            message,
            keyword,
            new Location(1, 1),
            keyword, UUID.randomUUID(),
            URI.create("MESSAGE:\"" + message + "\""));
    }
    //
}
