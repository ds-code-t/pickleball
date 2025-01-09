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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;

final class GroupBuilder {
    private final List<GroupBuilder> groupBuilders = new ArrayList<>();
    private boolean capturing = true;
    private String source;
    private int startIndex;
    private int endIndex;

    GroupBuilder(int startIndex) {
        this.startIndex = startIndex;
    }

    void add(GroupBuilder groupBuilder) {
        groupBuilders.add(groupBuilder);
    }

    Group build(Matcher matcher, Iterator<Integer> groupIndices) {
        int groupIndex = groupIndices.next();
        List<Group> children = new ArrayList<>(groupBuilders.size());
        for (GroupBuilder childGroupBuilder : groupBuilders) {
            children.add(childGroupBuilder.build(matcher, groupIndices));
        }
        return new Group(matcher.group(groupIndex), matcher.start(groupIndex), matcher.end(groupIndex), children);
    }

    void setNonCapturing() {
        this.capturing = false;
    }

    boolean isCapturing() {
        return capturing;
    }

    void moveChildrenTo(GroupBuilder groupBuilder) {
        for (GroupBuilder child : groupBuilders) {
            groupBuilder.add(child);
        }
    }

    List<GroupBuilder> getChildren() {
        return groupBuilders;
    }

    String getSource() {
        return source;
    }

    void setSource(String source) {
        this.source = source;
    }

    int getStartIndex() {
        return startIndex;
    }

    int getEndIndex() {
        return endIndex;
    }

    void setEndIndex(int endIndex) {
        this.endIndex = endIndex;
    }
}
