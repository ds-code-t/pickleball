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

import io.cucumber.core.backend.Status;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.messages.types.Attachment;
import io.cucumber.messages.types.AttachmentContentEncoding;
import io.cucumber.messages.types.Envelope;
import io.cucumber.plugin.event.EmbedEvent;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.WriteEvent;

import java.net.URI;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.max;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

public class TestCaseState implements io.cucumber.core.backend.TestCaseState {

    private final List<Result> stepResults = new ArrayList<>();
    private final EventBus bus;
    private final TestCase testCase;
    private final UUID testExecutionId;

    private UUID currentTestStepId;

    TestCaseState(EventBus bus, UUID testExecutionId, TestCase testCase) {
        this.bus = requireNonNull(bus);
        this.testExecutionId = requireNonNull(testExecutionId);
        this.testCase = requireNonNull(testCase);

        if (this.testCase instanceof io.cucumber.core.runner.TestCase) {
            ((io.cucumber.core.runner.TestCase) this.testCase).setTestCaseState(this);
        }
    }

    public void add(Result result) {
        stepResults.add(result);
    }

    UUID getTestExecutionId() {
        return testExecutionId;
    }

    @Override
    public Collection<String> getSourceTagNames() {
        return testCase.getTags();
    }

    @Override
    public Status getStatus() {
        if (stepResults.isEmpty()) {
            return Status.PASSED;
        }

        Result mostSevereResult = max(stepResults, comparing(Result::getStatus));
        return Status.valueOf(mostSevereResult.getStatus().name());
    }

    public boolean anyFailures() {
        return getStatus() == Status.FAILED || getStatus() == Status.SOFT_FAILED;
    }

    public boolean anySoftFailures() {
        return  getStatus() == Status.SOFT_FAILED;
    }

    public boolean anyHardFailures() {
        return  getStatus() == Status.FAILED;
    }


    @Override
    public boolean isFailed() {
        return getStatus() == Status.FAILED;
    }


    @Override
    public void attach(byte[] data, String mediaType, String name) {
        requireNonNull(data);
        requireNonNull(mediaType);

        requireActiveTestStep();
        bus.send(new EmbedEvent(bus.getInstant(), testCase, data, mediaType, name));
        bus.send(Envelope.of(new Attachment(
                Base64.getEncoder().encodeToString(data),
                AttachmentContentEncoding.BASE64,
                name,
                mediaType,
                null,
                testExecutionId.toString(),
                currentTestStepId.toString(),
                null)));
    }

    @Override
    public void attach(String data, String mediaType, String name) {
        requireNonNull(data);
        requireNonNull(mediaType);

        requireActiveTestStep();
        bus.send(new EmbedEvent(bus.getInstant(), testCase, data.getBytes(UTF_8), mediaType, name));
        bus.send(Envelope.of(new Attachment(
                data,
                AttachmentContentEncoding.IDENTITY,
                name,
                mediaType,
                null,
                testExecutionId.toString(),
                currentTestStepId.toString(),
                null)));
    }

    @Override
    public void log(String text) {
        requireActiveTestStep();
        bus.send(new WriteEvent(bus.getInstant(), testCase, text));
        bus.send(Envelope.of(new Attachment(
                text,
                AttachmentContentEncoding.IDENTITY,
                null,
                "text/x.cucumber.log+plain",
                null,
                testExecutionId.toString(),
                currentTestStepId.toString(),
                null)));
    }

    @Override
    public String getName() {
        return testCase.getName();
    }

    @Override
    public String getId() {
        return testCase.getId().toString();
    }

    @Override
    public URI getUri() {
        return testCase.getUri();
    }

    @Override
    public Integer getLine() {
        return testCase.getLocation().getLine();
    }

    Throwable getError() {
        if (stepResults.isEmpty()) {
            return null;
        }

        return max(stepResults, comparing(Result::getStatus)).getError();
    }

    void setCurrentTestStepId(UUID currentTestStepId) {
        this.currentTestStepId = currentTestStepId;
    }

    void clearCurrentTestStepId() {
        this.currentTestStepId = null;
    }

    private void requireActiveTestStep() {
        if (currentTestStepId == null) {
            throw new IllegalStateException(
                    "You can not use Scenario.log or Scenario.attach when a step is not being executed");
        }
    }

}
