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
 * Represents the SourceReference message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * Points to a [Source](#io.cucumber.messages.Source) identified by `uri` and a
 * [Location](#io.cucumber.messages.Location) within that file.
 */
// Generated code
@SuppressWarnings("unused")
public final class SourceReference {
    private final String uri;
    private final JavaMethod javaMethod;
    private final JavaStackTraceElement javaStackTraceElement;
    private final Location location;

    public static SourceReference of(String uri) {
        return new SourceReference(
            requireNonNull(uri, "SourceReference.uri cannot be null"),
            null,
            null,
            null
        );
    }

    public static SourceReference of(JavaMethod javaMethod) {
        return new SourceReference(
            null,
            requireNonNull(javaMethod, "SourceReference.javaMethod cannot be null"),
            null,
            null
        );
    }

    public static SourceReference of(JavaStackTraceElement javaStackTraceElement) {
        return new SourceReference(
            null,
            null,
            requireNonNull(javaStackTraceElement, "SourceReference.javaStackTraceElement cannot be null"),
            null
        );
    }

    public static SourceReference of(Location location) {
        return new SourceReference(
            null,
            null,
            null,
            requireNonNull(location, "SourceReference.location cannot be null")
        );
    }

    public SourceReference(
        String uri,
        JavaMethod javaMethod,
        JavaStackTraceElement javaStackTraceElement,
        Location location
    ) {
        this.uri = uri;
        this.javaMethod = javaMethod;
        this.javaStackTraceElement = javaStackTraceElement;
        this.location = location;
    }

    public Optional<String> getUri() {
        return Optional.ofNullable(uri);
    }

    public Optional<JavaMethod> getJavaMethod() {
        return Optional.ofNullable(javaMethod);
    }

    public Optional<JavaStackTraceElement> getJavaStackTraceElement() {
        return Optional.ofNullable(javaStackTraceElement);
    }

    public Optional<Location> getLocation() {
        return Optional.ofNullable(location);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceReference that = (SourceReference) o;
        return 
            Objects.equals(uri, that.uri) &&         
            Objects.equals(javaMethod, that.javaMethod) &&         
            Objects.equals(javaStackTraceElement, that.javaStackTraceElement) &&         
            Objects.equals(location, that.location);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            uri,
            javaMethod,
            javaStackTraceElement,
            location
        );
    }

    @Override
    public String toString() {
        return "SourceReference{" +
            "uri=" + uri +
            ", javaMethod=" + javaMethod +
            ", javaStackTraceElement=" + javaStackTraceElement +
            ", location=" + location +
            '}';
    }
}
