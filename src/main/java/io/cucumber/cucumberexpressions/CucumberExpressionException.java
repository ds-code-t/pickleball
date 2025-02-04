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

import io.cucumber.cucumberexpressions.Ast.Located;
import io.cucumber.cucumberexpressions.Ast.Node;
import io.cucumber.cucumberexpressions.Ast.Token;
import io.cucumber.cucumberexpressions.Ast.Token.Type;
import org.apiguardian.api.API;

@API(status = API.Status.STABLE)
public class CucumberExpressionException extends RuntimeException {

    CucumberExpressionException(String message) {
        super(message);
    }

    CucumberExpressionException(String message, Throwable cause) {
        super(message, cause);
    }

    static CucumberExpressionException createMissingEndToken(String expression, Type beginToken, Type endToken,
            Token current) {
        return new CucumberExpressionException(message(
                current.start(),
                expression,
                pointAt(current),
                "The '" + beginToken.symbol() + "' does not have a matching '" + endToken.symbol() + "'",
                "If you did not intend to use " + beginToken.purpose() + " you can use '\\" + beginToken
                        .symbol() + "' to escape the " + beginToken.purpose()));
    }

    static CucumberExpressionException createAlternationNotAllowedInOptional(String expression, Token current) {
        return new CucumberExpressionException(message(
                current.start,
                expression,
                pointAt(current),
                "An alternation can not be used inside an optional",
                "If you did not mean to use an alternation you can use '\\/' to escape the '/'. Otherwise rephrase your expression or consider using a regular expression instead."
        ));
    }

    static CucumberExpressionException createTheEndOfLineCanNotBeEscaped(String expression) {
        int index = expression.codePointCount(0, expression.length()) - 1;
        return new CucumberExpressionException(message(
                index,
                expression,
                pointAt(index),
                "The end of line can not be escaped",
                "You can use '\\\\' to escape the '\\'"
        ));
    }

    static CucumberExpressionException createAlternativeMayNotBeEmpty(Node node, String expression) {
        return new CucumberExpressionException(message(
                node.start(),
                expression,
                pointAt(node),
                "Alternative may not be empty",
                "If you did not mean to use an alternative you can use '\\/' to escape the '/'"));
    }

    static CucumberExpressionException createParameterIsNotAllowedInOptional(Node node, String expression) {
        return new CucumberExpressionException(message(
                node.start(),
                expression,
                pointAt(node),
                "An optional may not contain a parameter type",
                "If you did not mean to use an parameter type you can use '\\{' to escape the '{'"));
    }
    static CucumberExpressionException createOptionalIsNotAllowedInOptional(Node node, String expression) {
        return new CucumberExpressionException(message(
                node.start(),
                expression,
                pointAt(node),
                "An optional may not contain an other optional",
                "If you did not mean to use an optional type you can use '\\(' to escape the '('. For more complicated expressions consider using a regular expression instead."));
    }

    static CucumberExpressionException createOptionalMayNotBeEmpty(Node node, String expression) {
        return new CucumberExpressionException(message(
                node.start(),
                expression,
                pointAt(node),
                "An optional must contain some text",
                "If you did not mean to use an optional you can use '\\(' to escape the '('"));
    }

    static CucumberExpressionException createAlternativeMayNotExclusivelyContainOptionals(Node node,
            String expression) {
        return new CucumberExpressionException(message(
                node.start(),
                expression,
                pointAt(node),
                "An alternative may not exclusively contain optionals",
                "If you did not mean to use an optional you can use '\\(' to escape the '('"));
    }

    private static String thisCucumberExpressionHasAProblemAt(int index) {
        return "This Cucumber Expression has a problem at column " + (index + 1) + ":" + "\n";
    }

    static CucumberExpressionException createCantEscape(String expression, int index) {
        return new CucumberExpressionException(message(
                index,
                expression,
                pointAt(index),
                "Only the characters '{', '}', '(', ')', '\\', '/' and whitespace can be escaped",
                "If you did mean to use an '\\' you can use '\\\\' to escape it"));
    }

    static CucumberExpressionException createInvalidParameterTypeName(String name) {
        return new CucumberExpressionException(
                "Illegal character in parameter name {" + name + "}. Parameter names may not contain '{', '}', '(', ')', '\\' or '/'");
    }

    /**
     * Not very clear, but this message has to be language independent
     * Other languages have dedicated syntax for writing down regular expressions
     * <p>
     * In java a regular expression has to start with {@code ^} and end with
     * {@code $} to be recognized as one by Cucumber.
     *
     * @see ExpressionFactory
     */
    static CucumberExpressionException createInvalidParameterTypeName(Token token, String expression) {
        return new CucumberExpressionException(message(
                token.start(),
                expression,
                pointAt(token),
                "Parameter names may not contain '{', '}', '(', ')', '\\' or '/'",
                "Did you mean to use a regular expression?"));
    }

    static String message(int index, String expression, String pointer, String problem,
            String solution) {
        return thisCucumberExpressionHasAProblemAt(index) +
                "\n" +
                expression + "\n" +
                pointer + "\n" +
                problem + ".\n" +
                solution;
    }

    static String pointAt(Located node) {
        StringBuilder pointer = new StringBuilder(pointAt(node.start()));
        if (node.start() + 1 < node.end()) {
            for (int i = node.start() + 1; i < node.end() - 1; i++) {
                pointer.append("-");
            }
            pointer.append("^");
        }
        return pointer.toString();
    }

    private static String pointAt(int index) {
        StringBuilder pointer = new StringBuilder();
        for (int i = 0; i < index; i++) {
            pointer.append(" ");
        }
        pointer.append("^");
        return pointer.toString();
    }

}
