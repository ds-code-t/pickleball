package io.cucumber.core.runner;

//import io.cucumber.core.metasteps.ScenarioSteps;

import io.cucumber.core.backend.Pending;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.TestStepResult;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.pickleball.cacheandstate.StepContext;
import io.pickleball.exceptions.SoftFailureException;
import io.pickleball.logging.EventContainer;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

import static io.cucumber.core.exception.UnrecoverableExceptions.rethrowIfUnrecoverable;
import static io.cucumber.core.runner.ExecutionMode.SKIP;
import static io.cucumber.core.runner.TestAbortedExceptions.createIsTestAbortedExceptionPredicate;
import static java.time.Duration.ZERO;

public abstract class TestStep extends StepContext implements io.cucumber.plugin.event.TestStep {


    private final Predicate<Throwable> isTestAbortedException = createIsTestAbortedExceptionPredicate();
    private final StepDefinitionMatch stepDefinitionMatch;
    private final UUID id;
    public Method method;



    TestStep(UUID id, StepDefinitionMatch stepDefinitionMatch) {
        super(id, (PickleStepDefinitionMatch) stepDefinitionMatch);
        this.id = id;
        this.stepDefinitionMatch = stepDefinitionMatch;
    }


    @Override
    public String getCodeLocation() {
        return stepDefinitionMatch.getCodeLocation();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode executionMode) {
        Instant startTime = bus.getInstant();

//        if (!stepContext.isMetaStep())
        emitTestStepStarted(testCase, bus, state.getTestExecutionId(), startTime);

        Status status;
        Throwable error = null;
        try {
            status = executeStep(state, executionMode);
        } catch (Throwable t) {
            t.printStackTrace();
            rethrowIfUnrecoverable(t);
            error = t;
            status = mapThrowableToStatus(t);
        }
        Instant stopTime = bus.getInstant();
        Duration duration = Duration.between(startTime, stopTime);
        Result result = mapStatusToResult(status, error, duration);
        state.add(result);

        emitTestStepFinished(testCase, bus, state.getTestExecutionId(), stopTime, duration, result, error);


        addStatus(result.getStatus());
        Status returnStatus = getHighestStatus();

        return returnStatus.is(Status.PASSED) || returnStatus.is(Status.SOFT_FAILED) ? executionMode : SKIP;
//
    }
//
//    public ExecutionMode runDynamically(io.cucumber.plugin.event.TestCase testCase, EventBus bus, TestCaseState currentState, ExecutionMode executionMode) {
//        return executionMode;
//    }


    private void emitTestStepStarted(TestCase testCase, EventBus bus, UUID textExecutionId, Instant startTime) {
        startEvent = new EventContainer(  testCase,  bus,  textExecutionId,  startTime,  id, this);
        if (shouldSendStart())
            sendStartEvent();
    }

    private Status executeStep(TestCaseState state, ExecutionMode executionMode) throws Throwable {
        state.setCurrentTestStepId(id);
        try {
            return executionMode.execute(stepDefinitionMatch, state);
        } finally {
            state.clearCurrentTestStepId();
        }
    }

    private Status mapThrowableToStatus(Throwable t) {
        if (t.getClass().isAnnotationPresent(Pending.class)) {
            return Status.PENDING;
        }
        if (isTestAbortedException.test(t)) {
            return Status.SKIPPED;
        }
        if (t.getClass() == UndefinedStepDefinitionException.class) {
            return Status.UNDEFINED;
        }
        if (t.getClass() == AmbiguousStepDefinitionsException.class) {
            return Status.AMBIGUOUS;
        }
        if (t.getClass() == SoftFailureException.class) {
            return Status.SOFT_FAILED;
        }
        return Status.FAILED;
    }

    private Result mapStatusToResult(Status status, Throwable error, Duration duration) {
        if (status == Status.UNDEFINED) {
            return new Result(status, ZERO, null);
        }
        return new Result(status, duration, error);
    }

    private void emitTestStepFinished(
            TestCase testCase, EventBus bus, UUID textExecutionId, Instant stopTime, Duration duration, Result result , Throwable... throwables
    ) {

        endEvent = new EventContainer(  testCase,  bus,  textExecutionId,  stopTime,  duration,  result,  id, this);
        if (result.getStatus().equals(Status.FAILED) || shouldSendEnd())
            sendEndEvent(throwables);

    }
}
