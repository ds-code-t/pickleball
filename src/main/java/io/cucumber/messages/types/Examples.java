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
import java.util.stream.IntStream;

import static io.pickleball.stringutilities.Constants.SCENARIO_TAGS;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Represents the Examples message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 */
// Generated code
@SuppressWarnings("unused")
public final class Examples {
    private final Location location;
    private final java.util.List<Tag> tags;
    private final String keyword;
    private final String name;
    private final String description;
    private final TableRow tableHeader;
    private final java.util.List<TableRow> tableBody;
    private final String id;
    public final int scenarioTagsIndex;

    public Examples(
        Location location,
        java.util.List<Tag> tags,
        String keyword,
        String name,
        String description,
        TableRow tableHeader,
        java.util.List<TableRow> tableBody,
        String id
    ) {
        this.location = requireNonNull(location, "Examples.location cannot be null");
        this.tags = unmodifiableList(new ArrayList<>(requireNonNull(tags, "Examples.tags cannot be null")));
        this.keyword = requireNonNull(keyword, "Examples.keyword cannot be null");
        this.name = requireNonNull(name, "Examples.name cannot be null");
        this.description = requireNonNull(description, "Examples.description cannot be null");
        this.tableHeader = tableHeader;
        this.tableBody = unmodifiableList(new ArrayList<>(requireNonNull(tableBody, "Examples.tableBody cannot be null")));
        this.id = requireNonNull(id, "Examples.id cannot be null");
        this.scenarioTagsIndex =  IntStream.range(0, tableHeader.getCells().size())
                .filter(i -> tableHeader.getCells().get(i).getValue().trim().equals(SCENARIO_TAGS))
                .findFirst()
                .orElse(-1);
    }

    /**
     * The location of the `Examples` keyword
     */
    public Location getLocation() {
        return location;
    }

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

    public Optional<TableRow> getTableHeader() {
        return Optional.ofNullable(tableHeader);
    }

    public java.util.List<TableRow> getTableBody() {
        return tableBody;
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Examples that = (Examples) o;
        return 
            location.equals(that.location) &&         
            tags.equals(that.tags) &&         
            keyword.equals(that.keyword) &&         
            name.equals(that.name) &&         
            description.equals(that.description) &&         
            Objects.equals(tableHeader, that.tableHeader) &&         
            tableBody.equals(that.tableBody) &&         
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
            tableHeader,
            tableBody,
            id
        );
    }

    @Override
    public String toString() {
        return "Examples{" +
            "location=" + location +
            ", tags=" + tags +
            ", keyword=" + keyword +
            ", name=" + name +
            ", description=" + description +
            ", tableHeader=" + tableHeader +
            ", tableBody=" + tableBody +
            ", id=" + id +
            '}';
    }
}
