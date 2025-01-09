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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class CombinatorialGeneratedExpressionFactory {
    // 256 generated expressions ought to be enough for anybody
    private static final int MAX_EXPRESSIONS = 256;
    private final String expressionTemplate;
    private final List<List<ParameterType<?>>> parameterTypeCombinations;

    CombinatorialGeneratedExpressionFactory(
            String expressionTemplate,
            List<List<ParameterType<?>>> parameterTypeCombinations) {

        this.expressionTemplate = expressionTemplate;
        this.parameterTypeCombinations = parameterTypeCombinations;
    }

    List<GeneratedExpression> generateExpressions() {
        List<GeneratedExpression> generatedExpressions = new ArrayList<>();
        ArrayDeque<ParameterType<?>> permutation = new ArrayDeque<>(parameterTypeCombinations.size());
        generatePermutations(generatedExpressions, permutation);
        return generatedExpressions;
    }

    private void generatePermutations(
            List<GeneratedExpression> generatedExpressions,
            Deque<ParameterType<?>> permutation
    ) {
        if (generatedExpressions.size() >= MAX_EXPRESSIONS) {
            return;
        }

        if (permutation.size() == parameterTypeCombinations.size()) {
            ArrayList<ParameterType<?>> permutationCopy = new ArrayList<>(permutation);
            generatedExpressions.add(new GeneratedExpression(expressionTemplate, permutationCopy));
            return;
        }

        List<ParameterType<?>> parameterTypes = parameterTypeCombinations.get(permutation.size());
        for (ParameterType<?> parameterType : parameterTypes) {
            // Avoid recursion if no elements can be added.
            if (generatedExpressions.size() >= MAX_EXPRESSIONS) {
                return;
            }
            permutation.addLast(parameterType);
            generatePermutations(generatedExpressions, permutation);
            permutation.removeLast();
        }
    }
}
