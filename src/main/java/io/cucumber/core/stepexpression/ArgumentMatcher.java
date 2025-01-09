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

package io.cucumber.core.stepexpression;

import io.cucumber.core.gherkin.DataTableArgument;
import io.cucumber.core.gherkin.DocStringArgument;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.messages.GherkinMessagesStep;

import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Collectors;

public final class ArgumentMatcher {

    private final StepExpression expression;

    public ArgumentMatcher(StepExpression expression) {
        this.expression = expression;
    }

    public List<Argument> argumentsFrom(Step step, Type... types) {
        GherkinMessagesStep gStep = (GherkinMessagesStep) step;
        io.cucumber.core.gherkin.Argument arg = step.getArgument();
        if (arg == null) {
            return expression.match(gStep.getRunTimeText(), types);
        }

        if (arg instanceof io.cucumber.core.gherkin.DocStringArgument) {
            DocStringArgument docString = (DocStringArgument) arg;
            String content = docString.getContent();
            String contentType = docString.getMediaType();
            return expression.match(gStep.getRunTimeText(), content, contentType, types);
        }

        if (arg instanceof io.cucumber.core.gherkin.DataTableArgument) {
            DataTableArgument table = (DataTableArgument) arg;
            List<List<String>> cells = emptyCellsToNull(table.cells());
            return expression.match(gStep.getRunTimeText(), cells, types);
        }

        throw new IllegalStateException("Argument was neither PickleString nor PickleTable");
    }

    private static List<List<String>> emptyCellsToNull(List<List<String>> cells) {
        return cells.stream()
                .map(row -> row.stream()
                        .map(s -> s.isEmpty() ? null : s)
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

}
