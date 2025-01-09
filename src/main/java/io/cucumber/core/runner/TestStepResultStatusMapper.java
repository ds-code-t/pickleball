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

package io.cucumber.core.runner;

import io.cucumber.messages.types.TestStepResultStatus;
import io.cucumber.plugin.event.Status;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.cucumber.messages.types.TestStepResultStatus.AMBIGUOUS;
import static io.cucumber.messages.types.TestStepResultStatus.FAILED;
import static io.cucumber.messages.types.TestStepResultStatus.PASSED;
import static io.cucumber.messages.types.TestStepResultStatus.PENDING;
import static io.cucumber.messages.types.TestStepResultStatus.SKIPPED;
import static io.cucumber.messages.types.TestStepResultStatus.UNDEFINED;
import static io.cucumber.messages.types.TestStepResultStatus.UNKNOWN;

public class TestStepResultStatusMapper {

    private static final Map<Status, TestStepResultStatus> STATUS;

    static {
        Map<Status, TestStepResultStatus> status = new HashMap<>();
        status.put(Status.FAILED, FAILED);
        status.put(Status.PASSED, PASSED);
        status.put(Status.UNDEFINED, UNDEFINED);
        status.put(Status.PENDING, PENDING);
        status.put(Status.SKIPPED, SKIPPED);
        status.put(Status.AMBIGUOUS, AMBIGUOUS);
        STATUS = Collections.unmodifiableMap(status);
    };

    private TestStepResultStatusMapper() {
    }

    public static TestStepResultStatus from(Status status) {
        return STATUS.getOrDefault(status, UNKNOWN);
    }

}
