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

package io.cucumber.core.backend;

import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class StackTraceElementReference implements SourceReference {

    private final String className;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;

    StackTraceElementReference(String className, String methodName, String fileName, int lineNumber) {
        this.className = requireNonNull(className);
        this.methodName = requireNonNull(methodName);
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public Optional<String> fileName() {
        return Optional.ofNullable(fileName);
    }

    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        StackTraceElementReference that = (StackTraceElementReference) o;
        return lineNumber == that.lineNumber &&
                className.equals(that.className) &&
                methodName.equals(that.methodName) &&
                Objects.equals(fileName, that.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, methodName, fileName, lineNumber);
    }

}
