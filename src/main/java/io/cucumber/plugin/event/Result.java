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

import java.time.Duration;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * The result of a step, scenario or test run.
 */
@API(status = API.Status.STABLE)
public final class Result {

    private final Status status;
    private final Duration duration;
    private final Throwable error;

    /**
     * Creates a new result.
     *
     * @param status   status of the step or scenario
     * @param duration the duration
     * @param error    the error that caused the failure if any
     */
    public Result(Status status, Duration duration, Throwable error) {
        this.status = requireNonNull(status);
        this.duration = requireNonNull(duration);
        this.error = error;
    }

    public Status getStatus() {
        return status;
    }

    public Duration getDuration() {
        return duration;
    }

    /**
     * Returns the error encountered while executing a step, scenario or test
     * run.
     * <p>
     * Will return null when passed. May return null when status is undefined or
     * when skipped due to a failing prior step.
     *
     * @return the error encountered while executing a step or scenario or null.
     */
    public Throwable getError() {
        return error;
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, duration, error);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Result result = (Result) o;
        return status == result.status &&
                Objects.equals(duration, result.duration) &&
                Objects.equals(error, result.error);
    }

    @Override
    public String toString() {
        return "Result{" +
                "status=" + status +
                ", duration=" + duration.getSeconds() +
                ", error=" + error +
                '}';
    }

}
