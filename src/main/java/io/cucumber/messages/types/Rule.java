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
 * Represents the Rule message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class Rule {
    private final Location location;
    private final java.util.List<Tag> tags;
    private final String keyword;
    private final String name;
    private final String description;
    private final java.util.List<RuleChild> children;
    private final String id;

    public Rule(
        Location location,
        java.util.List<Tag> tags,
        String keyword,
        String name,
        String description,
        java.util.List<RuleChild> children,
        String id
    ) {
        this.location = requireNonNull(location, "Rule.location cannot be null");
        this.tags = unmodifiableList(new ArrayList<>(requireNonNull(tags, "Rule.tags cannot be null")));
        this.keyword = requireNonNull(keyword, "Rule.keyword cannot be null");
        this.name = requireNonNull(name, "Rule.name cannot be null");
        this.description = requireNonNull(description, "Rule.description cannot be null");
        this.children = unmodifiableList(new ArrayList<>(requireNonNull(children, "Rule.children cannot be null")));
        this.id = requireNonNull(id, "Rule.id cannot be null");
    }

    /**
     * The location of the `Rule` keyword
     */
    public Location getLocation() {
        return location;
    }

    /**
     * All the tags placed above the `Rule` keyword
     */
    public java.util.List<Tag> getTags() {
        return tags;
    }

    public String getKeyword() {
        return keyword;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public java.util.List<RuleChild> getChildren() {
        return children;
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rule that = (Rule) o;
        return 
            location.equals(that.location) &&         
            tags.equals(that.tags) &&         
            keyword.equals(that.keyword) &&         
            name.equals(that.name) &&         
            description.equals(that.description) &&         
            children.equals(that.children) &&         
            id.equals(that.id);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            location,
            tags,
            keyword,
            name,
            description,
            children,
            id
        );
    }

    @Override
    public String toString() {
        return "Rule{" +
            "location=" + location +
            ", tags=" + tags +
            ", keyword=" + keyword +
            ", name=" + name +
            ", description=" + description +
            ", children=" + children +
            ", id=" + id +
            '}';
    }
}
