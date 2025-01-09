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
 * Represents the GherkinDocument message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * The [AST](https://en.wikipedia.org/wiki/Abstract_syntax_tree) of a Gherkin document.
 * Cucumber implementations should *not* depend on `GherkinDocument` or any of its
 * children for execution - use [Pickle](#io.cucumber.messages.Pickle) instead.
 *
 * The only consumers of `GherkinDocument` should only be formatters that produce
 * "rich" output, resembling the original Gherkin document.
 */
// Generated code
@SuppressWarnings("unused")
public final class GherkinDocument {
    private final String uri;
    private final Feature feature;
    private final java.util.List<Comment> comments;

    public GherkinDocument(
        String uri,
        Feature feature,
        java.util.List<Comment> comments
    ) {
        this.uri = uri;
        this.feature = feature;
        this.comments = unmodifiableList(new ArrayList<>(requireNonNull(comments, "GherkinDocument.comments cannot be null")));
    }

    /**
      * The [URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier)
     * of the source, typically a file path relative to the root directory
     */
    public Optional<String> getUri() {
        return Optional.ofNullable(uri);
    }

    public Optional<Feature> getFeature() {
        return Optional.ofNullable(feature);
    }

    /**
     * All the comments in the Gherkin document
     */
    public java.util.List<Comment> getComments() {
        return comments;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GherkinDocument that = (GherkinDocument) o;
        return 
            Objects.equals(uri, that.uri) &&         
            Objects.equals(feature, that.feature) &&         
            comments.equals(that.comments);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            uri,
            feature,
            comments
        );
    }

    @Override
    public String toString() {
        return "GherkinDocument{" +
            "uri=" + uri +
            ", feature=" + feature +
            ", comments=" + comments +
            '}';
    }
}
