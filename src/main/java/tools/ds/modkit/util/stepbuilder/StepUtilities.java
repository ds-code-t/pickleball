package tools.ds.modkit.util.stepbuilder;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.Runner;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleStepType;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.PickleStepTestStep;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.util.Reflect;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static tools.ds.modkit.coredefinitions.MetaSteps.defaultMatchFlag;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.Reflect.invokeAnyMethod;

public class StepUtilities {


    public static PickleStep createPickleStep(PickleStepArgument pickleStepArgument, java.util.List<String> astNodeIds, String id, PickleStepType pickleStepType, String stepText) {
        return new PickleStep(pickleStepArgument, astNodeIds, id, pickleStepType, stepText);
    }

    public static PickleStep createPickleStep(String stepText) {
        return new PickleStep(null, new ArrayList<>(), "111111111", PickleStepType.CONTEXT, stepText);
    }

    public static io.cucumber.core.gherkin.Step createGherikinMessageStep(PickleStep pickleStep, String previousGivenWhenThenKeyword, io.cucumber.plugin.event.Location location, String keyword) {
        return (io.cucumber.core.gherkin.Step) Reflect.newInstance(
                "io.cucumber.core.gherkin.messages.GherkinMessagesStep",
                pickleStep,
                GherkinDialects.getDialect(getScenarioState().getPickleLanguage()).orElse(GherkinDialects.getDialect("en").get()),
                previousGivenWhenThenKeyword,
                location,
                keyword
        );
    }


    public static PickleStepTestStep createPickleStepTestStep(io.cucumber.core.gherkin.Step gherikinMessageStep, java.util.UUID uuid, java.net.URI uri) {
        Object pickleStepDefinitionMatch = getDefinition(getScenarioState().getRunner(), getScenarioState().getScenarioPickle(), gherikinMessageStep);
        return (PickleStepTestStep) Reflect.newInstance(
                "io.cucumber.core.runner.PickleStepTestStep",
                uuid,             // java.util.UUID
                uri,                // java.net.URI
                gherikinMessageStep,        // io.cucumber.core.gherkin.Step (public)
                pickleStepDefinitionMatch            // io.cucumber.core.runner.PickleStepDefinitionMatch (package-private instance is fine)
        );
    }

    public static PickleStepTestStep createPickleStepTestStep(PickleStepArgument pickleStepArgument, java.util.List<String> astNodeIds, String id, PickleStepType pickleStepType, String stepText, String previousGivenWhenThenKeyword, io.cucumber.plugin.event.Location location, String keyword, java.util.UUID uuid, java.net.URI uri) {
        PickleStep pickleStep = createPickleStep(pickleStepArgument, astNodeIds, id, pickleStepType, stepText);
        Step gherikinMessageStep = createGherikinMessageStep(pickleStep, previousGivenWhenThenKeyword, location, keyword);
        return createPickleStepTestStep(gherikinMessageStep, uuid, uri);
    }


    public static PickleStepTestStep createScenarioPickleStepTestStep(io.cucumber.core.gherkin.Pickle pickle, PickleStepTestStep pickleStepTestStep) {
        try {
            String newStepText = defaultMatchFlag + pickle.getKeyword() + ":" + pickle.getName();
            io.cucumber.core.gherkin.Step gherikinMessageStep = (Step) pickleStepTestStep.getStep();
            PickleStepTestStep step = createPickleStepTestStep(null, new ArrayList<>(), UUID.randomUUID().toString(), PickleStepType.CONTEXT, newStepText, gherikinMessageStep.getPreviousGivenWhenThenKeyword(), pickle.getLocation(), gherikinMessageStep.getKeyword(), UUID.randomUUID(), pickle.getUri());
            return step;

        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }


    public static Object getDefinition(Runner runner, io.cucumber.core.gherkin.Pickle scenarioPickle, io.cucumber.core.gherkin.Step step) {
        return matchStepToStepDefinition(runner, scenarioPickle, step);
    }

    public static Object matchStepToStepDefinition(Runner runner, Pickle pickle, io.cucumber.core.gherkin.Step step) {
        return invokeAnyMethod(runner, "matchStepToStepDefinition", pickle, step);
    }


    public static PickleStepTestStep CreateOrphanStep(String stepText) throws URISyntaxException {
            String keyword = GherkinDialects.getDialect(getScenarioState().getPickleLanguage()).orElse(GherkinDialects.getDialect("en").get()).getThenKeywords().getFirst();
            return createPickleStepTestStep(
                    null,
                    new ArrayList<>(),
                    UUID.randomUUID().toString(),
                    PickleStepType.CONTEXT,
                    stepText,
                    keyword,
                    new Location(1, 1),
                    keyword, UUID.randomUUID(),
                    URI.create("")
            );
    }

    public static PickleStepTestStep createMessageStep(String message) throws URISyntaxException {
        String keyword = GherkinDialects.getDialect(getScenarioState().getPickleLanguage()).orElse(GherkinDialects.getDialect("en").get()).getThenKeywords().getFirst();
        return createPickleStepTestStep(
                null,
                new ArrayList<>(),
                UUID.randomUUID().toString(),
                PickleStepType.CONTEXT,
                message,
                keyword,
                new Location(1, 1),
                keyword, UUID.randomUUID(),
                URI.create("MESSAGE:\"" + message +"\"")
        );
    }
//
}
