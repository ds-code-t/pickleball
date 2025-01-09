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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Creates a {@link CucumberExpression} or {@link RegularExpression} from a {@link String}
 * using heuristics. This is particularly useful for languages that don't have a
 * literal syntax for regular expressions. In Java, a regular expression has to be represented as a String.
 *
 *  A string that starts with `^` and/or ends with `$` (or written in script style, i.e. starting with `/` 
 *  and ending with `/`) is considered a regular expression.
 *  Everything else is considered a Cucumber expression.
 */
@API(status = API.Status.STABLE)
public final class ExpressionFactory {

    private static final Pattern PARAMETER_PATTERN = Pattern.compile("((?:\\\\){0,2})\\{([^}]*)\\}");

    private final ParameterTypeRegistry parameterTypeRegistry;

    public ExpressionFactory(ParameterTypeRegistry parameterTypeRegistry) {
        this.parameterTypeRegistry = parameterTypeRegistry;
    }

    public Expression createExpression(String expressionString) {
        /* This method is called often (typically about number_of_steps x
         * nbr_test_scenarios), thus performance is more important than
         * readability here.
         * Consequently, we check the first and last expressionString
         * characters to determine whether we need to create a
         * RegularExpression or a CucumberExpression (because character
         * matching is faster than startsWith/endsWith and regexp matching).
         */
        int length = expressionString.length();
        if (length == 0) {
            return new CucumberExpression(expressionString, this.parameterTypeRegistry);
        }

        int lastCharIndex = length - 1;
        char firstChar = expressionString.charAt(0);
        char lastChar = expressionString.charAt(lastCharIndex);

        if (firstChar == '^' || lastChar == '$') {
            return this.createRegularExpressionWithAnchors(expressionString);
        } else if (firstChar == '/' && lastChar == '/') {
            return new RegularExpression(Pattern.compile(expressionString.substring(1, lastCharIndex)), this.parameterTypeRegistry);
        }

        return new CucumberExpression(expressionString, this.parameterTypeRegistry);
    }

    private RegularExpression createRegularExpressionWithAnchors(String expressionString) {
        try {
            return new RegularExpression(Pattern.compile(expressionString), parameterTypeRegistry);
        } catch (PatternSyntaxException e) {
            if (PARAMETER_PATTERN.matcher(expressionString).find()) {
                throw new CucumberExpressionException("You cannot use anchors (^ or $) in Cucumber Expressions. Please remove them from " + expressionString, e);
            }
            throw e;
        }
    }
}
