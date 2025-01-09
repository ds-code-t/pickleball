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
 * Represents the RuleChild message in Cucumber's message protocol
 * @see <a href=https://github.com/cucumber/messages>Github - Cucumber - Messages</a>
 *
 * A child node of a `Rule` node
 */
// Generated code
@SuppressWarnings("unused")
public final class RuleChild {
    private final Background background;
    private final Scenario scenario;

    public static RuleChild of(Background background) {
        return new RuleChild(
            requireNonNull(background, "RuleChild.background cannot be null"),
            null
        );
    }

    public static RuleChild of(Scenario scenario) {
        return new RuleChild(
            null,
            requireNonNull(scenario, "RuleChild.scenario cannot be null")
        );
    }

    public RuleChild(
        Background background,
        Scenario scenario
    ) {
        this.background = background;
        this.scenario = scenario;
    }

    public Optional<Background> getBackground() {
        return Optional.ofNullable(background);
    }

    public Optional<Scenario> getScenario() {
        return Optional.ofNullable(scenario);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RuleChild that = (RuleChild) o;
        return 
            Objects.equals(background, that.background) &&         
            Objects.equals(scenario, that.scenario);        
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            background,
            scenario
        );
    }

    @Override
    public String toString() {
        return "RuleChild{" +
            "background=" + background +
            ", scenario=" + scenario +
            '}';
    }
}
