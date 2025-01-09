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
 * Represents the Exception message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * A simplified representation of an exception
 */
// Generated code
@SuppressWarnings("unused")
public final class Exception {
    private final String type;
    private final String message;
    private final String stackTrace;

    public Exception(
        String type,
        String message,
        String stackTrace
    ) {
        this.type = requireNonNull(type, "Exception.type cannot be null");
        this.message = message;
        this.stackTrace = stackTrace;
    }

    /**
     * The type of the exception that caused this result. E.g. "Error" or "org.opentest4j.AssertionFailedError"
     */
    public String getType() {
        return type;
    }

    /**
      * The message of exception that caused this result. E.g. expected: "a" but was: "b"
     */
    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    /**
      * The stringified stack trace of the exception that caused this result
     */
    public Optional<String> getStackTrace() {
        return Optional.ofNullable(stackTrace);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Exception that = (Exception) o;
        return 
            type.equals(that.type) &&         
            Objects.equals(message, that.message) &&         
            Objects.equals(stackTrace, that.stackTrace);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            type,
            message,
            stackTrace
        );
    }

    @Override
    public String toString() {
        return "Exception{" +
            "type=" + type +
            ", message=" + message +
            ", stackTrace=" + stackTrace +
            '}';
    }
}
