package io.pickleball.dynamicstepinvocation;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.TestCaseState;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestStep;

public interface DynamicSteps extends TestStep {

    void addStepsToStack(PickleStepTestStep... pickleStepTestSteps);
    ExecutionMode runStackSteps(TestCase testCase, TestCaseState state, EventBus bus, ExecutionMode nextExecutionMode);
}
