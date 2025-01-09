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

package io.cucumber.cucumberexpressions;

import org.apiguardian.api.API;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;

import java.util.ArrayList;
import java.util.Collection;

@API(status = API.Status.STABLE)
public class Group {
    private final List<Group> children;
    private final String value;
    private final int start;
    private final int end;

    Group(String value, int start, int end, List<Group> children) {
        this.value = value;
        this.start = start;
        this.end = end;
        this.children = children;
    }

    public String getValue() {
        return value;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public List<Group> getChildren() {
        return children;
    }

    public List<String> getValues() {
        List<Group> groups = getChildren().isEmpty() ? singletonList(this) : getChildren();
        return groups.stream()
                .map(Group::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Parse a {@link Pattern} into collection of {@link Group}s
     * 
     * @param expression the expression to decompose
     * @return A collection of {@link Group}s, possibly empty but never
     *         <code>null</code>
     */
    public static Collection<Group> parse(Pattern expression) {
        GroupBuilder builder = TreeRegexp.createGroupBuilder(expression);
        return toGroups(builder.getChildren());
    }

    private static List<Group> toGroups(List<GroupBuilder> children) {
        List<Group> list = new ArrayList<>();
        if (children != null) {
            for (GroupBuilder child : children) {
                list.add(new Group(child.getSource(), child.getStartIndex(), child.getEndIndex(),
                        toGroups(child.getChildren())));
            }
        }
        return list;
    }
}
