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

import io.cucumber.core.backend.CucumberBackendException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

final class InvalidMethodSignatureException extends CucumberBackendException {

    private InvalidMethodSignatureException(String message) {
        super(message);
    }

    static InvalidMethodSignatureExceptionBuilder builder(Method method) {
        return new InvalidMethodSignatureExceptionBuilder(method);
    }

    static class InvalidMethodSignatureExceptionBuilder {

        private final Method method;
        private final List<Class<?>> annotations = new ArrayList<>();
        private final List<String> signatures = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        private InvalidMethodSignatureExceptionBuilder(Method method) {
            this.method = requireNonNull(method);
        }

        InvalidMethodSignatureExceptionBuilder addAnnotation(Class<?> annotation) {
            annotations.add(annotation);
            return this;
        }

        InvalidMethodSignatureExceptionBuilder addSignature(String signature) {
            signatures.add(signature);
            return this;
        }

        InvalidMethodSignatureExceptionBuilder addNote(String note) {
            this.notes.add(note);
            return this;
        }

        public InvalidMethodSignatureException build() {
            return new InvalidMethodSignatureException("" +
                    describeAnnotations() + " must have one of these signatures:\n" +
                    " * " + describeAvailableSignature() + "\n" +
                    "at " + describeLocation() + "\n" +
                    describeNote() + "\n");
        }

        private String describeAnnotations() {
            if (annotations.size() == 1) {
                return "A @" + annotations.get(0).getSimpleName() + " annotated method";
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < annotations.size(); i++) {
                builder.append(annotations.get(i).getSimpleName());

                if (i < annotations.size() - 2) {
                    builder.append(", ");
                } else if (i < annotations.size() - 1) {
                    builder.append(" or ");
                }
            }

            return "A method annotated with " + builder.toString();
        }

        private String describeAvailableSignature() {
            return String.join("\n * ", signatures);
        }

        private Object describeLocation() {
            return MethodFormat.FULL.format(method);
        }

        private String describeNote() {
            return String.join("\n", notes);
        }

    }

}
