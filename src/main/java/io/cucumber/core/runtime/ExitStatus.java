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

package io.cucumber.core.runtime;

import io.cucumber.core.plugin.Options;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.max;
import static java.util.Collections.min;
import static java.util.Comparator.comparing;

public final class ExitStatus implements ConcurrentEventListener {

    private static final byte DEFAULT = 0x0;
    private static final byte ERRORS = 0x1;

    private final List<Result> results = new ArrayList<>();
    private final Options options;

    private final EventHandler<TestCaseFinished> testCaseFinishedHandler = event -> results.add(event.getResult());

    public ExitStatus(Options options) {
        this.options = options;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseFinished.class, testCaseFinishedHandler);
    }

    byte exitStatus() {
        return isSuccess() ? DEFAULT : ERRORS;
    }

    boolean isSuccess() {
        if (results.isEmpty()) {
            return true;
        }

        if (options.isWip()) {
            Result leastSeverResult = min(results, comparing(Result::getStatus));
            return !leastSeverResult.getStatus().is(Status.PASSED);
        } else {
            Result mostSevereResult = max(results, comparing(Result::getStatus));
            return mostSevereResult.getStatus().isOk();
        }
    }

    Status getStatus() {
        if (results.isEmpty()) {
            return Status.PASSED;
        }
        Result mostSevereResult = max(results, comparing(Result::getStatus));
        return mostSevereResult.getStatus();
    }

}
