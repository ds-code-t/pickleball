package io.pickleball.cacheandstate;

import io.cucumber.core.backend.Status;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;

import java.util.*;

import static io.pickleball.cacheandstate.BaseContext.RunCondition.DEFAULT;
import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;
import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;


public class BaseContext implements io.cucumber.plugin.event.TestStep {
    private final TreeMap<Integer, RunCondition> statusFlagTree = new TreeMap<>();
    public List<String> unevaluatedPredicates = new ArrayList<>();


    protected BaseContext templateContext;
    protected TestCase parent = null;
    protected int nestingLevel = 0;
    protected int position = 0;

    private List<BaseContext> clonedContexts = new ArrayList<>();

    public void addCloned(BaseContext clone) {
        clone.templateContext = this;
        clonedContexts.add(clone);
        clone.parent = parent;
        clone.nestingLevel = nestingLevel;
        clone.position = position;
    }




//    public void setParent(TestCase parent) {
//        this.parent = parent;
//    }
//
//    public void setPosition(int position) {
//        this.position = position;
//    }
//
//    public void setNestingLevel(int nestingLevel) {
//        this.nestingLevel = nestingLevel;
//    }
//
//
//    public int getNestingLevel() {
//        if(templateContext == null)
//            return nestingLevel;
//        return templateContext.getNestingLevel();
//    }
//
//    public int getPosition() {
//        if(templateContext == null)
//            return position;
//        return templateContext.getPosition();
//    }
//
//    public TestCase getParent() {
//        if(templateContext == null)
//            return parent;
//        return templateContext.getParent();
//    }


//    public void setTemplateContext(BaseContext templateContext) {
//        this.templateContext = templateContext;
//    }
//
//
//    public BaseContext getTemplateContext() {
//        return templateContext;
//    }


    private RunCondition runCondition;


    public RunCondition getRunCondition() {
        return runCondition;
    }

    public void setRunCondition(RunCondition runCondition) {
        this.runCondition = runCondition;
    }





    public void addStatusFlag(RunCondition statusFlag) {
        statusFlagTree.put(position, statusFlag);
    }

    public RunCondition getLastFlag() {
        if(runCondition != null)
            return runCondition;
        var entry = statusFlagTree.floorEntry(position);
        if (entry == null)
            return DEFAULT;
        return entry.getValue();
    }

    public enum RunCondition {
        RUN_ON_HARD_FAIL,
        RUN_ON_SOFT_FAIL,
        RUN_ON_FAIL,
        RUN_ON_PASS,
        RUN_ON_END,
        ALWAYS_RUN,
        DEFAULT,
    }


    public boolean shouldRun() {
        System.out.println("@@shouldRun ");
        Status[] statuses = parent.getTestCaseState().getCompletionStatus();
        RunCondition flag = getLastFlag();
        Status completionStatus = statuses[0];
        Status status = statuses[1];
        System.out.println("@@flag: " + flag);
        System.out.println("@@statuses: " + Arrays.toString(statuses));
        switch (flag) {
            case DEFAULT -> {
                return completionStatus.equals(Status.RUNNING);
            }
            case ALWAYS_RUN -> {
                return true;
            }
            case RUN_ON_PASS -> {
                System.out.println("@@status??: " + status);
                return status.equals(Status.PASSED);
            }
            case RUN_ON_FAIL -> {
                return status.toString().contains("FAIL");
            }
            case RUN_ON_HARD_FAIL -> {
                return status.equals(Status.FAILED);
            }
            case RUN_ON_SOFT_FAIL -> {
                return status.equals(Status.SOFT_FAILED);
            }
        }
        return false;
    }

    private boolean evaluatePredicates() {
        if (unevaluatedPredicates.isEmpty())
            return true;
        return unevaluatedPredicates.stream().anyMatch(p -> resolveObjectToBoolean(replaceNestedBrackets(p, parent.getPassedMap(), parent.getExamplesMap(), parent.getStateMap())));
    }

    private final Stack<PickleStepTestStep> postStepsStack = new Stack<>();
    private final Stack<PickleStepTestStep> preStepsStack = new Stack<>();

    public void addPostStackSteps(PickleStepTestStep... pickleStepTestSteps) {
        postStepsStack.addAll(List.of(pickleStepTestSteps));
    }

    public void addPreStackSteps(PickleStepTestStep... pickleStepTestSteps) {
        preStepsStack.addAll(List.of(pickleStepTestSteps));
    }


    public ExecutionMode runPostStepsStack(io.cucumber.plugin.event.TestCase testCase, TestCaseState state, EventBus bus, ExecutionMode nextExecutionMode) {
        return runStepsStack(postStepsStack, testCase, state, bus, nextExecutionMode);
    }

    public ExecutionMode runPreStepsStack(io.cucumber.plugin.event.TestCase testCase, TestCaseState state, EventBus bus, ExecutionMode nextExecutionMode) {
        return runStepsStack(preStepsStack, testCase, state, bus, nextExecutionMode);
    }


    public ExecutionMode runStepsStack(Stack<PickleStepTestStep> stepsStack, io.cucumber.plugin.event.TestCase testCase, TestCaseState state, EventBus bus, ExecutionMode nextExecutionMode) {
        while (nextExecutionMode.equals(ExecutionMode.RUN) && !stepsStack.empty()) {
            PickleStepTestStep stackStep = stepsStack.pop();
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
