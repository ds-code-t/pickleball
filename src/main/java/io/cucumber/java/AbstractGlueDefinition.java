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

import io.cucumber.core.backend.Located;
import io.cucumber.core.backend.Lookup;
import io.cucumber.core.backend.SourceReference;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

abstract class AbstractGlueDefinition implements Located {

    public final Method method;
    private final Lookup lookup;
    private String fullFormat;
    private SourceReference sourceReference;

    AbstractGlueDefinition(Method method, Lookup lookup) {
        this.method = requireNonNull(method);
        this.lookup = requireNonNull(lookup);
    }

    @Override
    public boolean isDefinedAt(StackTraceElement e) {
        return e.getClassName().equals(method.getDeclaringClass().getName())
                && e.getMethodName().equals(method.getName());
    }

    @Override
    public final String getLocation() {
        return getFullLocationLocation();
    }

    private String getFullLocationLocation() {
        if (fullFormat == null) {
            fullFormat = MethodFormat.FULL.format(method);
        }
        return fullFormat;
    }

    public final Object invokeMethod(Object... args) {
        if (Modifier.isStatic(method.getModifiers())) {
            return Invoker.invokeStatic(this, method, args);
        }
        return Invoker.invoke(this, lookup.getInstance(method.getDeclaringClass()), method, args);
    }

    @Override
    public Optional<SourceReference> getSourceReference() {
        if (sourceReference == null) {
            sourceReference = SourceReference.fromMethod(this.method);
        }
        return Optional.of(sourceReference);
    }

}
