package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Pickle;
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

    private final URI uri;
    private final Step step;
    private final List<HookTestStep> afterStepHookSteps;
    private final List<HookTestStep> beforeStepHookSteps;
    private PickleStepDefinitionMatch definitionMatch;
    private  io. cucumber. core. gherkin.Pickle pickle;
    public Runner runner;
//    public PickleStepTestStep createNewMappedStep(){
//        return null;
//    }



//    public final StepContext stepContext;

    PickleStepTestStep(UUID id, URI uri, Step step, PickleStepDefinitionMatch definitionMatch) {
        this(id, uri, step, Collections.emptyList(), Collections.emptyList(), definitionMatch);
    }

    public PickleStepTestStep(
            UUID id, URI uri,
            Step step,
            List<HookTestStep> beforeStepHookSteps,
            List<HookTestStep> afterStepHookSteps,
            Pickle pickle,
            Runner runner
    ) {
        super(id, null);
        this.uri = uri;
        this.step = step;
        this.afterStepHookSteps = afterStepHookSteps;
        this.beforeStepHookSteps = beforeStepHookSteps;
        this.pickle = pickle;
        this.runner = runner;

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
    }

//    public PickleStepTestStep generateNewStep(){
//
//        PickleStepDefinitionMatch match = runner.matchStepToStepDefinition(pickle, step);
//    return new PickleStepTestStep(runner.bus.generateId(), pickle.getUri(), step, beforeStepHookSteps,
//    afterStepHookSteps, match);
//    }

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


    public Pickle getPickle() {
        return pickle;
    }

    public Runner getRunner() {
        return runner;
    }


}
