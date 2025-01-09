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
import io.cucumber.messages.types.Timestamp;

@Deprecated
@SuppressWarnings("unused")
public final class TimeConversion {

    private TimeConversion(){

    }

    public static Timestamp javaInstantToTimestamp(java.time.Instant instant) {
        return Convertor.toMessage(instant);
    }

    public static Duration javaDurationToDuration(java.time.Duration duration) {
        return Convertor.toMessage(duration);
    }

    public static java.time.Instant timestampToJavaInstant(Timestamp timestamp) {
        return Convertor.toInstant(timestamp);
    }

    public static java.time.Duration durationToJavaDuration(Duration duration) {
        return Convertor.toDuration(duration);
    }
}
