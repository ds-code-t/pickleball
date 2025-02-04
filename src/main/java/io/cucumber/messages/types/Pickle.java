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

import io.pickleball.cacheandstate.ScenarioContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.pickleball.mapandStateutilities.MappingFunctions.replaceNestedBrackets;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

/**
 * Represents the Pickle message in Cucumber's message protocol
 *
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 * <p>
 * //// Pickles
 * <p>
 * A `Pickle` represents a template for a `TestCase`. It is typically derived
 * from another format, such as [GherkinDocument](#io.cucumber.messages.GherkinDocument).
 * In the future a `Pickle` may be derived from other formats such as Markdown or
 * Excel files.
 * <p>
 * By making `Pickle` the main data structure Cucumber uses for execution, the
 * implementation of Cucumber itself becomes simpler, as it doesn't have to deal
 * with the complex structure of a [GherkinDocument](#io.cucumber.messages.GherkinDocument).
 * <p>
 * Each `PickleStep` of a `Pickle` is matched with a `StepDefinition` to create a `TestCase`
 */
// Generated code
@SuppressWarnings("unused")
public final class Pickle {
    private final String id;
    private final String uri;

    public void setName(ScenarioContext parent) {
        this.name = replaceNestedBrackets(originalName, parent.getPassedMap(), parent.getExamplesMap(), parent.getStateMap());
    }

    private String name;
    private final String originalName;
    private final String language;
    private final java.util.List<PickleStep> steps;
    private final java.util.List<PickleTag> tags;
    private final java.util.List<String> astNodeIds;

    public int getBackgroundStepsCount() {
        return backgroundStepsCount;
    }

    public List<TableCell> getHeaderRow() {
        return headerRow;
    }

    public TableRow getValueRow() {
        return valueRow;
    }

    private final int backgroundStepsCount;
    private final List<TableCell> headerRow;
    private final TableRow valueRow;


    public Pickle(
            String id,
            String uri,
            String name,
            String language,
            java.util.List<PickleStep> steps,
            java.util.List<PickleTag> tags,
            java.util.List<String> astNodeIds,
            int backgroundStepsCount,
            String originalName,
            List<TableCell> headerRow,
            TableRow valueRow
    ) {
        this.id = requireNonNull(id, "Pickle.id cannot be null");
        this.uri = requireNonNull(uri, "Pickle.uri cannot be null");
        this.name = requireNonNull(name, "Pickle.name cannot be null");
        this.language = requireNonNull(language, "Pickle.language cannot be null");
        this.steps = unmodifiableList(new ArrayList<>(requireNonNull(steps, "Pickle.steps cannot be null")));
        this.tags = unmodifiableList(new ArrayList<>(requireNonNull(tags, "Pickle.tags cannot be null")));
        this.astNodeIds = unmodifiableList(new ArrayList<>(requireNonNull(astNodeIds, "Pickle.astNodeIds cannot be null")));
        this.backgroundStepsCount = backgroundStepsCount;
        this.originalName = originalName;
        this.headerRow = headerRow;
        this.valueRow = valueRow;
    }

    /**
     * A unique id for the pickle
     */
    public String getId() {
        return id;
    }

    /**
     * The uri of the source file
     */
    public String getUri() {
        return uri;
    }

    /**
     * The name of the pickle
     */
    public String getName() {
        return name;
    }

    /**
     * The language of the pickle
     */
    public String getLanguage() {
        return language;
    }

    /**
     * One or more steps
     */
    public java.util.List<PickleStep> getSteps() {
        return steps;
    }

    /**
     * One or more tags. If this pickle is constructed from a Gherkin document,
     * It includes inherited tags from the `Feature` as well.
     */
    public java.util.List<PickleTag> getTags() {
        return tags;
    }

    /**
     * Points to the AST node locations of the pickle. The last one represents the unique
     * id of the pickle. A pickle constructed from `Examples` will have the first
     * id originating from the `Scenario` AST node, and the second from the `TableRow` AST node.
     */
    public java.util.List<String> getAstNodeIds() {
        return astNodeIds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pickle that = (Pickle) o;
        return
                id.equals(that.id) &&
                        uri.equals(that.uri) &&
                        name.equals(that.name) &&
                        language.equals(that.language) &&
                        steps.equals(that.steps) &&
                        tags.equals(that.tags) &&
                        astNodeIds.equals(that.astNodeIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id,
                uri,
                name,
                language,
                steps,
                tags,
                astNodeIds
        );
    }

    @Override
    public String toString() {
        return "Pickle{" +
                "id=" + id +
                ", uri=" + uri +
                ", name=" + name +
                ", language=" + language +
                ", steps=" + steps +
                ", tags=" + tags +
                ", astNodeIds=" + astNodeIds +
                '}';
    }
}
