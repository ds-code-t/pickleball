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

import io.cucumber.core.backend.DocStringTypeDefinition;
import io.cucumber.core.backend.Lookup;
import io.cucumber.docstring.DocStringType;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static io.cucumber.java.InvalidMethodSignatureException.builder;

class JavaDocStringTypeDefinition extends AbstractGlueDefinition implements DocStringTypeDefinition {

    private final io.cucumber.docstring.DocStringType docStringType;

    JavaDocStringTypeDefinition(String contentType, Method method, Lookup lookup) {
        super(requireValidMethod(method), lookup);
        this.docStringType = new DocStringType(
            this.method.getGenericReturnType(),
            contentType.isEmpty() ? method.getName() : contentType,
            this::invokeMethod);
    }

    private static Method requireValidMethod(Method method) {
        Type returnType = method.getGenericReturnType();
        if (Void.class.equals(returnType) || void.class.equals(returnType)) {
            throw createInvalidSignatureException(method);
        }

        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length != 1) {
            throw createInvalidSignatureException(method);
        }

        for (Type parameterType : parameterTypes) {
            if (!String.class.equals(parameterType)) {
                throw createInvalidSignatureException(method);
            }
        }

        return method;
    }

    private static InvalidMethodSignatureException createInvalidSignatureException(Method method) {
        return builder(method)
                .addAnnotation(io.cucumber.java.DocStringType.class)
                .addSignature("public JsonNode json(String content)")
                .addNote("Note: JsonNode is an example of the class you want to convert content to")
                .build();
    }

    @Override
    public DocStringType docStringType() {
        return docStringType;
    }

}
