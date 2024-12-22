package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Step;
import io.cucumber.plugin.event.Argument;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestCase;
import io.pickleball.cacheandstate.StepContext;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.UUID;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getRunner;
import static io.pickleball.cacheandstate.ScenarioContext.popCurrentStep;
import static io.pickleball.cacheandstate.ScenarioContext.setCurrentStep;

public final class PickleStepTestStep extends TestStep  implements io.cucumber.plugin.event.PickleStepTestStep {

    public final URI uri;
    public final Step step;
    public final List<HookTestStep> afterStepHookSteps;
    public final List<HookTestStep> beforeStepHookSteps;
    public final PickleStepDefinitionMatch definitionMatch;


//    public final StepContext stepContext;

    PickleStepTestStep(UUID id, URI uri, Step step, PickleStepDefinitionMatch definitionMatch) {
        this(id, uri, step, Collections.emptyList(), Collections.emptyList(), definitionMatch);
    }

    public PickleStepTestStep(
            UUID id, URI uri,
            Step step,
            List<HookTestStep> beforeStepHookSteps,
            List<HookTestStep> afterStepHookSteps,
            PickleStepDefinitionMatch definitionMatch
    ) {
        super(id, definitionMatch);
        this.uri = uri;
        this.step = step;
        this.afterStepHookSteps = afterStepHookSteps;
        this.beforeStepHookSteps = beforeStepHookSteps;
        this.definitionMatch = definitionMatch;
//        this.stepContext = new StepContext(
//                this,               // the current test step instance
//                step,               // the Gherkin step
//                definitionMatch
//        );
    }

    @Override
    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode executionMode) {
        ExecutionMode nextExecutionMode = executionMode;

        setCurrentStep(this);
        for (HookTestStep before : beforeStepHookSteps) {
            nextExecutionMode = before
                    .run(testCase, bus, state, executionMode)
                    .next(nextExecutionMode);
        }
        nextExecutionMode = super.run(testCase, bus, state, nextExecutionMode)
                .next(nextExecutionMode);
        for (HookTestStep after : afterStepHookSteps) {
            nextExecutionMode = after
                    .run(testCase, bus, state, executionMode)
                    .next(nextExecutionMode);
        }
        popCurrentStep();


        nextExecutionMode = runStackSteps(testCase, state, bus, nextExecutionMode);

        return nextExecutionMode;
    }

    List<HookTestStep> getBeforeStepHookSteps() {
        return beforeStepHookSteps;
    }

    List<HookTestStep> getAfterStepHookSteps() {
        return afterStepHookSteps;
    }

    @Override
    public String getPattern() {
        return definitionMatch.getPattern();
    }

    @Override
    public Step getStep() {
        return step;
    }

    @Override
    public List<Argument> getDefinitionArgument() {
        return DefinitionArgument.createArguments(definitionMatch.getArguments());
    }

    public PickleStepDefinitionMatch getDefinitionMatch() {
        return definitionMatch;
    }

    @Override
    public StepArgument getStepArgument() {
        return step.getArgument();
    }

    @Override
    public int getStepLine() {
        return step.getLine();
    }

    @Override
    public URI getUri() {
        return uri;
    }

    @Override
    public String getStepText() {
        return step.getText();
    }


//    private final Stack<PickleStepTestStep> stepStack = new Stack<>();
//
//    @Override
//    public void addStepsToStack(PickleStepTestStep... pickleStepTestSteps) {
//        stepStack.addAll(List.of(pickleStepTestSteps));
//    }
//
//    @Override
//    public ExecutionMode runStackSteps(TestCase testCase, TestCaseState state, EventBus bus, ExecutionMode nextExecutionMode) {
//        while (nextExecutionMode.equals(ExecutionMode.RUN) && !stepStack.empty()) {
//            PickleStepTestStep stackStep = stepStack.pop();
//            nextExecutionMode = stackStep
//                    .run(testCase, bus, state, nextExecutionMode)
//                    .next(nextExecutionMode);
//        }
//        return nextExecutionMode;
//    }
}
