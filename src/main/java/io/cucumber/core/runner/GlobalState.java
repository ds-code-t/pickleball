package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.plugin.event.PickleStepTestStep;

import static io.cucumber.core.runner.CurrentScenarioState.getTestCase;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.registry.GlobalRegistry.localOrGlobalOf;

public class GlobalState {

    public static io.cucumber.core.runtime.Runtime getRuntime() {
        return localOrGlobalOf(io.cucumber.core.runtime.Runtime.class);
    }

    public static io.cucumber.core.runner.Runner getRunner() {
        return localOrGlobalOf(io.cucumber.core.runner.Runner.class);
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



}
