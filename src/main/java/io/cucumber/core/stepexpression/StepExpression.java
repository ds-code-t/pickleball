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

import io.cucumber.cucumberexpressions.Expression;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

public final class StepExpression {

    private final Expression expression;
    private final DocStringTransformer<?> docStringType;
    private final RawTableTransformer<?> tableType;

    StepExpression(Expression expression, DocStringTransformer<?> docStringType, RawTableTransformer<?> tableType) {
        this.expression = requireNonNull(expression);
        this.docStringType = requireNonNull(docStringType);
        this.tableType = requireNonNull(tableType);
    }

    public Class<? extends Expression> getExpressionType() {
        return expression.getClass();
    }

    public String getSource() {
        return expression.getSource();
    }

    public List<Argument> match(String text, List<List<String>> cells, Type... types) {
        List<Argument> list = match(text, types);

        if (list == null) {
            return null;
        }

        list.add(new DataTableArgument(tableType, cells));

        return list;

    }

    public List<Argument> match(String text, Type... types) {
        List<io.cucumber.cucumberexpressions.Argument<?>> match = expression.match(text, types);
        if (match == null) {
            return null;
        }
        return wrapPlusOne(match);
    }

    private static List<Argument> wrapPlusOne(List<io.cucumber.cucumberexpressions.Argument<?>> match) {
        List<Argument> copy = new ArrayList<>(match.size() + 1);
        for (io.cucumber.cucumberexpressions.Argument<?> argument : match) {
            copy.add(new ExpressionArgument(argument));
        }
        return copy;
    }

    public List<Argument> match(String text, String content, String contentType, Type... types) {
        List<Argument> list = match(text, types);
        if (list == null) {
            return null;
        }

        list.add(new DocStringArgument(this.docStringType, content, contentType));

        return list;
    }

}
