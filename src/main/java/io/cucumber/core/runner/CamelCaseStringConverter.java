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

package io.cucumber.core.runner;

import io.cucumber.core.exception.CucumberException;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

class CamelCaseStringConverter {

    private static final String WHITESPACE = " ";
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    Map<String, String> toCamelCase(Map<String, String> fromValue) {
        // First we create a map from converted keys to unconverted keys
        // This will allow us to spot duplicate keys and inform the user
        // exactly which key caused the problem.
        Map<String, String> map = new HashMap<>();
        fromValue.keySet().forEach(key -> {
            String newKey = toCamelCase(key);
            String conflictingKey = map.get(newKey);
            if (conflictingKey != null) {
                throw createDuplicateKeyException(key, conflictingKey, newKey);
            }
            map.put(newKey, key);
        });

        // Then once we have a unique mapping from converted keys to unconverted
        // keys we replace the unconverted keys with the value associated with
        // with that key
        map.replaceAll((newKey, oldKey) -> fromValue.get(oldKey));
        return map;
    }

    private static String toCamelCase(String string) {
        String[] parts = normalizeSpace(string).split(WHITESPACE);
        parts[0] = uncapitalize(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            parts[i] = capitalize(parts[i]);
        }
        return join(parts);
    }

    private static CucumberException createDuplicateKeyException(String key, String conflictingKey, String newKey) {
        return new CucumberException(String.format(
            "Failed to convert header '%s' to property name. '%s' also converted to '%s'",
            key, conflictingKey, newKey));
    }

    private static String normalizeSpace(String originalHeaderName) {
        return WHITESPACE_PATTERN.matcher(originalHeaderName.trim()).replaceAll(WHITESPACE);
    }

    private static String uncapitalize(String string) {
        return Character.toLowerCase(string.charAt(0)) + string.substring(1);
    }

    private static String capitalize(String string) {
        return Character.toTitleCase(string.charAt(0)) + string.substring(1);
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            sb.append(s);
        }
        return sb.toString();
    }

}
