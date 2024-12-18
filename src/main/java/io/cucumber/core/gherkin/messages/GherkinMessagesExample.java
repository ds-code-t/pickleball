package io.cucumber.core.gherkin.messages;

import io.cucumber.messages.types.TableRow;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.Node;
import io.pickleball.MapAndStateUtilities.LinkedMultiMap;

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
//        try {
//            scenarioOutlineLine1 = ( (GherkinMessagesScenarioOutline) parent.getParent().get().getParent().get()).getLocation().getLine();;
//        } catch (Exception e) {
//            scenarioOutlineLine1 = 0;
//        }
//        scenarioOutlineLine = scenarioOutlineLine1;
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
