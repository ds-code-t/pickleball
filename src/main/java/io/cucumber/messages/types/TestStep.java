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
 * Represents the TestStep message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * A `TestStep` is derived from either a `PickleStep`
 * combined with a `StepDefinition`, or from a `Hook`.
 */
// Generated code
@SuppressWarnings("unused")
public final class TestStep {
    private final String hookId;
    private final String id;
    private final String pickleStepId;
    private final java.util.List<String> stepDefinitionIds;
    private final java.util.List<StepMatchArgumentsList> stepMatchArgumentsLists;

    public TestStep(
        String hookId,
        String id,
        String pickleStepId,
        java.util.List<String> stepDefinitionIds,
        java.util.List<StepMatchArgumentsList> stepMatchArgumentsLists
    ) {
        this.hookId = hookId;
        this.id = requireNonNull(id, "TestStep.id cannot be null");
        this.pickleStepId = pickleStepId;
        this.stepDefinitionIds = stepDefinitionIds == null ? null : unmodifiableList(new ArrayList<>(stepDefinitionIds));
        this.stepMatchArgumentsLists = stepMatchArgumentsLists == null ? null : unmodifiableList(new ArrayList<>(stepMatchArgumentsLists));
    }

    /**
      * Pointer to the `Hook` (if derived from a Hook)
     */
    public Optional<String> getHookId() {
        return Optional.ofNullable(hookId);
    }

    public String getId() {
        return id;
    }

    /**
      * Pointer to the `PickleStep` (if derived from a `PickleStep`)
     */
    public Optional<String> getPickleStepId() {
        return Optional.ofNullable(pickleStepId);
    }

    /**
      * Pointer to all the matching `StepDefinition`s (if derived from a `PickleStep`)
     */
    public Optional<java.util.List<String>> getStepDefinitionIds() {
        return Optional.ofNullable(stepDefinitionIds);
    }

    /**
      * A list of list of StepMatchArgument (if derived from a `PickleStep`).
     * Each element represents a matching step definition. A size of 0 means `UNDEFINED`,
     * and a size of 2+ means `AMBIGUOUS`
     */
    public Optional<java.util.List<StepMatchArgumentsList>> getStepMatchArgumentsLists() {
        return Optional.ofNullable(stepMatchArgumentsLists);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestStep that = (TestStep) o;
        return 
            Objects.equals(hookId, that.hookId) &&         
            id.equals(that.id) &&         
            Objects.equals(pickleStepId, that.pickleStepId) &&         
            Objects.equals(stepDefinitionIds, that.stepDefinitionIds) &&         
            Objects.equals(stepMatchArgumentsLists, that.stepMatchArgumentsLists);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            hookId,
            id,
            pickleStepId,
            stepDefinitionIds,
            stepMatchArgumentsLists
        );
    }

    @Override
    public String toString() {
        return "TestStep{" +
            "hookId=" + hookId +
            ", id=" + id +
            ", pickleStepId=" + pickleStepId +
            ", stepDefinitionIds=" + stepDefinitionIds +
            ", stepMatchArgumentsLists=" + stepMatchArgumentsLists +
            '}';
    }
}
