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

import io.cucumber.core.backend.DataTableTypeDefinition;
import io.cucumber.core.backend.Lookup;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableType;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static io.cucumber.java.InvalidMethodSignatureException.builder;

class JavaDataTableTypeDefinition extends AbstractDatatableElementTransformerDefinition
        implements DataTableTypeDefinition {

    private final DataTableType dataTableType;

    JavaDataTableTypeDefinition(Method method, Lookup lookup, String[] emptyPatterns) {
        super(method, lookup, emptyPatterns);
        this.dataTableType = createDataTableType(method);
    }

    private DataTableType createDataTableType(Method method) {
        Type returnType = requireValidReturnType(method);
        Type parameterType = requireValidParameterType(method);

        if (DataTable.class.equals(parameterType)) {
            return new DataTableType(
                returnType,
                (DataTable table) -> invokeMethod(
                    replaceEmptyPatternsWithEmptyString(table)));
        }

        if (List.class.equals(parameterType)) {
            return new DataTableType(
                returnType,
                (List<String> row) -> invokeMethod(
                    replaceEmptyPatternsWithEmptyString(row)));
        }

        if (Map.class.equals(parameterType)) {
            return new DataTableType(
                returnType,
                (Map<String, String> entry) -> invokeMethod(
                    replaceEmptyPatternsWithEmptyString(entry)));
        }

        if (String.class.equals(parameterType)) {
            return new DataTableType(
                returnType,
                (String cell) -> invokeMethod(
                    replaceEmptyPatternsWithEmptyString(cell)));
        }

        throw createInvalidSignatureException(method);
    }

    private static Type requireValidReturnType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            throw createInvalidSignatureException(method);
        }

        return returnType;
    }

    private static Type requireValidParameterType(Method method) {
        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length != 1) {
            throw createInvalidSignatureException(method);
        }

        Type parameterType = parameterTypes[0];
        if (!(parameterType instanceof ParameterizedType)) {
            return parameterType;
        }

        ParameterizedType parameterizedType = (ParameterizedType) parameterType;
        Type[] typeParameters = parameterizedType.getActualTypeArguments();
        for (Type typeParameter : typeParameters) {
            if (!String.class.equals(typeParameter)) {
                throw createInvalidSignatureException(method);
            }
        }

        return parameterizedType.getRawType();
    }

    private static InvalidMethodSignatureException createInvalidSignatureException(Method method) {
        return builder(method)
                .addAnnotation(io.cucumber.java.DataTableType.class)
                .addSignature("public Author author(DataTable table)")
                .addSignature("public Author author(List<String> row)")
                .addSignature("public Author author(Map<String, String> entry)")
                .addSignature("public Author author(String cell)")
                .addNote("Note: Author is an example of the class you want to convert the table to.")
                .build();
    }

    @Override
    public DataTableType dataTableType() {
        return dataTableType;
    }

}
