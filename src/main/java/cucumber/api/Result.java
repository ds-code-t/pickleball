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

package cucumber.api;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Comparator;
import java.util.Objects;

import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;

public final class Result {

    public final static Comparator<Result> SEVERITY = new Comparator<Result>() {

        @Override
        public int compare(Result a, Result b) {
            return a.status == b.status ? 0 : a.status.ordinal() > b.status.ordinal() ? 1 : -1;
        }
    };

    private final Type status;
    private final Long duration;
    private final Throwable error;
    public static final Result UNDEFINED = new Result(Result.Type.UNDEFINED, 0L, null);
    public enum Type {
        PASSED,
        SKIPPED,
        PENDING,
        UNDEFINED,
        AMBIGUOUS,
        FAILED;

        public static Type fromLowerCaseName(String lowerCaseName) {
            return valueOf(lowerCaseName.toUpperCase(ROOT));
        }

        public String lowerCaseName() {
            return name().toLowerCase(ROOT);
        }

        public String firstLetterCapitalizedName() {
            return name().substring(0, 1) + name().substring(1).toLowerCase(ROOT);
        }


    }

    /**
     * The result of a step or scenario
     *
     * @param status status of the step or scenario
     * @param duration the duration in nanoseconds
     * @param error the error that caused the failure if any
     */
    public Result(Result.Type status, Long duration, Throwable error) {
        this.status = requireNonNull(status);
        this.duration = requireNonNull(duration);
        this.error = error;
    }

    public Result.Type getStatus() {
        return status;
    }

    public Long getDuration() {
        return duration;
    }

    public String getErrorMessage() {
        return error != null ? getErrorMessage(error) : null;
    }

    public Throwable getError() {
        return error;
    }

    public boolean is(Result.Type status) {
        return this.status == status;
    }

    public boolean isOk(boolean isStrict) {
        return hasAlwaysOkStatus() || !isStrict && hasOkWhenNotStrictStatus();
    }

    private boolean hasAlwaysOkStatus() {
        return is(Result.Type.PASSED) || is(Result.Type.SKIPPED);
    }

    private boolean hasOkWhenNotStrictStatus() {
        return is(Result.Type.UNDEFINED) || is(Result.Type.PENDING);
    }

    private String getErrorMessage(Throwable error) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        error.printStackTrace(printWriter);
        return stringWriter.getBuffer().toString();
    }

    @Override
    public String toString() {
        return "Result{" +
                "status=" + status +
                ", duration=" + duration +
                ", error=" + error +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Result result = (Result) o;
        return status == result.status &&
                Objects.equals(duration, result.duration) &&
                Objects.equals(error, result.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, duration, error);
    }
}
