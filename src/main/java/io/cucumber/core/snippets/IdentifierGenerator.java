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

package io.cucumber.core.snippets;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Character.isJavaIdentifierStart;

final class IdentifierGenerator {

    private static final String BETWEEN_LOWER_AND_UPPER = "(?<=\\p{Ll})(?=\\p{Lu})";
    private static final String BEFORE_UPPER_AND_LOWER = "(?<=\\p{L})(?=\\p{Lu}\\p{Ll})";
    private static final Pattern SPLIT_CAMEL_CASE = Pattern
            .compile(BETWEEN_LOWER_AND_UPPER + "|" + BEFORE_UPPER_AND_LOWER);
    private static final Pattern SPLIT_WHITESPACE = Pattern.compile("\\s");
    private static final Pattern SPLIT_UNDERSCORE = Pattern.compile("_");

    private static final char SUBST = ' ';
    private final Joiner joiner;

    IdentifierGenerator(Joiner joiner) {
        this.joiner = joiner;
    }

    String generate(String sentence) {
        if (sentence.isEmpty()) {
            throw new IllegalArgumentException("Cannot create function name from empty sentence");
        }

        List<String> words = Stream.of(sentence)
                .map(this::replaceIllegalCharacters)
                .map(String::trim)
                .flatMap(SPLIT_WHITESPACE::splitAsStream)
                .flatMap(SPLIT_CAMEL_CASE::splitAsStream)
                .flatMap(SPLIT_UNDERSCORE::splitAsStream)
                .collect(Collectors.toList());

        return joiner.concatenate(words);
    }

    private String replaceIllegalCharacters(String sentence) {
        StringBuilder sanitized = new StringBuilder();
        sanitized.append(isJavaIdentifierStart(sentence.charAt(0)) ? sentence.charAt(0) : SUBST);
        for (int i = 1; i < sentence.length(); i++) {
            if (Character.isJavaIdentifierPart(sentence.charAt(i))) {
                sanitized.append(sentence.charAt(i));
            } else if (sanitized.charAt(sanitized.length() - 1) != SUBST && i != sentence.length() - 1) {
                sanitized.append(SUBST);
            }
        }
        return sanitized.toString();
    }

}
