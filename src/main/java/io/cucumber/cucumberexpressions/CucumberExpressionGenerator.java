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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@API(status = API.Status.STABLE)
public final class CucumberExpressionGenerator {
    private final ParameterTypeRegistry parameterTypeRegistry;

    public CucumberExpressionGenerator(ParameterTypeRegistry parameterTypeRegistry) {
        this.parameterTypeRegistry = parameterTypeRegistry;
    }

    public List<GeneratedExpression> generateExpressions(String text) {
        List<List<ParameterType<?>>> parameterTypeCombinations = new ArrayList<>();
        List<ParameterTypeMatcher> parameterTypeMatchers = createParameterTypeMatchers(text);
        StringBuilder expressionTemplate = new StringBuilder();
        int pos = 0;
        while (true) {
            List<ParameterTypeMatcher> matchingParameterTypeMatchers = new ArrayList<>();

            for (ParameterTypeMatcher parameterTypeMatcher : parameterTypeMatchers) {
                if (parameterTypeMatcher.advanceToAndFind(pos)) {
                    matchingParameterTypeMatchers.add(parameterTypeMatcher);
                }
            }

            if (!matchingParameterTypeMatchers.isEmpty()) {
                Collections.sort(matchingParameterTypeMatchers);

                // Find all the best parameter type matchers, they are all candidates.
                ParameterTypeMatcher bestParameterTypeMatcher = matchingParameterTypeMatchers.get(0);
                List<ParameterTypeMatcher> bestParameterTypeMatchers = new ArrayList<>();
                for (ParameterTypeMatcher m : matchingParameterTypeMatchers) {
                    if (m.compareTo(bestParameterTypeMatcher) == 0) {
                        bestParameterTypeMatchers.add(m);
                    }
                }

                // Build a list of parameter types without duplicates. The reason there
                // might be duplicates is that some parameter types have more than one regexp,
                // which means multiple ParameterTypeMatcher objects will have a reference to the
                // same ParameterType.
                // We're sorting the list so preferential parameter types are listed first.
                // Users are most likely to want these, so they should be listed at the top.
                Set<ParameterType<?>> set = new HashSet<>();
                for (ParameterTypeMatcher parameterTypeMatcher : bestParameterTypeMatchers) {
                    ParameterType<?> parameterType = parameterTypeMatcher.getParameterType();
                    set.add(parameterType);
                }
                SortedSet<ParameterType<?>> parameterTypes = new TreeSet<>(set);

                parameterTypeCombinations.add(new ArrayList<>(parameterTypes));

                expressionTemplate
                        .append(escape(text.substring(pos, bestParameterTypeMatcher.start())))
                        .append("{%s}");
                pos = bestParameterTypeMatcher.start() + bestParameterTypeMatcher.group().length();
            } else {
                break;
            }

            if (pos >= text.length()) {
                break;
            }
        }
        expressionTemplate.append(escape(text.substring(pos)));
        return new CombinatorialGeneratedExpressionFactory(expressionTemplate.toString(), parameterTypeCombinations).generateExpressions();
    }

    private String escape(String s) {
        return s.replaceAll("%", "%%") // Escape for String.format
                .replaceAll("\\(", "\\\\(")
                .replaceAll("\\{", "\\\\{")
                .replaceAll("/", "\\\\/");
    }

    private List<ParameterTypeMatcher> createParameterTypeMatchers(String text) {
        Collection<ParameterType<?>> parameterTypes = parameterTypeRegistry.getParameterTypes();
        List<ParameterTypeMatcher> parameterTypeMatchers = new ArrayList<>();
        for (ParameterType<?> parameterType : parameterTypes) {
            if (parameterType.useForSnippets()) {
                parameterTypeMatchers.addAll(createParameterTypeMatchers(parameterType, text));
            }
        }
        return parameterTypeMatchers;
    }

    private static List<ParameterTypeMatcher> createParameterTypeMatchers(ParameterType<?> parameterType, String text) {
        List<ParameterTypeMatcher> result = new ArrayList<>();
        List<String> captureGroupRegexps = parameterType.getRegexps();
        for (String captureGroupRegexp : captureGroupRegexps) {
            Pattern regexp = Pattern.compile("(" + captureGroupRegexp + ")");
            Matcher matcher = regexp.matcher(text);
            result.add(new ParameterTypeMatcher(parameterType, matcher, text));
        }
        return result;
    }

}
