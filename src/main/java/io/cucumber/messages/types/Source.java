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
 * Represents the Source message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * //// Source
 *
 * A source file, typically a Gherkin document or Java/Ruby/JavaScript source code
 */
// Generated code
@SuppressWarnings("unused")
public final class Source {
    private final String uri;
    private final String data;
    private final SourceMediaType mediaType;

    public Source(
        String uri,
        String data,
        SourceMediaType mediaType
    ) {
        this.uri = requireNonNull(uri, "Source.uri cannot be null");
        this.data = requireNonNull(data, "Source.data cannot be null");
        this.mediaType = requireNonNull(mediaType, "Source.mediaType cannot be null");
    }

    /**
     * The [URI](https://en.wikipedia.org/wiki/Uniform_Resource_Identifier)
     * of the source, typically a file path relative to the root directory
     */
    public String getUri() {
        return uri;
    }

    /**
     * The contents of the file
     */
    public String getData() {
        return data;
    }

    /**
     * The media type of the file. Can be used to specify custom types, such as
     * text/x.cucumber.gherkin+plain
     */
    public SourceMediaType getMediaType() {
        return mediaType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Source that = (Source) o;
        return 
            uri.equals(that.uri) &&         
            data.equals(that.data) &&         
            mediaType.equals(that.mediaType);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            uri,
            data,
            mediaType
        );
    }

    @Override
    public String toString() {
        return "Source{" +
            "uri=" + uri +
            ", data=" + data +
            ", mediaType=" + mediaType +
            '}';
    }
}
