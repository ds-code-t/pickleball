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

import io.cucumber.core.gherkin.Step;

import java.util.List;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public final class AmbiguousStepDefinitionsException extends Exception {

    private final List<PickleStepDefinitionMatch> matches;

    AmbiguousStepDefinitionsException(Step step, List<PickleStepDefinitionMatch> matches) {
        super(createMessage(step, matches));
        this.matches = matches;
    }

    private static String createMessage(Step step, List<PickleStepDefinitionMatch> matches) {
        requireNonNull(step);
        requireNonNull(matches);

        return quoteText(step.getText()) + " matches more than one step definition:\n" + matches.stream()
                .map(match -> "  " + quoteText(match.getPattern()) + " in " + match.getLocation())
                .collect(joining("\n"));
    }

    private static String quoteText(String text) {
        return "\"" + text + "\"";
    }

    List<PickleStepDefinitionMatch> getMatches() {
        return matches;
    }

}
