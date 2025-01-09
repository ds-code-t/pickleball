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

package io.cucumber.messages;

import io.cucumber.messages.types.Duration;
import io.cucumber.messages.types.Exception;
import io.cucumber.messages.types.Timestamp;

import java.io.PrintWriter;
import java.io.StringWriter;

import static java.util.Objects.requireNonNull;

public final class Convertor {

    private Convertor(){

    }

    public static Exception toMessage(Throwable throwable) {
        requireNonNull(throwable, "throwable may not be null");
        return new Exception(throwable.getClass().getName(), throwable.getMessage(), extractStackTrace(throwable));
    }

    private static String extractStackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        try (PrintWriter printWriter = new PrintWriter(stringWriter)) {
            throwable.printStackTrace(printWriter);
        }
        return stringWriter.toString();
    }

    public static Timestamp toMessage(java.time.Instant instant) {
        requireNonNull(instant, "instant may not be null");
        return new Timestamp(instant.getEpochSecond(), (long) instant.getNano());
    }

    public static Duration toMessage(java.time.Duration duration) {
        requireNonNull(duration, "duration may not be null");
        return new Duration(duration.getSeconds(), (long) duration.getNano());
    }

    public static java.time.Instant toInstant(Timestamp timestamp) {
        requireNonNull(timestamp, "timestamp may not be null");
        return java.time.Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static java.time.Duration toDuration(Duration duration) {
        requireNonNull(duration, "duration may not be null");
        return java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos());
    }


}
