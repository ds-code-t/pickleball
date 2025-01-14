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

import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.Lookup;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static io.cucumber.java.MethodScanner.isCucumberAnnotation;

final class GlueAdaptor {

    private final Lookup lookup;
    private final Glue glue;

    GlueAdaptor(Lookup lookup, Glue glue) {
        this.lookup = lookup;
        this.glue = glue;
    }

    void addDefinition(Method method, Annotation annotation) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        if (isCucumberAnnotation(annotationType)) {
            String expression = expression(annotation);
            glue.addStepDefinition(new JavaStepDefinition(method, expression, lookup));
        } else if (annotationType.equals(Before.class)) {
            Before before = (Before) annotation;
            String tagExpression = before.value();
            glue.addBeforeHook(new JavaHookDefinition(method, tagExpression, before.order(), lookup));
        } else if (annotationType.equals(BeforeAll.class)) {
            BeforeAll beforeAll = (BeforeAll) annotation;
            glue.addBeforeAllHook(new JavaStaticHookDefinition(method, beforeAll.order(), lookup));
        } else if (annotationType.equals(After.class)) {
            After after = (After) annotation;
            String tagExpression = after.value();
            glue.addAfterHook(new JavaHookDefinition(method, tagExpression, after.order(), lookup));
        } else if (annotationType.equals(AfterAll.class)) {
            AfterAll afterAll = (AfterAll) annotation;
            glue.addAfterAllHook(new JavaStaticHookDefinition(method, afterAll.order(), lookup));
        } else if (annotationType.equals(BeforeStep.class)) {
            BeforeStep beforeStep = (BeforeStep) annotation;
            String tagExpression = beforeStep.value();
            glue.addBeforeStepHook(new JavaHookDefinition(method, tagExpression, beforeStep.order(), lookup));
        } else if (annotationType.equals(AfterStep.class)) {
            AfterStep afterStep = (AfterStep) annotation;
            String tagExpression = afterStep.value();
            glue.addAfterStepHook(new JavaHookDefinition(method, tagExpression, afterStep.order(), lookup));
        } else if (annotationType.equals(ParameterType.class)) {
            ParameterType parameterType = (ParameterType) annotation;
            String pattern = parameterType.value();
            String name = parameterType.name();
            boolean useForSnippets = parameterType.useForSnippets();
            boolean preferForRegexMatch = parameterType.preferForRegexMatch();
            boolean useRegexpMatchAsStrongTypeHint = parameterType.useRegexpMatchAsStrongTypeHint();
            glue.addParameterType(new JavaParameterTypeDefinition(name, pattern, method, useForSnippets,
                preferForRegexMatch, useRegexpMatchAsStrongTypeHint, lookup));
        } else if (annotationType.equals(DataTableType.class)) {
            DataTableType dataTableType = (DataTableType) annotation;
            glue.addDataTableType(
                new JavaDataTableTypeDefinition(method, lookup, dataTableType.replaceWithEmptyString()));
        } else if (annotationType.equals(DefaultParameterTransformer.class)) {
            glue.addDefaultParameterTransformer(new JavaDefaultParameterTransformerDefinition(method, lookup));
        } else if (annotationType.equals(DefaultDataTableEntryTransformer.class)) {
            DefaultDataTableEntryTransformer transformer = (DefaultDataTableEntryTransformer) annotation;
            boolean headersToProperties = transformer.headersToProperties();
            String[] replaceWithEmptyString = transformer.replaceWithEmptyString();
            glue.addDefaultDataTableEntryTransformer(new JavaDefaultDataTableEntryTransformerDefinition(method, lookup,
                headersToProperties, replaceWithEmptyString));
        } else if (annotationType.equals(DefaultDataTableCellTransformer.class)) {
            DefaultDataTableCellTransformer cellTransformer = (DefaultDataTableCellTransformer) annotation;
            String[] emptyPatterns = cellTransformer.replaceWithEmptyString();
            glue.addDefaultDataTableCellTransformer(
                new JavaDefaultDataTableCellTransformerDefinition(method, lookup, emptyPatterns));
        } else if (annotationType.equals(DocStringType.class)) {
            DocStringType docStringType = (DocStringType) annotation;
            String contentType = docStringType.contentType();
            glue.addDocStringType(new JavaDocStringTypeDefinition(contentType, method, lookup));
        }
    }

    private static String expression(Annotation annotation) {
        try {
            Method expressionMethod = annotation.getClass().getMethod("value");
            return (String) Invoker.invoke(annotation, expressionMethod);
        } catch (NoSuchMethodException e) {
            // Should never happen.
            throw new IllegalStateException(e);
        }
    }

}
