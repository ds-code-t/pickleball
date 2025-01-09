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
 * Represents the TestStepFinished message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class TestStepFinished {
    private final String testCaseStartedId;
    private final String testStepId;
    private final TestStepResult testStepResult;
    private final Timestamp timestamp;

    public TestStepFinished(
        String testCaseStartedId,
        String testStepId,
        TestStepResult testStepResult,
        Timestamp timestamp
    ) {
        this.testCaseStartedId = requireNonNull(testCaseStartedId, "TestStepFinished.testCaseStartedId cannot be null");
        this.testStepId = requireNonNull(testStepId, "TestStepFinished.testStepId cannot be null");
        this.testStepResult = requireNonNull(testStepResult, "TestStepFinished.testStepResult cannot be null");
        this.timestamp = requireNonNull(timestamp, "TestStepFinished.timestamp cannot be null");
    }

    public String getTestCaseStartedId() {
        return testCaseStartedId;
    }

    public String getTestStepId() {
        return testStepId;
    }

    public TestStepResult getTestStepResult() {
        return testStepResult;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestStepFinished that = (TestStepFinished) o;
        return 
            testCaseStartedId.equals(that.testCaseStartedId) &&         
            testStepId.equals(that.testStepId) &&         
            testStepResult.equals(that.testStepResult) &&         
            timestamp.equals(that.timestamp);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            testCaseStartedId,
            testStepId,
            testStepResult,
            timestamp
        );
    }

    @Override
    public String toString() {
        return "TestStepFinished{" +
            "testCaseStartedId=" + testCaseStartedId +
            ", testStepId=" + testStepId +
            ", testStepResult=" + testStepResult +
            ", timestamp=" + timestamp +
            '}';
    }
}
