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

import io.cucumber.core.backend.Snippet;
import io.cucumber.datatable.DataTable;

import java.lang.reflect.Type;
import java.util.Map;

import static java.util.stream.Collectors.joining;

abstract class AbstractJavaSnippet implements Snippet {

    @Override
    public final String tableHint() {
        return "" +
                "    // For automatic transformation, change DataTable to one of\n" +
                "    // E, List<E>, List<List<E>>, List<Map<K,V>>, Map<K,V> or\n" +
                "    // Map<K, List<V>>. E,K,V must be a String, Integer, Float,\n" +
                "    // Double, Byte, Short, Long, BigInteger or BigDecimal.\n" +
                "    //\n" +
                // TODO: Add doc URL
                "    // For other transformations you can register a DataTableType.\n";
    }

    @Override
    public final String arguments(Map<String, Type> arguments) {
        return arguments.entrySet()
                .stream()
                .map(argType -> getArgType(argType.getValue()) + " " + argType.getKey())
                .collect(joining(", "));
    }

    private String getArgType(Type argType) {
        if (argType instanceof Class) {
            Class<?> cType = (Class<?>) argType;
            if (cType.equals(DataTable.class)) {
                return cType.getName();
            }
            return cType.getSimpleName();
        }

        // Got a better idea? Send a PR.
        return argType.toString();
    }

    @Override
    public final String escapePattern(String pattern) {
        return pattern.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
