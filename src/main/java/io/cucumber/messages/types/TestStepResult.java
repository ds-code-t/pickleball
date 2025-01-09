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
 * Represents the TestStepResult message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class TestStepResult {
    private final Duration duration;
    private final String message;
    private final TestStepResultStatus status;
    private final Exception exception;

    public TestStepResult(
        Duration duration,
        String message,
        TestStepResultStatus status,
        Exception exception
    ) {
        this.duration = requireNonNull(duration, "TestStepResult.duration cannot be null");
        this.message = message;
        this.status = requireNonNull(status, "TestStepResult.status cannot be null");
        this.exception = exception;
    }

    public Duration getDuration() {
        return duration;
    }

    /**
      * An arbitrary bit of information that explains this result. This can be a stack trace of anything else.
     */
    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    public TestStepResultStatus getStatus() {
        return status;
    }

    /**
      * Exception thrown while executing this step, if any.
     */
    public Optional<Exception> getException() {
        return Optional.ofNullable(exception);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestStepResult that = (TestStepResult) o;
        return 
            duration.equals(that.duration) &&         
            Objects.equals(message, that.message) &&         
            status.equals(that.status) &&         
            Objects.equals(exception, that.exception);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            duration,
            message,
            status,
            exception
        );
    }

    @Override
    public String toString() {
        return "TestStepResult{" +
            "duration=" + duration +
            ", message=" + message +
            ", status=" + status +
            ", exception=" + exception +
            '}';
    }
}
