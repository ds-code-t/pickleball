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

import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestRunFinished;

import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static io.cucumber.core.feature.FeatureWithLines.create;
import static io.cucumber.core.plugin.PrettyFormatter.relativize;

/**
 * Formatter for reporting all failed test cases and print their locations
 * Failed means: results that make the exit code non-zero.
 */
public final class RerunFormatter implements ConcurrentEventListener {

    private final UTF8PrintWriter out;
    private final Map<URI, Collection<Integer>> featureAndFailedLinesMapping = new HashMap<>();

    public RerunFormatter(OutputStream out) {
        this.out = new UTF8PrintWriter(out);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseFinished.class, this::handleTestCaseFinished);
        publisher.registerHandlerFor(TestRunFinished.class, event -> finishReport());
    }

    private void handleTestCaseFinished(TestCaseFinished event) {
        if (!event.getResult().getStatus().isOk()) {
            recordTestFailed(event.getTestCase());
        }
    }

    private void finishReport() {
        for (Map.Entry<URI, Collection<Integer>> entry : featureAndFailedLinesMapping.entrySet()) {
            FeatureWithLines featureWithLines = create(relativize(entry.getKey()), entry.getValue());
            out.println(featureWithLines.toString());
        }

        out.close();
    }

    private void recordTestFailed(TestCase testCase) {
        URI uri = testCase.getUri();
        Collection<Integer> failedTestCaseLines = getFailedTestCaseLines(uri);
        failedTestCaseLines.add(testCase.getLocation().getLine());
    }

    private Collection<Integer> getFailedTestCaseLines(URI uri) {
        return featureAndFailedLinesMapping.computeIfAbsent(uri, k -> new ArrayList<>());
    }

}
