package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.plugin.event.PickleStepTestStep;

import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.localOrGlobalOf;

public class GlobalState {

    public static io.cucumber.core.runtime.Runtime getRuntime() {
        return localOrGlobalOf(io.cucumber.core.runtime.Runtime.class);
    }

    public static io.cucumber.core.runner.Runner getRunner() {
        return localOrGlobalOf(io.cucumber.core.runner.Runner.class);
    }

    public static CachingGlue getCachingGlue() {
        return (CachingGlue) getProperty(getRunner(), "glue");
    }

    public static  Options getOptions() {
        return (Options) getProperty(getRunner(), "runnerOptions");
    }

    public static EventBus getEventBus() {
        return (EventBus) getProperty(getRunner(), "bus");
    }

    public static  io.cucumber.core.gherkin.Pickle getPickleFromPickleTestStep(PickleStepTestStep pickleStepTestStep) {
        return (io.cucumber.core.gherkin.Pickle) getProperty(pickleStepTestStep, "pickle");
    }

    public static io.cucumber.core.gherkin.Pickle getGherkinMessagesPickle() {
        return (io.cucumber.core.gherkin.Pickle) getProperty(getTestCase(), "pickle");
    }

    public static String language = "en";

    public static String getLanguage() {
        return language;
    }

    public static GherkinDialect getGherkinDialect() {
        return GherkinDialects.getDialect(language).orElse(GherkinDialects.getDialect("en").get());
    }

    public static String getGivenKeyword() {
        return getGherkinDialect().getGivenKeywords().getFirst();
    }

    public static io.cucumber.core.runner.TestCase getTestCase() {
        return localOrGlobalOf(io.cucumber.core.runner.TestCase.class);
    }

    public static io.cucumber.core.runner.TestCaseState getTestCaseState() {
        return localOrGlobalOf(io.cucumber.core.runner.TestCaseState.class);
    }

    public static io.cucumber.core.runner.CurrentScenarioState getCurrentScenarioState() {
        return (CurrentScenarioState) getProperty(localOrGlobalOf(TestCase.class), "currentScenarioState");
    }

    public static StepExtension getRunningStep() {
        return getCurrentScenarioState().getCurrentStep();
    }
}
