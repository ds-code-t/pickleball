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
 * Represents the PickleTag message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * A tag
 */
// Generated code
@SuppressWarnings("unused")
public final class PickleTag {
    private final String name;
    private final String astNodeId;

    public PickleTag(
        String name,
        String astNodeId
    ) {
        this.name = requireNonNull(name, "PickleTag.name cannot be null");
        this.astNodeId = requireNonNull(astNodeId, "PickleTag.astNodeId cannot be null");
    }

    public String getName() {
        return name;
    }

    /**
     * Points to the AST node this was created from
     */
    public String getAstNodeId() {
        return astNodeId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PickleTag that = (PickleTag) o;
        return 
            name.equals(that.name) &&         
            astNodeId.equals(that.astNodeId);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            name,
            astNodeId
        );
    }

    @Override
    public String toString() {
        return "PickleTag{" +
            "name=" + name +
            ", astNodeId=" + astNodeId +
            '}';
    }
}
