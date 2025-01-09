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
import io.cucumber.plugin.event.SnippetsSuggestedEvent;
import io.cucumber.plugin.event.TestRunFinished;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DefaultSummaryPrinter implements ColorAware, ConcurrentEventListener {

    private final Set<String> snippets = new LinkedHashSet<>();
    private final Stats stats;
    private final PrintStream out;

    public DefaultSummaryPrinter() {
        this(System.out, Locale.getDefault());
    }

    DefaultSummaryPrinter(OutputStream out, Locale locale) {
        this.out = new PrintStream(out);
        this.stats = new Stats(locale);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        stats.setEventPublisher(publisher);
        publisher.registerHandlerFor(SnippetsSuggestedEvent.class, this::handleSnippetsSuggestedEvent);
        publisher.registerHandlerFor(TestRunFinished.class, event -> print());
    }

    private void handleSnippetsSuggestedEvent(SnippetsSuggestedEvent event) {
        this.snippets.addAll(event.getSuggestion().getSnippets());
    }

    private void print() {
        out.println();
        printStats();
        printErrors();
        printSnippets();
        out.println();
    }

    private void printStats() {
        stats.printStats(out);
        out.println();
    }

    private void printErrors() {
        List<Throwable> errors = stats.getErrors();
        if (errors.isEmpty()) {
            return;
        }
        out.println();
        for (Throwable error : errors) {
            error.printStackTrace(out);
            out.println();
        }
    }

    private void printSnippets() {
        if (snippets.isEmpty()) {
            return;
        }

        out.println();
        out.println("You can implement missing steps with the snippets below:");
        out.println();
        for (String snippet : snippets) {
            out.println(snippet);
            out.println();
        }
    }

    @Override
    public void setMonochrome(boolean monochrome) {
        stats.setMonochrome(monochrome);
    }

}
