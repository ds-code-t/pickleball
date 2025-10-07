package tools.ds.modkit.coredefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.mappings.NodeMap;
import tools.ds.modkit.mappings.ParsingMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static tools.ds.modkit.extensions.StepExtension.getCurrentStep;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.TableUtils.toFlatMultimap;
import static tools.ds.modkit.util.TableUtils.toRowsMultimap;

public class MappingSteps {

    public static final String TABLE_KEY = "\u206A_TABLE_KEY";
    ;

    @Given("^For every ROW in (:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void forEverRow(String tableName) {
        StepExtension currentStep = getCurrentStep();
        DataTable dataTable = null;
        if (tableName == null || tableName.isBlank()) {
            StepExtension nestStep = currentStep.getNextSibling();
            if (nestStep != null && nestStep.isDataTableStep)
                dataTable = nestStep.getDataTable();
        } else {
            dataTable = (DataTable) getScenarioState().getInstance(tableName.trim());
        }
        if (dataTable == null)
            throw new RuntimeException("Data Table not defined");
        currentStep.getStepNodeMap().put(tableName.trim(), toRowsMultimap(dataTable));
    }

    @Given("^(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void dataTable(String tableName, DataTable dataTable) {
        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
        getCurrentStep().putToTemplateStep(tableName, dataTable);
        getScenarioState().register(dataTable, tableName);
    }

    //    @Given("^([A-Z]*\\s+)*(:?\"(.*)\"\\s+)?DATA TABLE$")
    @Given("^wew(?:(DEFAULT|OVERRIDE)\\s+)?(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void setDataTableValues(String tableType, String tableName, DataTable dataTable) {
//        if (tableName != null && !tableName.isBlank())
//            getCurrentStep().templateStepObjectMap.put(TABLE_NAME, tableType.trim());
        List<String> parameters = tableType == null || tableType.isBlank() ? new ArrayList<>() :
                Arrays.stream(tableType.strip().split("\\s+")).toList();
        ParsingMap parsingMap = parameters.contains("GLOBAL") ? getCurrentStep().getStepParsingMap() : getScenarioState().getParsingMap();
        List<NodeMap> nodeMapList = new ArrayList<>();
        if (parameters.contains("DEFAULT")) {
            nodeMapList.addAll(parsingMap.getNodeMaps(ParsingMap.MapType.DEFAULT));
        } else if (parameters.contains("OVERRIDE")) {
            nodeMapList.addAll(parsingMap.getNodeMaps(ParsingMap.MapType.OVERRIDE_MAP));
        } else {
            nodeMapList.add(parsingMap.getPrimaryRunMap());
        }
        saveDataTableToStepMap(tableName, dataTable, nodeMapList);
    }


    private static void saveDataTableToStepMap(String tableName, DataTable dataTable, List<NodeMap> nodeMaps) {
        for (NodeMap nodeMap : nodeMaps) {
            if (tableName != null && !tableName.isBlank()) {
                nodeMap.put(tableName.trim(), toFlatMultimap(dataTable.asLists()));
                nodeMap.put(tableName.trim(), toRowsMultimap(dataTable));
            } else {
                nodeMap.merge(toFlatMultimap(dataTable.asLists()));
                nodeMap.merge(toRowsMultimap(dataTable));
            }
        }
    }


//    @Given("^SET (\".*\"\\s)?TABLE VALUES$")
//    public static void setValues(String tableName, DataTable dataTable) {
//        ScenarioState scenarioState = getScenarioState();
//        if (tableName != null && !tableName.isBlank()) {
//            scenarioState.put(tableName.trim(), toFlatMultimap(dataTable.asLists()));
//            scenarioState.put(tableName.trim(), toRowsMultimap(dataTable));
//        } else {
//            scenarioState.mergeToRunMap(toFlatMultimap(dataTable.asLists()));
//            scenarioState.mergeToRunMap(toRowsMultimap(dataTable));
//        }
//    }


    @Given("^save \"(.*)\" as \"(.*)\"$")
    public static void saveValues(String value, String key) {
        System.out.println("SAve values");
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        getScenarioState().put(key, value);
    }


}
