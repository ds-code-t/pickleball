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

package io.cucumber.java;

import io.cucumber.core.backend.Lookup;
import io.cucumber.datatable.DataTable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.datatable.DataTable.create;
import static java.util.stream.Collectors.toList;

class AbstractDatatableElementTransformerDefinition extends AbstractGlueDefinition {

    private final String[] emptyPatterns;

    AbstractDatatableElementTransformerDefinition(Method method, Lookup lookup, String[] emptyPatterns) {
        super(method, lookup);
        this.emptyPatterns = emptyPatterns;
    }

    DataTable replaceEmptyPatternsWithEmptyString(DataTable table) {
        List<List<String>> rawWithEmptyStrings = table.cells().stream()
                .map(this::replaceEmptyPatternsWithEmptyString)
                .collect(toList());

        return create(rawWithEmptyStrings, table.getTableConverter());
    }

    List<String> replaceEmptyPatternsWithEmptyString(List<String> row) {
        return row.stream()
                .map(this::replaceEmptyPatternsWithEmptyString)
                .collect(toList());
    }

    String replaceEmptyPatternsWithEmptyString(String t) {
        for (String emptyPattern : emptyPatterns) {
            if (emptyPattern.equals(t)) {
                return "";
            }
        }
        return t;
    }

    Map<String, String> replaceEmptyPatternsWithEmptyString(Map<String, String> fromValue) {
        Map<String, String> replacement = new LinkedHashMap<>();

        fromValue.forEach((String key, String value) -> {
            String potentiallyEmptyKey = replaceEmptyPatternsWithEmptyString(key);
            String potentiallyEmptyValue = replaceEmptyPatternsWithEmptyString(value);

            if (replacement.containsKey(potentiallyEmptyKey)) {
                throw createDuplicateKeyAfterReplacement(fromValue);
            }
            replacement.put(potentiallyEmptyKey, potentiallyEmptyValue);
        });

        return replacement;
    }

    private IllegalArgumentException createDuplicateKeyAfterReplacement(Map<String, String> fromValue) {
        List<String> conflict = new ArrayList<>(2);
        for (String emptyPattern : emptyPatterns) {
            if (fromValue.containsKey(emptyPattern)) {
                conflict.add(emptyPattern);
            }
        }
        String msg = "After replacing %s and %s with empty strings the datatable entry contains duplicate keys: %s";
        return new IllegalArgumentException(String.format(msg, conflict.get(0), conflict.get(1), fromValue));
    }

}
