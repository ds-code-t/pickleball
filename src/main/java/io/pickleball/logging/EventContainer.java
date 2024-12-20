package io.pickleball.logging;


import io.cucumber.core.eventbus.EventBus;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.TestStepResult;
import io.cucumber.plugin.event.*;
import io.pickleball.cacheandstate.StepContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;


import static io.cucumber.core.runner.TestStepResultStatusMapper.from;
import static io.cucumber.messages.Convertor.toMessage;


public class EventContainer {

    public Event event;
    public Envelope envelope;
    public EventBus bus;

    public TestStep testStep;
    public TestCase testCase;
    public UUID textExecutionId;
    public Instant startTime;
    public StepContext stepContext;

    public Duration duration;
    public Result passedResult;
    public Instant stopTime;

    public UUID id;

    public EventContainer(TestCase testCase, EventBus bus, UUID textExecutionId, Instant startTime, UUID id, TestStep testStep) {
        this.testCase = testCase;
        this.bus = bus;
        this.textExecutionId = textExecutionId;
        this.startTime = startTime;
        this.id = id;
        this.testStep = testStep;

    }


    public EventContainer(TestCase testCase, EventBus bus, UUID textExecutionId, Instant stopTime, Duration duration, Result passedResult, UUID id, TestStep testStep) {
        this.testCase = testCase;
        this.testCase = testCase;
        this.bus = bus;
        this.textExecutionId = textExecutionId;
        this.duration = duration;
        this.passedResult = passedResult;
        this.stopTime = stopTime;
        this.id = id;
        this.testStep = testStep;
    }



    public EventContainer(Event event, Envelope envelope, EventBus bus) {
        this.event = event;
        this.envelope = envelope;
        this.bus = bus;
    }

    public void send() {
        bus.send(event);
        bus.send(envelope);
    }

    public void sendStart() {
        this.event = new TestStepStarted(startTime, testCase, testStep);


         this.envelope = Envelope.of(new io.cucumber.messages.types.TestStepStarted(
                textExecutionId.toString(),
                id.toString(),
                toMessage(startTime)));
        bus.send(event);
        bus.send(envelope);
    }

    public void sendEnd() {


        Result result = new Result(passedResult.getStatus(), duration, passedResult.getError());

        TestStepResult testStepResult = new TestStepResult(
                toMessage(duration),
                result.getError() != null ? result.getError().getMessage() : null,
                from(result.getStatus()),
                result.getError() != null ? toMessage(result.getError()) : null);

        this.event = new TestStepFinished(stopTime, testCase, testStep, result);

        this.envelope = Envelope.of(new io.cucumber.messages.types.TestStepFinished(
                textExecutionId.toString(),
                id.toString(),
                testStepResult,
                toMessage(stopTime)));
        bus.send(event);
        bus.send(envelope);

    }

}
