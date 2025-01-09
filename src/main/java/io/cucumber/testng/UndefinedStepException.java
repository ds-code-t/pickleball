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

package io.cucumber.testng;

import io.cucumber.core.runtime.TestCaseResultObserver.Suggestion;
import org.testng.SkipException;

import java.util.Collection;
import java.util.stream.Collectors;

final class UndefinedStepException extends SkipException {

    private static final long serialVersionUID = 1L;

    UndefinedStepException(Collection<Suggestion> suggestions) {
        super(createMessage(suggestions));
    }

    private static String createMessage(Collection<Suggestion> suggestions) {
        if (suggestions.isEmpty()) {
            return "This step is undefined";
        }
        Suggestion first = suggestions.iterator().next();
        StringBuilder sb = new StringBuilder("The step '" + first.getStep() + "'");
        if (suggestions.size() == 1) {
            sb.append(" is undefined.");
        } else {
            sb.append(" and ").append(suggestions.size() - 1).append(" other step(s) are undefined.");
        }
        sb.append("\n");
        if (suggestions.size() == 1) {
            sb.append("You can implement this step using the snippet(s) below:\n\n");
        } else {
            sb.append("You can implement these steps using the snippet(s) below:\n\n");
        }
        String snippets = suggestions
                .stream()
                .map(Suggestion::getSnippets)
                .flatMap(Collection::stream)
                .distinct()
                .collect(Collectors.joining("\n", "", "\n"));
        sb.append(snippets);
        return sb.toString();
    }

    @Override
    public boolean isSkip() {
        return false;
    }

}
