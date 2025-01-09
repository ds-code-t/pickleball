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

package io.cucumber.core.gherkin.messages;

import io.cucumber.messages.types.RuleChild;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Node;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

final class GherkinMessagesRule implements Node.Rule {

    private final Node parent;
    private final io.cucumber.messages.types.Rule rule;
    private final List<Node> children;

    GherkinMessagesRule(Node parent, io.cucumber.messages.types.Rule rule) {
        this.parent = parent;
        this.rule = rule;
        this.children = rule.getChildren().stream()
                .map(RuleChild::getScenario)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(scenario -> {
                    if (!scenario.getExamples().isEmpty()) {
                        return new GherkinMessagesScenarioOutline(this, scenario);
                    } else {
                        return new GherkinMessagesScenario(this, scenario);
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Node> getParent() {
        return Optional.of(parent);
    }

    @Override
    public Collection<Node> elements() {
        return children;
    }

    @Override
    public Location getLocation() {
        return GherkinMessagesLocation.from(rule.getLocation());
    }

    @Override
    public Optional<String> getKeyword() {
        return Optional.of(rule.getKeyword());
    }

    @Override
    public Optional<String> getName() {
        String name = rule.getName();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

}
