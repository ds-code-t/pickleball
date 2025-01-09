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
 * Represents the PickleStepArgument message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * An optional argument
 */
// Generated code
@SuppressWarnings("unused")
public final class PickleStepArgument {
    private final PickleDocString docString;
    private final PickleTable dataTable;

    public static PickleStepArgument of(PickleDocString docString) {
        return new PickleStepArgument(
            requireNonNull(docString, "PickleStepArgument.docString cannot be null"),
            null
        );
    }

    public static PickleStepArgument of(PickleTable dataTable) {
        return new PickleStepArgument(
            null,
            requireNonNull(dataTable, "PickleStepArgument.dataTable cannot be null")
        );
    }

    public PickleStepArgument(
        PickleDocString docString,
        PickleTable dataTable
    ) {
        this.docString = docString;
        this.dataTable = dataTable;
    }

    public Optional<PickleDocString> getDocString() {
        return Optional.ofNullable(docString);
    }

    public Optional<PickleTable> getDataTable() {
        return Optional.ofNullable(dataTable);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PickleStepArgument that = (PickleStepArgument) o;
        return 
            Objects.equals(docString, that.docString) &&         
            Objects.equals(dataTable, that.dataTable);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            docString,
            dataTable
        );
    }

    @Override
    public String toString() {
        return "PickleStepArgument{" +
            "docString=" + docString +
            ", dataTable=" + dataTable +
            '}';
    }
}
