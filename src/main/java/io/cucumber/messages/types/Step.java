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


import java.util.Objects;
import java.util.Optional;
import static java.util.Objects.requireNonNull;

/**
 * Represents the Step message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * A step
 */
// Generated code
@SuppressWarnings("unused")
public class Step {
    private final Location location;
    private final String keyword;
    private final StepKeywordType keywordType;
    private final String text;
    private final DocString docString;
    private final DataTable dataTable;
    private final String id;


    public Step(
            Location location,
            String keyword,
            StepKeywordType keywordType,
            String text,
            DocString docString,
            DataTable dataTable,
            String id
    ) {
        this.location = requireNonNull(location, "Step.location cannot be null");
        this.keyword = requireNonNull(keyword, "Step.keyword cannot be null");
        this.keywordType = keywordType;
        this.text = requireNonNull(text, "Step.text cannot be null");
        this.docString = docString;
        this.dataTable = dataTable;
        this.id = requireNonNull(id, "Step.id cannot be null");
    }

    /**
     * The location of the steps' `keyword`
     */
    public Location getLocation() {
        return location;
    }

    /**
     * The actual keyword as it appeared in the source.
     */
    public String getKeyword() {
        return keyword;
    }

    /**
     * The test phase signalled by the keyword: Context definition (Given), Action performance (When), Outcome assertion (Then). Other keywords signal Continuation (And and But) from a prior keyword. Please note that all translations which a dialect maps to multiple keywords (`*` is in this category for all dialects), map to 'Unknown'.
     */
    public Optional<StepKeywordType> getKeywordType() {
        return Optional.ofNullable(keywordType);
    }


    public String getText() {

        return text;
    }

    public Optional<DocString> getDocString() {
        return Optional.ofNullable(docString);
    }

    public Optional<DataTable> getDataTable() {
        return Optional.ofNullable(dataTable);
    }

    /**
     * Unique ID to be able to reference the Step from PickleStep
     */
    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Step that = (Step) o;
        return
                location.equals(that.location) &&
                        keyword.equals(that.keyword) &&
                        Objects.equals(keywordType, that.keywordType) &&
                        text.equals(that.text) &&
                        Objects.equals(docString, that.docString) &&
                        Objects.equals(dataTable, that.dataTable) &&
                        id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                location,
                keyword,
                keywordType,
                text,
                docString,
                dataTable,
                id
        );
    }

    @Override
    public String toString() {
        return "Step{" +
                "location=" + location +
                ", keyword=" + keyword +
                ", keywordType=" + keywordType +
                ", text=" + text +
                ", docString=" + docString +
                ", dataTable=" + dataTable +
                ", id=" + id +
                '}';
    }
}
