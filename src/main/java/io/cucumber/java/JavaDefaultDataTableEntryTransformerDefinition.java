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

import io.cucumber.core.backend.DefaultDataTableEntryTransformerDefinition;
import io.cucumber.core.backend.Lookup;
import io.cucumber.datatable.TableCellByTypeTransformer;
import io.cucumber.datatable.TableEntryByTypeTransformer;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

import static io.cucumber.java.InvalidMethodSignatureException.builder;

class JavaDefaultDataTableEntryTransformerDefinition extends AbstractDatatableElementTransformerDefinition
        implements DefaultDataTableEntryTransformerDefinition {

    private final TableEntryByTypeTransformer transformer;
    private final boolean headersToProperties;

    JavaDefaultDataTableEntryTransformerDefinition(Method method, Lookup lookup) {
        this(method, lookup, false, new String[0]);
    }

    JavaDefaultDataTableEntryTransformerDefinition(
            Method method, Lookup lookup, boolean headersToProperties, String[] emptyPatterns
    ) {
        super(requireValidMethod(method), lookup, emptyPatterns);
        this.headersToProperties = headersToProperties;
        this.transformer = (entryValue, toValueType, cellTransformer) -> execute(
            replaceEmptyPatternsWithEmptyString(entryValue), toValueType, cellTransformer);
    }

    private static Method requireValidMethod(Method method) {
        Class<?> returnType = method.getReturnType();
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            throw createInvalidSignatureException(method);
        }

        Type[] parameterTypes = method.getParameterTypes();
        Type[] genericParameterTypes = method.getGenericParameterTypes();

        if (parameterTypes.length < 2 || parameterTypes.length > 3) {
            throw createInvalidSignatureException(method);
        }

        Type parameterType = genericParameterTypes[0];

        if (!Object.class.equals(parameterType)) {
            if (!(parameterType instanceof ParameterizedType)) {
                throw createInvalidSignatureException(method);
            }
            ParameterizedType parameterizedType = (ParameterizedType) parameterType;
            Type rawType = parameterizedType.getRawType();
            if (!Map.class.equals(rawType)) {
                throw createInvalidSignatureException(method);
            }
            Type[] typeParameters = parameterizedType.getActualTypeArguments();
            for (Type typeParameter : typeParameters) {
                if (!String.class.equals(typeParameter)) {
                    throw createInvalidSignatureException(method);
                }
            }
        }

        if (!Type.class.equals(parameterTypes[1])) {
            throw createInvalidSignatureException(method);
        }

        if (parameterTypes.length == 3) {
            if (!(Object.class.equals(parameterTypes[2])
                    || TableCellByTypeTransformer.class.equals(parameterTypes[2]))) {
                throw createInvalidSignatureException(method);
            }
        }

        return method;
    }

    private Object execute(
            Map<String, String> fromValue, Type toValueType, TableCellByTypeTransformer cellTransformer
    ) {
        Object[] args;
        if (method.getParameterTypes().length == 3) {
            args = new Object[] { fromValue, toValueType, cellTransformer };
        } else {
            args = new Object[] { fromValue, toValueType };
        }
        return invokeMethod(args);
    }

    private static InvalidMethodSignatureException createInvalidSignatureException(Method method) {
        return builder(method)
                .addAnnotation(DefaultDataTableEntryTransformer.class)
                .addSignature("public Object defaultDataTableEntry(Map<String, String> fromValue, Type toValueType)")
                .addSignature("public Object defaultDataTableEntry(Object fromValue, Type toValueType)")
                .build();
    }

    @Override
    public boolean headersToProperties() {
        return headersToProperties;
    }

    @Override
    public TableEntryByTypeTransformer tableEntryByTypeTransformer() {
        return transformer;
    }

}
