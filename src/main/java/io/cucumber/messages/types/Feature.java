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
 * Represents the Feature message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class Feature {
    private final Location location;
    private final java.util.List<Tag> tags;
    private final String language;
    private final String keyword;
    private final String name;
    private final String description;
    private final java.util.List<FeatureChild> children;

    public Feature(
        Location location,
        java.util.List<Tag> tags,
        String language,
        String keyword,
        String name,
        String description,
        java.util.List<FeatureChild> children
    ) {
        this.location = requireNonNull(location, "Feature.location cannot be null");
        this.tags = unmodifiableList(new ArrayList<>(requireNonNull(tags, "Feature.tags cannot be null")));
        this.language = requireNonNull(language, "Feature.language cannot be null");
        this.keyword = requireNonNull(keyword, "Feature.keyword cannot be null");
        this.name = requireNonNull(name, "Feature.name cannot be null");
        this.description = requireNonNull(description, "Feature.description cannot be null");
        this.children = unmodifiableList(new ArrayList<>(requireNonNull(children, "Feature.children cannot be null")));
    }

    /**
     * The location of the `Feature` keyword
     */
    public Location getLocation() {
        return location;
    }

    /**
     * All the tags placed above the `Feature` keyword
     */
    public java.util.List<Tag> getTags() {
        return tags;
    }

    /**
     * The [ISO 639-1](https://en.wikipedia.org/wiki/ISO_639-1) language code of the Gherkin document
     */
    public String getLanguage() {
        return language;
    }

    /**
     * The text of the `Feature` keyword (in the language specified by `language`)
     */
    public String getKeyword() {
        return keyword;
    }

    /**
     * The name of the feature (the text following the `keyword`)
     */
    public String getName() {
        return name;
    }

    /**
     * The line(s) underneath the line with the `keyword` that are used as description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Zero or more children
     */
    public java.util.List<FeatureChild> getChildren() {
        return children;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Feature that = (Feature) o;
        return 
            location.equals(that.location) &&         
            tags.equals(that.tags) &&         
            language.equals(that.language) &&         
            keyword.equals(that.keyword) &&         
            name.equals(that.name) &&         
            description.equals(that.description) &&         
            children.equals(that.children);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            location,
            tags,
            language,
            keyword,
            name,
            description,
            children
        );
    }

    @Override
    public String toString() {
        return "Feature{" +
            "location=" + location +
            ", tags=" + tags +
            ", language=" + language +
            ", keyword=" + keyword +
            ", name=" + name +
            ", description=" + description +
            ", children=" + children +
            '}';
    }
}
