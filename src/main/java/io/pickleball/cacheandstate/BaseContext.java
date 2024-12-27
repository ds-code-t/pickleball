package io.pickleball.cacheandstate;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;

import java.util.List;
import java.util.Stack;
import java.util.UUID;


public class BaseContext  implements io.cucumber.plugin.event.TestStep{

    protected int nestingLevel = 0;
    protected int position = 0;
    protected TestCase parent = null;

    private final Stack<PickleStepTestStep> stepStack = new Stack<>();

    public void addStepsToStack(PickleStepTestStep... pickleStepTestSteps) {
        stepStack.addAll(List.of(pickleStepTestSteps));
    }

    public ExecutionMode runStackSteps(io.cucumber.plugin.event.TestCase testCase, TestCaseState state, EventBus bus, ExecutionMode nextExecutionMode) {
        while (nextExecutionMode.equals(ExecutionMode.RUN) && !stepStack.empty()) {
            PickleStepTestStep stackStep = stepStack.pop();
            nextExecutionMode = stackStep
                    .run(testCase, bus, state, nextExecutionMode)
                    .next(nextExecutionMode);
        }
        return nextExecutionMode;
    }


    @Override
    public String getCodeLocation() {
        return "";
    }

    @Override
    public UUID getId() {
        return null;
    }
}
