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

import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.Lookup;
import io.cucumber.core.backend.TestCaseState;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static io.cucumber.java.InvalidMethodSignatureException.builder;
import static java.util.Objects.requireNonNull;

final class JavaHookDefinition extends AbstractGlueDefinition implements HookDefinition {

    private final String tagExpression;
    private final int order;

    JavaHookDefinition(Method method, String tagExpression, int order, Lookup lookup) {
        super(requireValidMethod(method), lookup);
        this.tagExpression = requireNonNull(tagExpression, "tag-expression may not be null");
        this.order = order;
    }

    private static Method requireValidMethod(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length > 1) {
            throw createInvalidSignatureException(method);
        }

        if (parameterTypes.length == 1) {
            Class<?> parameterType = parameterTypes[0];
            if (!(Object.class.equals(parameterType) || io.cucumber.java.Scenario.class.equals(parameterType))) {
                throw createInvalidSignatureException(method);
            }
        }

        Type returnType = method.getGenericReturnType();
        if (!Void.class.equals(returnType) && !void.class.equals(returnType)) {
            throw createInvalidSignatureException(method);
        }
        return method;
    }

    private static InvalidMethodSignatureException createInvalidSignatureException(Method method) {
        return builder(method)
                .addAnnotation(Before.class)
                .addAnnotation(After.class)
                .addAnnotation(BeforeStep.class)
                .addAnnotation(AfterStep.class)
                .addSignature("public void before_or_after(io.cucumber.java.Scenario scenario)")
                .addSignature("public void before_or_after()")
                .build();
    }

    @Override
    public void execute(TestCaseState state) {
        Object[] args;
        if (method.getParameterTypes().length == 1) {
            args = new Object[] { new io.cucumber.java.Scenario(state) };
        } else {
            args = new Object[0];
        }

        invokeMethod(args);
    }

    @Override
    public String getTagExpression() {
        return tagExpression;
    }

    @Override
    public int getOrder() {
        return order;
    }

}
