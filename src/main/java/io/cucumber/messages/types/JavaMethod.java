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

package io.cucumber.messages.types;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Represents the JavaMethod message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class JavaMethod {
    private final String className;
    private final String methodName;
    private final java.util.List<String> methodParameterTypes;

    public JavaMethod(
        String className,
        String methodName,
        java.util.List<String> methodParameterTypes
    ) {
        this.className = requireNonNull(className, "JavaMethod.className cannot be null");
        this.methodName = requireNonNull(methodName, "JavaMethod.methodName cannot be null");
        this.methodParameterTypes = unmodifiableList(new ArrayList<>(requireNonNull(methodParameterTypes, "JavaMethod.methodParameterTypes cannot be null")));
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public java.util.List<String> getMethodParameterTypes() {
        return methodParameterTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JavaMethod that = (JavaMethod) o;
        return 
            className.equals(that.className) &&         
            methodName.equals(that.methodName) &&         
            methodParameterTypes.equals(that.methodParameterTypes);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            className,
            methodName,
            methodParameterTypes
        );
    }

    @Override
    public String toString() {
        return "JavaMethod{" +
            "className=" + className +
            ", methodName=" + methodName +
            ", methodParameterTypes=" + methodParameterTypes +
            '}';
    }
}
