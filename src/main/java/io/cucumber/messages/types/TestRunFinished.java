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
 * Represents the TestRunFinished message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class TestRunFinished {
    private final String message;
    private final Boolean success;
    private final Timestamp timestamp;
    private final Exception exception;

    public TestRunFinished(
        String message,
        Boolean success,
        Timestamp timestamp,
        Exception exception
    ) {
        this.message = message;
        this.success = requireNonNull(success, "TestRunFinished.success cannot be null");
        this.timestamp = requireNonNull(timestamp, "TestRunFinished.timestamp cannot be null");
        this.exception = exception;
    }

    /**
      * An informative message about the test run. Typically additional information about failure, but not necessarily.
     */
    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    /**
     * A test run is successful if all steps are either passed or skipped, all before/after hooks passed and no other exceptions where thrown.
     */
    public Boolean getSuccess() {
        return success;
    }

    /**
     * Timestamp when the TestRun is finished
     */
    public Timestamp getTimestamp() {
        return timestamp;
    }

    /**
      * Any exception thrown during the test run, if any. Does not include exceptions thrown while executing steps.
     */
    public Optional<Exception> getException() {
        return Optional.ofNullable(exception);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestRunFinished that = (TestRunFinished) o;
        return 
            Objects.equals(message, that.message) &&         
            success.equals(that.success) &&         
            timestamp.equals(that.timestamp) &&         
            Objects.equals(exception, that.exception);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            message,
            success,
            timestamp,
            exception
        );
    }

    @Override
    public String toString() {
        return "TestRunFinished{" +
            "message=" + message +
            ", success=" + success +
            ", timestamp=" + timestamp +
            ", exception=" + exception +
            '}';
    }
}
