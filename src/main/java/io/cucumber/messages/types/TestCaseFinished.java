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
 * Represents the TestCaseFinished message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class TestCaseFinished {
    private final String testCaseStartedId;
    private final Timestamp timestamp;
    private final Boolean willBeRetried;

    public TestCaseFinished(
        String testCaseStartedId,
        Timestamp timestamp,
        Boolean willBeRetried
    ) {
        this.testCaseStartedId = requireNonNull(testCaseStartedId, "TestCaseFinished.testCaseStartedId cannot be null");
        this.timestamp = requireNonNull(timestamp, "TestCaseFinished.timestamp cannot be null");
        this.willBeRetried = requireNonNull(willBeRetried, "TestCaseFinished.willBeRetried cannot be null");
    }

    public String getTestCaseStartedId() {
        return testCaseStartedId;
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public Boolean getWillBeRetried() {
        return willBeRetried;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestCaseFinished that = (TestCaseFinished) o;
        return 
            testCaseStartedId.equals(that.testCaseStartedId) &&         
            timestamp.equals(that.timestamp) &&         
            willBeRetried.equals(that.willBeRetried);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            testCaseStartedId,
            timestamp,
            willBeRetried
        );
    }

    @Override
    public String toString() {
        return "TestCaseFinished{" +
            "testCaseStartedId=" + testCaseStartedId +
            ", timestamp=" + timestamp +
            ", willBeRetried=" + willBeRetried +
            '}';
    }
}
