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
 * Represents the StepDefinition message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class StepDefinition {
    private final String id;
    private final StepDefinitionPattern pattern;
    private final SourceReference sourceReference;

    public StepDefinition(
        String id,
        StepDefinitionPattern pattern,
        SourceReference sourceReference
    ) {
        this.id = requireNonNull(id, "StepDefinition.id cannot be null");
        this.pattern = requireNonNull(pattern, "StepDefinition.pattern cannot be null");
        this.sourceReference = requireNonNull(sourceReference, "StepDefinition.sourceReference cannot be null");
    }

    public String getId() {
        return id;
    }

    public StepDefinitionPattern getPattern() {
        return pattern;
    }

    public SourceReference getSourceReference() {
        return sourceReference;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StepDefinition that = (StepDefinition) o;
        return 
            id.equals(that.id) &&         
            pattern.equals(that.pattern) &&         
            sourceReference.equals(that.sourceReference);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            pattern,
            sourceReference
        );
    }

    @Override
    public String toString() {
        return "StepDefinition{" +
            "id=" + id +
            ", pattern=" + pattern +
            ", sourceReference=" + sourceReference +
            '}';
    }
}
