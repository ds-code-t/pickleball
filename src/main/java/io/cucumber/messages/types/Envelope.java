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

package io.cucumber.messages.types;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Represents the Envelope message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * When removing a field, replace it with reserved, rather than deleting the line.
 * When adding a field, add it to the end and increment the number by one.
 * See https://developers.google.com/protocol-buffers/docs/proto#updating for details
 *
 * All the messages that are passed between different components/processes are Envelope
 * messages.
 */
// Generated code
@SuppressWarnings("unused")
public final class Envelope {
    private final Attachment attachment;
    private final GherkinDocument gherkinDocument;
    private final Hook hook;
    private final Meta meta;
    private final ParameterType parameterType;
    private final ParseError parseError;
    private final Pickle pickle;
    private final Source source;
    private final StepDefinition stepDefinition;
    private final TestCase testCase;
    private final TestCaseFinished testCaseFinished;
    private final TestCaseStarted testCaseStarted;
    private final TestRunFinished testRunFinished;
    private final TestRunStarted testRunStarted;
    private final TestStepFinished testStepFinished;
    private final TestStepStarted testStepStarted;
    private final UndefinedParameterType undefinedParameterType;

    public static Envelope of(Attachment attachment) {
        return new Envelope(
            requireNonNull(attachment, "Envelope.attachment cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(GherkinDocument gherkinDocument) {
        return new Envelope(
            null,
            requireNonNull(gherkinDocument, "Envelope.gherkinDocument cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(Hook hook) {
        return new Envelope(
            null,
            null,
            requireNonNull(hook, "Envelope.hook cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(Meta meta) {
        return new Envelope(
            null,
            null,
            null,
            requireNonNull(meta, "Envelope.meta cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(ParameterType parameterType) {
        return new Envelope(
            null,
            null,
            null,
            null,
            requireNonNull(parameterType, "Envelope.parameterType cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(ParseError parseError) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            requireNonNull(parseError, "Envelope.parseError cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(Pickle pickle) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(pickle, "Envelope.pickle cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(Source source) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(source, "Envelope.source cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(StepDefinition stepDefinition) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(stepDefinition, "Envelope.stepDefinition cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(TestCase testCase) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testCase, "Envelope.testCase cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(TestCaseFinished testCaseFinished) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testCaseFinished, "Envelope.testCaseFinished cannot be null"),
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(TestCaseStarted testCaseStarted) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testCaseStarted, "Envelope.testCaseStarted cannot be null"),
            null,
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(TestRunFinished testRunFinished) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testRunFinished, "Envelope.testRunFinished cannot be null"),
            null,
            null,
            null,
            null
        );
    }

    public static Envelope of(TestRunStarted testRunStarted) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testRunStarted, "Envelope.testRunStarted cannot be null"),
            null,
            null,
            null
        );
    }

    public static Envelope of(TestStepFinished testStepFinished) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testStepFinished, "Envelope.testStepFinished cannot be null"),
            null,
            null
        );
    }

    public static Envelope of(TestStepStarted testStepStarted) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(testStepStarted, "Envelope.testStepStarted cannot be null"),
            null
        );
    }

    public static Envelope of(UndefinedParameterType undefinedParameterType) {
        return new Envelope(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requireNonNull(undefinedParameterType, "Envelope.undefinedParameterType cannot be null")
        );
    }

    public Envelope(
        Attachment attachment,
        GherkinDocument gherkinDocument,
        Hook hook,
        Meta meta,
        ParameterType parameterType,
        ParseError parseError,
        Pickle pickle,
        Source source,
        StepDefinition stepDefinition,
        TestCase testCase,
        TestCaseFinished testCaseFinished,
        TestCaseStarted testCaseStarted,
        TestRunFinished testRunFinished,
        TestRunStarted testRunStarted,
        TestStepFinished testStepFinished,
        TestStepStarted testStepStarted,
        UndefinedParameterType undefinedParameterType
    ) {
        this.attachment = attachment;
        this.gherkinDocument = gherkinDocument;
        this.hook = hook;
        this.meta = meta;
        this.parameterType = parameterType;
        this.parseError = parseError;
        this.pickle = pickle;
        this.source = source;
        this.stepDefinition = stepDefinition;
        this.testCase = testCase;
        this.testCaseFinished = testCaseFinished;
        this.testCaseStarted = testCaseStarted;
        this.testRunFinished = testRunFinished;
        this.testRunStarted = testRunStarted;
        this.testStepFinished = testStepFinished;
        this.testStepStarted = testStepStarted;
        this.undefinedParameterType = undefinedParameterType;
    }

    public Optional<Attachment> getAttachment() {
        return Optional.ofNullable(attachment);
    }

    public Optional<GherkinDocument> getGherkinDocument() {
        return Optional.ofNullable(gherkinDocument);
    }

    public Optional<Hook> getHook() {
        return Optional.ofNullable(hook);
    }

    public Optional<Meta> getMeta() {
        return Optional.ofNullable(meta);
    }

    public Optional<ParameterType> getParameterType() {
        return Optional.ofNullable(parameterType);
    }

    public Optional<ParseError> getParseError() {
        return Optional.ofNullable(parseError);
    }

    public Optional<Pickle> getPickle() {
        return Optional.ofNullable(pickle);
    }

    public Optional<Source> getSource() {
        return Optional.ofNullable(source);
    }

    public Optional<StepDefinition> getStepDefinition() {
        return Optional.ofNullable(stepDefinition);
    }

    public Optional<TestCase> getTestCase() {
        return Optional.ofNullable(testCase);
    }

    public Optional<TestCaseFinished> getTestCaseFinished() {
        return Optional.ofNullable(testCaseFinished);
    }

    public Optional<TestCaseStarted> getTestCaseStarted() {
        return Optional.ofNullable(testCaseStarted);
    }

    public Optional<TestRunFinished> getTestRunFinished() {
        return Optional.ofNullable(testRunFinished);
    }

    public Optional<TestRunStarted> getTestRunStarted() {
        return Optional.ofNullable(testRunStarted);
    }

    public Optional<TestStepFinished> getTestStepFinished() {
        return Optional.ofNullable(testStepFinished);
    }

    public Optional<TestStepStarted> getTestStepStarted() {
        return Optional.ofNullable(testStepStarted);
    }

    public Optional<UndefinedParameterType> getUndefinedParameterType() {
        return Optional.ofNullable(undefinedParameterType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Envelope that = (Envelope) o;
        return 
            Objects.equals(attachment, that.attachment) &&         
            Objects.equals(gherkinDocument, that.gherkinDocument) &&         
            Objects.equals(hook, that.hook) &&         
            Objects.equals(meta, that.meta) &&         
            Objects.equals(parameterType, that.parameterType) &&         
            Objects.equals(parseError, that.parseError) &&         
            Objects.equals(pickle, that.pickle) &&         
            Objects.equals(source, that.source) &&         
            Objects.equals(stepDefinition, that.stepDefinition) &&         
            Objects.equals(testCase, that.testCase) &&         
            Objects.equals(testCaseFinished, that.testCaseFinished) &&         
            Objects.equals(testCaseStarted, that.testCaseStarted) &&         
            Objects.equals(testRunFinished, that.testRunFinished) &&         
            Objects.equals(testRunStarted, that.testRunStarted) &&         
            Objects.equals(testStepFinished, that.testStepFinished) &&         
            Objects.equals(testStepStarted, that.testStepStarted) &&         
            Objects.equals(undefinedParameterType, that.undefinedParameterType);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            attachment,
            gherkinDocument,
            hook,
            meta,
            parameterType,
            parseError,
            pickle,
            source,
            stepDefinition,
            testCase,
            testCaseFinished,
            testCaseStarted,
            testRunFinished,
            testRunStarted,
            testStepFinished,
            testStepStarted,
            undefinedParameterType
        );
    }

    @Override
    public String toString() {
        return "Envelope{" +
            "attachment=" + attachment +
            ", gherkinDocument=" + gherkinDocument +
            ", hook=" + hook +
            ", meta=" + meta +
            ", parameterType=" + parameterType +
            ", parseError=" + parseError +
            ", pickle=" + pickle +
            ", source=" + source +
            ", stepDefinition=" + stepDefinition +
            ", testCase=" + testCase +
            ", testCaseFinished=" + testCaseFinished +
            ", testCaseStarted=" + testCaseStarted +
            ", testRunFinished=" + testRunFinished +
            ", testRunStarted=" + testRunStarted +
            ", testStepFinished=" + testStepFinished +
            ", testStepStarted=" + testStepStarted +
            ", undefinedParameterType=" + undefinedParameterType +
            '}';
    }
}
