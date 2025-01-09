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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ParameterTypeMatcher implements Comparable<ParameterTypeMatcher> {
    private final ParameterType<?> parameterType;
    private final Matcher matcher;
    private final String text;

    ParameterTypeMatcher(ParameterType<?> parameterType, Matcher matcher, String text) {
        this.parameterType = parameterType;
        this.matcher = matcher;
        this.text = text;
    }

    private static boolean isWhitespaceOrPunctuationOrSymbol(char c) {
        return Pattern.matches("[\\p{Z}\\p{P}\\p{S}]", new String(new char[] { c }));
    }

    boolean advanceToAndFind(int newMatchPos) {
        // Unlike js, ruby and go, the matcher is stateful
        // so we can't use the immutable semantics.
        matcher.region(newMatchPos, text.length());
        while (matcher.find()) {
            if (group().isEmpty()) {
                continue;
            }
            if (groupHasWordBoundaryOnBothSides()) {
                return true;
            }
        }
        return false;
    }

    private boolean groupHasWordBoundaryOnBothSides() {
        return groupHasLeftWordBoundary() && groupHasRightWordBoundary();
    }

    private boolean groupHasLeftWordBoundary() {
        if (matcher.start() > 0) {
            char before = text.charAt(matcher.start() - 1);
            return isWhitespaceOrPunctuationOrSymbol(before);
        }
        return true;
    }

    private boolean groupHasRightWordBoundary() {
        if (matcher.end() < text.length()) {
            char after = text.charAt(matcher.end());
            return isWhitespaceOrPunctuationOrSymbol(after);
        }
        return true;
    }

    int start() {
        return matcher.start();
    }

    String group() {
        return matcher.group();
    }

    @Override
    public int compareTo(ParameterTypeMatcher o) {
        int posComparison = Integer.compare(start(), o.start());
        if (posComparison != 0) return posComparison;
        int lengthComparison = Integer.compare(o.group().length(), group().length());
        if (lengthComparison != 0) return lengthComparison;
        int weightComparison = Integer.compare(o.parameterType.weight(), parameterType.weight());
        if (weightComparison != 0) return weightComparison;
        return 0;
    }

    ParameterType<?> getParameterType() {
        return parameterType;
    }

    public String toString() {
        return parameterType.getType().toString();
    }
}
