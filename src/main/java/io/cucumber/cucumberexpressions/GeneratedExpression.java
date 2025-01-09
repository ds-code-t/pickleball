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

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@API(status = API.Status.STABLE)
public class GeneratedExpression {
    private static final Collator ENGLISH_COLLATOR = Collator.getInstance(Locale.ENGLISH);
    private static final String[] JAVA_KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "extends",
            "false", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new",
            "null", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws",
            "transient", "true", "try", "void", "volatile",
            "while"
    };
    private final String expressionTemplate;
    private final List<ParameterType<?>> parameterTypes;

    GeneratedExpression(String expressionTemplate, List<ParameterType<?>> parameterTypes) {
        this.expressionTemplate = expressionTemplate;
        this.parameterTypes = parameterTypes;
    }

    private static boolean isJavaKeyword(String keyword) {
        return (Arrays.binarySearch(JAVA_KEYWORDS, keyword, ENGLISH_COLLATOR) >= 0);
    }

    public String getSource() {
        List<String> parameterTypeNames = new ArrayList<>();
        for (ParameterType<?> parameterType : parameterTypes) {
            String name = parameterType.getName();
            parameterTypeNames.add(name);
        }
        return String.format(expressionTemplate, parameterTypeNames.toArray());
    }

    private String getParameterName(String typeName, Map<String, Integer> usageByTypeName) {
        Integer count = usageByTypeName.get(typeName);
        count = count != null ? count + 1 : 1;
        usageByTypeName.put(typeName, count);

        return count == 1 && !isJavaKeyword(typeName) ? typeName : typeName + count;
    }

    public List<String> getParameterNames() {
        HashMap<String, Integer> usageByTypeName = new HashMap<>();
        List<String> list = new ArrayList<>();
        for (ParameterType<?> parameterType : parameterTypes) {
            String parameterName = getParameterName(parameterType.getName(), usageByTypeName);
            list.add(parameterName);
        }
        return list;
    }

    public List<ParameterType<?>> getParameterTypes() {
        return parameterTypes;
    }
}
