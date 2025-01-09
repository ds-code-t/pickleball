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
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.StepDefinedEvent;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestStepFinished;

import java.io.OutputStream;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.cucumber.core.plugin.Formats.ansi;
import static io.cucumber.core.plugin.Formats.monochrome;
import static java.util.Locale.ROOT;

public final class UnusedStepsSummaryPrinter implements ColorAware, ConcurrentEventListener {

    private final Map<String, String> registeredSteps = new TreeMap<>();
    private final Set<String> usedSteps = new TreeSet<>();
    private final UTF8PrintWriter out;
    private Formats formats = ansi();

    public UnusedStepsSummaryPrinter(OutputStream out) {
        this.out = new UTF8PrintWriter(out);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        // Record any steps registered
        publisher.registerHandlerFor(StepDefinedEvent.class, this::handleStepDefinedEvent);
        // Remove any steps that run
        publisher.registerHandlerFor(TestStepFinished.class, this::handleTestStepFinished);
        // Print summary when done
        publisher.registerHandlerFor(TestRunFinished.class, event -> finishReport());
    }

    private void handleStepDefinedEvent(StepDefinedEvent event) {
        registeredSteps.put(event.getStepDefinition().getLocation(), event.getStepDefinition().getPattern());
    }

    private void handleTestStepFinished(TestStepFinished event) {
        String codeLocation = event.getTestStep().getCodeLocation();
        if (codeLocation != null) {
            usedSteps.add(codeLocation);
        }
    }

    private void finishReport() {
        // Remove all used steps
        usedSteps.forEach(registeredSteps::remove);

        if (registeredSteps.isEmpty()) {
            return;
        }

        Format format = formats.get(Status.UNUSED.name().toLowerCase(ROOT));
        out.println(format.text(registeredSteps.size() + " Unused steps:"));

        // Output results when done
        for (Entry<String, String> entry : registeredSteps.entrySet()) {
            String location = entry.getKey();
            String pattern = entry.getValue();
            out.println(format.text(location) + " # " + pattern);
        }

        out.close();
    }

    @Override
    public void setMonochrome(boolean monochrome) {
        formats = monochrome ? monochrome() : ansi();
    }

}
