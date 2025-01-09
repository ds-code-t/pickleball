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
 * Represents the ParameterType message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class ParameterType {
    private final String name;
    private final java.util.List<String> regularExpressions;
    private final Boolean preferForRegularExpressionMatch;
    private final Boolean useForSnippets;
    private final String id;
    private final SourceReference sourceReference;

    public ParameterType(
        String name,
        java.util.List<String> regularExpressions,
        Boolean preferForRegularExpressionMatch,
        Boolean useForSnippets,
        String id,
        SourceReference sourceReference
    ) {
        this.name = requireNonNull(name, "ParameterType.name cannot be null");
        this.regularExpressions = unmodifiableList(new ArrayList<>(requireNonNull(regularExpressions, "ParameterType.regularExpressions cannot be null")));
        this.preferForRegularExpressionMatch = requireNonNull(preferForRegularExpressionMatch, "ParameterType.preferForRegularExpressionMatch cannot be null");
        this.useForSnippets = requireNonNull(useForSnippets, "ParameterType.useForSnippets cannot be null");
        this.id = requireNonNull(id, "ParameterType.id cannot be null");
        this.sourceReference = sourceReference;
    }

    /**
     * The name is unique, so we don't need an id.
     */
    public String getName() {
        return name;
    }

    public java.util.List<String> getRegularExpressions() {
        return regularExpressions;
    }

    public Boolean getPreferForRegularExpressionMatch() {
        return preferForRegularExpressionMatch;
    }

    public Boolean getUseForSnippets() {
        return useForSnippets;
    }

    public String getId() {
        return id;
    }

    public Optional<SourceReference> getSourceReference() {
        return Optional.ofNullable(sourceReference);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterType that = (ParameterType) o;
        return 
            name.equals(that.name) &&         
            regularExpressions.equals(that.regularExpressions) &&         
            preferForRegularExpressionMatch.equals(that.preferForRegularExpressionMatch) &&         
            useForSnippets.equals(that.useForSnippets) &&         
            id.equals(that.id) &&         
            Objects.equals(sourceReference, that.sourceReference);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            name,
            regularExpressions,
            preferForRegularExpressionMatch,
            useForSnippets,
            id,
            sourceReference
        );
    }

    @Override
    public String toString() {
        return "ParameterType{" +
            "name=" + name +
            ", regularExpressions=" + regularExpressions +
            ", preferForRegularExpressionMatch=" + preferForRegularExpressionMatch +
            ", useForSnippets=" + useForSnippets +
            ", id=" + id +
            ", sourceReference=" + sourceReference +
            '}';
    }
}
