package tools.dscode.engine.convert;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.datatable.DataTable;
import org.junit.jupiter.api.Test;
import tools.dscode.common.assertions.NeutralAssertions;
import tools.dscode.common.dataelements.TabularDataAdapter;
import tools.dscode.common.dataelements.TabularMatrix;
import tools.dscode.common.dataoperations.NeutralRows;
import tools.dscode.engine.model.DocText;
import tools.dscode.engine.model.Table;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NeutralDataTest {
    @Test
    void mappingsAndDataElementsUseNeutralTablesWithoutChangingDataTableConversion() {
        Table table = new Table(List.of(List.of("Name", "N"), List.of("Ada", "1")));
        DataTable dataTable = CucumberModelConverter.toDataTable(table);
        assertEquals(table.rows(), CucumberModelConverter.toTable(dataTable).rows());
        NeutralAssertions.sameTable(CucumberModelConverter.toTable(dataTable), table);
        assertEquals(List.of("Name", "N"), NeutralRows.headers(table));
        assertEquals("Ada", NeutralRows.asMaps(table).getFirst().get("Name"));
        JsonNode json = NeutralRows.toJson(table);
        assertEquals("Ada", json.get("Name").asText());
        TabularMatrix neutral = TabularDataAdapter.adapt(table);
        TabularMatrix cucumber = TabularDataAdapter.adapt(dataTable);
        assertEquals(neutral.physicalValues(0), cucumber.physicalValues(0));
        assertEquals("body", TabularDataAdapter.adapt(DocText.of("body")).cell(0, 0).externalValue());
        assertEquals("body", CucumberModelConverter.toDocText(CucumberModelConverter.toDocString(DocText.of("body"))).content());
    }
}
