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
 * Represents the Hook message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class Hook {
    private final String id;
    private final String name;
    private final SourceReference sourceReference;
    private final String tagExpression;

    public Hook(
        String id,
        String name,
        SourceReference sourceReference,
        String tagExpression
    ) {
        this.id = requireNonNull(id, "Hook.id cannot be null");
        this.name = name;
        this.sourceReference = requireNonNull(sourceReference, "Hook.sourceReference cannot be null");
        this.tagExpression = tagExpression;
    }

    public String getId() {
        return id;
    }

    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    public SourceReference getSourceReference() {
        return sourceReference;
    }

    public Optional<String> getTagExpression() {
        return Optional.ofNullable(tagExpression);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Hook that = (Hook) o;
        return 
            id.equals(that.id) &&         
            Objects.equals(name, that.name) &&         
            sourceReference.equals(that.sourceReference) &&         
            Objects.equals(tagExpression, that.tagExpression);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            name,
            sourceReference,
            tagExpression
        );
    }

    @Override
    public String toString() {
        return "Hook{" +
            "id=" + id +
            ", name=" + name +
            ", sourceReference=" + sourceReference +
            ", tagExpression=" + tagExpression +
            '}';
    }
}
