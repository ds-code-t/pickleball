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

package io.cucumber.core.plugin;

import io.cucumber.plugin.ColorAware;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestStepFinished;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public final class ProgressFormatter implements ConcurrentEventListener, ColorAware {

    private static final Map<Status, Character> CHARS = new HashMap<Status, Character>() {
        {
            put(Status.PASSED, '.');
            put(Status.UNDEFINED, 'U');
            put(Status.PENDING, 'P');
            put(Status.SKIPPED, '-');
            put(Status.FAILED, 'F');
            put(Status.AMBIGUOUS, 'A');
        }
    };
    private static final Map<Status, AnsiEscapes> ANSI_ESCAPES = new HashMap<Status, AnsiEscapes>() {
        {
            put(Status.PASSED, AnsiEscapes.GREEN);
            put(Status.UNDEFINED, AnsiEscapes.YELLOW);
            put(Status.PENDING, AnsiEscapes.YELLOW);
            put(Status.SKIPPED, AnsiEscapes.CYAN);
            put(Status.FAILED, AnsiEscapes.RED);
            put(Status.AMBIGUOUS, AnsiEscapes.RED);
        }
    };

    private final UTF8PrintWriter out;
    private boolean monochrome = false;

    public ProgressFormatter(OutputStream out) {
        this.out = new UTF8PrintWriter(out);
    }

    @Override
    public void setMonochrome(boolean monochrome) {
        this.monochrome = monochrome;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestStepFinished.class, this::handleTestStepFinished);
        publisher.registerHandlerFor(TestRunFinished.class, this::handleTestRunFinished);
    }

    private void handleTestStepFinished(TestStepFinished event) {
        boolean isTestStep = event.getTestStep() instanceof PickleStepTestStep;
        boolean isFailedHookOrTestStep = event.getResult().getStatus().is(Status.FAILED);
        if (!(isTestStep || isFailedHookOrTestStep)) {
            return;
        }
        // Prevent tearing in output when multiple threads write to System.out
        StringBuilder buffer = new StringBuilder();
        if (!monochrome) {
            ANSI_ESCAPES.get(event.getResult().getStatus()).appendTo(buffer);
        }
        buffer.append(CHARS.get(event.getResult().getStatus()));
        if (!monochrome) {
            AnsiEscapes.RESET.appendTo(buffer);
        }
        out.append(buffer);
        out.flush();
    }

    private void handleTestRunFinished(TestRunFinished testRunFinished) {
        out.println();
        out.close();
    }

}
