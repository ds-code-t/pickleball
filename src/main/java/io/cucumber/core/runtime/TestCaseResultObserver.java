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

import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.SnippetsSuggestedEvent;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.cucumber.plugin.event.Status.PASSED;
import static io.cucumber.plugin.event.Status.PENDING;
import static io.cucumber.plugin.event.Status.SKIPPED;
import static io.cucumber.plugin.event.Status.UNDEFINED;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

public final class TestCaseResultObserver implements AutoCloseable {

    private final EventPublisher bus;
    private final List<Suggestion> suggestions = new ArrayList<>();
    private final EventHandler<SnippetsSuggestedEvent> snippetsSuggested = this::handleSnippetSuggestedEvent;
    private Result result;
    private final EventHandler<TestCaseFinished> testCaseFinished = this::handleTestCaseFinished;

    public TestCaseResultObserver(EventPublisher bus) {
        this.bus = bus;
        bus.registerHandlerFor(SnippetsSuggestedEvent.class, snippetsSuggested);
        bus.registerHandlerFor(TestCaseFinished.class, testCaseFinished);
    }

    @Override
    public void close() {
        bus.removeHandlerFor(SnippetsSuggestedEvent.class, snippetsSuggested);
        bus.removeHandlerFor(TestCaseFinished.class, testCaseFinished);
    }

    private void handleSnippetSuggestedEvent(SnippetsSuggestedEvent event) {
        SnippetsSuggestedEvent.Suggestion s = event.getSuggestion();
        suggestions.add(new Suggestion(s.getStep(), s.getSnippets()));
    }

    private void handleTestCaseFinished(TestCaseFinished event) {
        result = event.getResult();
    }

    public void assertTestCasePassed(
            Supplier<Throwable> testCaseSkipped,
            Function<Throwable, Throwable> testCaseSkippedWithException,
            Function<List<Suggestion>, Throwable> testCaseWasUndefined,
            Function<Throwable, Throwable> testCaseWasPending
    ) {
        Status status = result.getStatus();
        if (status.is(PASSED)) {
            return;
        }
        Throwable error = result.getError();
        if (status.is(SKIPPED) && error == null) {
            Throwable throwable = testCaseSkipped.get();
            throw new TestCaseFailed(throwable);
        } else if (status.is(SKIPPED) && error != null) {
            Throwable throwable = testCaseSkippedWithException.apply(error);
            throw new TestCaseFailed(throwable);
        } else if (status.is(UNDEFINED)) {
            Throwable throwable = testCaseWasUndefined.apply(suggestions);
            throw new TestCaseFailed(throwable);
        } else if (status.is(PENDING)) {
            Throwable throwable = testCaseWasPending.apply(error);
            throw new TestCaseFailed(throwable);
        }
        requireNonNull(error, "result.error=null while result.status=" + result.getStatus());
        throw new TestCaseFailed(error);
    }

    public static final class Suggestion {

        final String step;
        final List<String> snippets;

        public Suggestion(String step, List<String> snippets) {
            this.step = requireNonNull(step);
            this.snippets = unmodifiableList(requireNonNull(snippets));
        }

        public String getStep() {
            return step;
        }

        public List<String> getSnippets() {
            return snippets;
        }

    }

}
