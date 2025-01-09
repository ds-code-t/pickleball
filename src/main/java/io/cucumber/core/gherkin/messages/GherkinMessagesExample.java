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

import io.cucumber.messages.types.TableRow;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Node;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import java.util.Optional;

import static io.pickleball.cucumberutilities.FeatureFileUtilities.getComponentByLine;

public final class GherkinMessagesExample implements Node.Example {
    private final TableRow tableRow;
    private final int examplesIndex;
    private final int rowIndex;
    private final Node parent;
//    private final int scenarioOutlineLine;

    public TableRow getTableRow() {
        return tableRow;
    }

    public int getExamplesIndex() {
        return examplesIndex;
    }

    public int getRowIndex() {
        return rowIndex;
    }


    public GherkinMessagesExample(Node parent, TableRow tableRow, int examplesIndex, int rowIndex) {
        int scenarioOutlineLine1;
        this.parent = parent;
        this.tableRow = tableRow;
        this.examplesIndex = examplesIndex;
        this.rowIndex = rowIndex;
    }

    public GherkinMessagesScenarioOutline getGherkinMessagesScenarioOutline(GherkinMessagesFeature feature){
        try {
            return (GherkinMessagesScenarioOutline) getComponentByLine(feature, ((GherkinMessagesScenarioOutline) parent.getParent().get()).getLocation().getLine());
        } catch (Exception e) {
           return null;
        }
    }

    public LinkedMultiMap<String, String> getLinkedMultiMap() {
        if (tableRow == null || tableRow.getCells().isEmpty())
            new LinkedMultiMap<>();
        return new LinkedMultiMap<>(((GherkinMessagesExamples) parent).getExamples().getTableHeader().get().getCells().stream().map(c -> c.getValue().trim()).toList(), tableRow.getCells().stream().map(c -> c.getValue().trim()).toList());
    }

    @Override
    public Location getLocation() {
        return GherkinMessagesLocation.from(tableRow.getLocation());
    }

    @Override
    public Optional<String> getKeyword() {
        return Optional.empty();
    }

    @Override
    public Optional<String> getName() {
        return Optional.of("Example #" + examplesIndex + "." + rowIndex);
    }

    @Override
    public Optional<Node> getParent() {
        return Optional.of(parent);
    }

}
