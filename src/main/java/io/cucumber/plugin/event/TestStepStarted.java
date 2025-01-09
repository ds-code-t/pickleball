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

package io.cucumber.plugin.event;

import org.apiguardian.api.API;

import java.time.Instant;
import java.util.Objects;

import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;

/**
 * A test step started event is broadcast when ever a step starts.
 * <p>
 * A step can either be a {@link PickleStepTestStep} or a {@link HookTestStep}
 * depending on what step was executed.
 * <p>
 * Each test step started event is followed by an matching
 * {@link TestStepFinished} event for the same step.The order in which these
 * events may be expected is:
 *
 * <pre>
 *     [before hook,]* [[before step hook,]* test step, [after step hook,]*]+, [after hook,]*
 * </pre>
 *
 * @see PickleStepTestStep
 * @see HookTestStep
 */

@API(status = API.Status.STABLE)
public final class TestStepStarted extends TestCaseEvent {

    private final TestStep testStep;

    public TestStepStarted(Instant timeInstant, TestCase testCase, TestStep testStep) {
        super(timeInstant, testCase);
        TestStep newStep = testStep instanceof io.cucumber.core.runner.TestCase ? createPickleStepTestStep("Scenario: " + ((TestCase)testStep).getName()) : testStep;
        this.testStep = Objects.requireNonNull(newStep);
    }

    public TestStep getTestStep() {
        return testStep;
    }

}
