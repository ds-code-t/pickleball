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
 * Represents the TestCase message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * //// TestCases
 *
 * A `TestCase` contains a sequence of `TestStep`s.
 */
// Generated code
@SuppressWarnings("unused")
public final class TestCase {
    private final String id;
    private final String pickleId;
    private final java.util.List<TestStep> testSteps;

    public TestCase(
        String id,
        String pickleId,
        java.util.List<TestStep> testSteps
    ) {
        this.id = requireNonNull(id, "TestCase.id cannot be null");
        this.pickleId = requireNonNull(pickleId, "TestCase.pickleId cannot be null");
        this.testSteps = unmodifiableList(new ArrayList<>(requireNonNull(testSteps, "TestCase.testSteps cannot be null")));
    }

    public String getId() {
        return id;
    }

    /**
     * The ID of the `Pickle` this `TestCase` is derived from.
     */
    public String getPickleId() {
        return pickleId;
    }

    public java.util.List<TestStep> getTestSteps() {
        return testSteps;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestCase that = (TestCase) o;
        return 
            id.equals(that.id) &&         
            pickleId.equals(that.pickleId) &&         
            testSteps.equals(that.testSteps);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            id,
            pickleId,
            testSteps
        );
    }

    @Override
    public String toString() {
        return "TestCase{" +
            "id=" + id +
            ", pickleId=" + pickleId +
            ", testSteps=" + testSteps +
            '}';
    }
}
