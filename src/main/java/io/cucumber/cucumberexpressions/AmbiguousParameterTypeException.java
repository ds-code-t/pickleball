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

package io.cucumber.cucumberexpressions;

import org.apiguardian.api.API;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.regex.Pattern;

@API(status = API.Status.STABLE)
public final class AmbiguousParameterTypeException extends CucumberExpressionException {
    private final Pattern regexp;
    private final String parameterTypeRegexp;
    private final SortedSet<ParameterType<?>> parameterTypes;
    private final List<GeneratedExpression> generatedExpressions;

    AmbiguousParameterTypeException(String parameterTypeRegexp, Pattern expressionRegexp, SortedSet<ParameterType<?>> parameterTypes, List<GeneratedExpression> generatedExpressions) {
        super(String.format("Your Regular Expression /%s/\n" +
                        "matches multiple parameter types with regexp /%s/:\n" +
                        "   %s\n" +
                        "\n" +
                        "I couldn't decide which one to use. You have two options:\n" +
                        "\n" +
                        "1) Use a Cucumber Expression instead of a Regular Expression. Try one of these:\n" +
                        "   %s\n" +
                        "\n" +
                        "2) Make one of the parameter types preferential and continue to use a Regular Expression.\n" +
                        "\n",
                expressionRegexp.pattern(),
                parameterTypeRegexp,
                parameterTypeNames(parameterTypes),
                expressions(generatedExpressions)
        ));
        this.regexp = expressionRegexp;
        this.parameterTypeRegexp = parameterTypeRegexp;
        this.parameterTypes = parameterTypes;
        this.generatedExpressions = generatedExpressions;
    }

    private static String parameterTypeNames(SortedSet<ParameterType<?>> parameterTypes) {

        List<String> parameterNames = new ArrayList<>();
        for (ParameterType<?> p : parameterTypes) {
            String s = "{" + p.getName() + "}";
            parameterNames.add(s);
        }
        return join(parameterNames);
    }

    private static String expressions(List<GeneratedExpression> generatedExpressions) {
        List<String> sources = new ArrayList<>();
        for (GeneratedExpression generatedExpression : generatedExpressions) {
            String source = generatedExpression.getSource();
            sources.add(source);
        }
        return join(sources);
    }

    private static String join(List<String> strings) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String element : strings) {
            if (first) {
                first = false;
            } else {
                builder.append("\n   ");
            }
            builder.append(element);
        }

        return builder.toString();
    }

    public Pattern getRegexp() {
        return regexp;
    }

    public String getParameterTypeRegexp() {
        return parameterTypeRegexp;
    }

    public SortedSet<ParameterType<?>> getParameterTypes() {
        return parameterTypes;
    }

    public List<GeneratedExpression> getGeneratedExpressions() {
        return generatedExpressions;
    }
}
