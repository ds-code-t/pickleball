/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.core.runner;

import io.cucumber.core.backend.Pending;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.pickleball.cacheandstate.StepContext;
import io.pickleball.exceptions.SoftFailureException;
import io.pickleball.logging.EventContainer;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;

import static io.cucumber.core.exception.UnrecoverableExceptions.rethrowIfUnrecoverable;
import static io.cucumber.core.runner.ExecutionMode.*;
import static io.cucumber.core.runner.TestAbortedExceptions.createIsTestAbortedExceptionPredicate;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentStep;
import static java.time.Duration.ZERO;

public abstract class TestStep extends StepContext implements io.cucumber.plugin.event.TestStep {


    private final Predicate<Throwable> isTestAbortedException = createIsTestAbortedExceptionPredicate();
    private final StepDefinitionMatch stepDefinitionMatch;
    private final UUID id;

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


    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode startExecutionMode) {
        Instant startTime = bus.getInstant();
        newExecutionMapPut("startTime", startTime);

        boolean shouldEmit = shouldEmitEvent();

        if (shouldEmit)
            emitTestStepStarted(testCase, bus, state.getTestExecutionId(), startTime);

        Status status;
        Throwable error = null;
        try {
            runExecutionMode = startExecutionMode;
            status = executeStep(state, runExecutionMode);
        } catch (Throwable t) {
            currentExecutionMapPut("error", t);
            rethrowIfUnrecoverable(t);
            error = t;
            status = mapThrowableToStatus(t);
        }
        Instant stopTime = bus.getInstant();
        Duration duration = Duration.between(startTime, stopTime);
        Result originalResult = mapStatusToResult(status, error, duration);
        currentExecutionMapPut("stopTime", stopTime);
        currentExecutionMapPut("duration", duration);
        currentExecutionMapPut("result", originalResult);
        addStatus(originalResult.getStatus());
        Status returnStatus = getHighestStatus();

        Result returnResult = originalResult;
        if (shouldUpdateStatus())
            state.add(originalResult);
        else if (!status.equals(Status.PASSED))
            returnResult = new Result(Status.SKIPPED, duration, error);


        if (shouldEmit)
            emitTestStepFinished(testCase, bus, state.getTestExecutionId(), stopTime, duration, returnResult, error);
        else if (status.equals(Status.FAILED))
            emitTestStepFinished(testCase, bus, state.getTestExecutionId(), stopTime, duration, originalResult, error);


        return returnStatus.is(Status.PASSED) || returnStatus.is(Status.SOFT_FAILED) ? RUN : SKIP;

    }


    private void emitTestStepStarted(TestCase testCase, EventBus bus, UUID textExecutionId, Instant startTime) {
        startEvent = new EventContainer(testCase, bus, textExecutionId, startTime, id, this);
//        GherkinMessagesStep s = ((GherkinMessagesStep) ((PickleStepTestStep) this).getStep());
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
            TestCase testCase, EventBus bus, UUID textExecutionId, Instant stopTime, Duration duration, Result result, Throwable... throwables
    ) {

        endEvent = new EventContainer(testCase, bus, textExecutionId, stopTime, duration, result, id, this);
        if (result.getStatus().equals(Status.FAILED) || shouldSendEnd())
            sendEndEvent(throwables);

    }
}
