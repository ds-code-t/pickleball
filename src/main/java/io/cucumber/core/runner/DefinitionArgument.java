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

package io.cucumber.core.runner;

import io.cucumber.core.stepexpression.ExpressionArgument;
import io.cucumber.plugin.event.Argument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

final class DefinitionArgument implements Argument {

    private final ExpressionArgument argument;
    private final io.cucumber.cucumberexpressions.Group group;

    private DefinitionArgument(ExpressionArgument argument) {
        this.argument = argument;
        this.group = argument.getGroup();
    }

    static List<Argument> createArguments(List<io.cucumber.core.stepexpression.Argument> match) {
        List<Argument> args = new ArrayList<>();
        for (io.cucumber.core.stepexpression.Argument argument : match) {
            if (argument instanceof ExpressionArgument) {
                ExpressionArgument expressionArgument = (ExpressionArgument) argument;
                args.add(new DefinitionArgument(expressionArgument));
            }
        }
        return args;
    }

    @Override
    public String getParameterTypeName() {
        return argument.getParameterTypeName();
    }

    @Override
    public String getValue() {
        return group == null ? null : group.getValue();
    }

    @Override
    public int getStart() {
        return group == null ? -1 : group.getStart();
    }

    @Override
    public int getEnd() {
        return group == null ? -1 : group.getEnd();
    }

    @Override
    public io.cucumber.plugin.event.Group getGroup() {
        return group == null ? null : new Group(group);
    }

    private static final class Group implements io.cucumber.plugin.event.Group {

        private final io.cucumber.cucumberexpressions.Group group;
        private final List<io.cucumber.plugin.event.Group> children;

        private Group(io.cucumber.cucumberexpressions.Group group) {
            this.group = group;
            children = group.getChildren().stream()
                    .map(Group::new)
                    .collect(Collectors.toList());
        }

        @Override
        public Collection<io.cucumber.plugin.event.Group> getChildren() {
            return children;
        }

        @Override
        public String getValue() {
            return group.getValue();
        }

        @Override
        public int getStart() {
            return group.getStart();
        }

        @Override
        public int getEnd() {
            return group.getEnd();
        }

    }

}
