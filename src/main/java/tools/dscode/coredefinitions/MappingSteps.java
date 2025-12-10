package tools.dscode.coredefinitions;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.util.TableUtils.toFlatMultimap;
import static io.cucumber.core.runner.util.TableUtils.toRowsMultimap;
import static tools.dscode.common.GlobalConstants.MATCH_START;


public class MappingSteps extends CoreSteps {


    // @NoLogging
    @Given("^For every ROW in (:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void forEveryRow(String tableName) {

        StepExtension currentStep = getRunningStep();
        DataTable dataTable = currentStep.getDataTable();
        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
        if (tableName.isEmpty()) {
            StepBase nestStep = currentStep.nextSibling;
            if (nestStep != null && nestStep.dataTable != null)
                dataTable = nestStep.dataTable;
        } else {
            dataTable = (DataTable) getCurrentScenarioState().get("-DATATABLE." + tableName);
        }
        if (dataTable == null)
            throw new RuntimeException("Data Table not defined");

        LinkedListMultimap<String, LinkedListMultimap<String, String>> rowMap = toRowsMultimap(dataTable);
        if (!tableName.isEmpty())
            currentStep.getStepNodeMap().put(tableName.trim(), rowMap);
        else
            currentStep.getStepNodeMap().merge(rowMap);


        List<LinkedListMultimap<String, String>> rows = rowMap.get("ROWS");
        currentStep.put("ROWS=", rows);

        StepExtension lastSibling = null;
        StepExtension currentSibling = null;

        currentStep.insertChildNesting();
        for (int r = 0; r < rows.size(); r++) {
            LinkedListMultimap<String, String> row = rows.get(r);
            currentSibling = currentStep.cloneWithOverrides(MATCH_START + "ROW " + (r + 1) + ": " + row);
            currentSibling.mergeToStepNodeMap(row);
            currentStep.addChildStep(currentSibling);
            if (lastSibling != null) {
                currentSibling.previousSibling = lastSibling;
                lastSibling.nextSibling = currentSibling;
            }
            lastSibling = currentSibling;


            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

        }

    }

    @Given("^"+ MATCH_START + "ROW (\\d+): (.*)$")
    public static void loop(String count, String mapString) {
        StepExtension currentStep = getRunningStep();
    }

    @Given("^(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void dataTable(String tableName, DataTable dataTable) {
        System.out.println("Datatable Step tableName: " + tableName);
        System.out.println("Datatable Step dataTable: " + dataTable);
        // tableName = tableName == null || tableName.isBlank() ? "" :
        // tableName.trim();
        // getCurrentStep().putToTemplateStep(tableName, dataTable);
        // getScenarioState().register(dataTable, tableName);
    }

    // @Given("^([A-Z]*\\s+)*(:?\"(.*)\"\\s+)?DATA TABLE$")
    @Given("^(?:(DEFAULT|OVERRIDE|GLOBAL)\\s+)+(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void setDataTableValues(String tableType, String tableName,
                                          DataTable dataTable) {

        //        if (tableName != null && !tableName.isBlank())
        //            getCurrentStep().templateStepObjectMap.put(TABLE_NAME, tableType.trim());
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        List<String> parameters = tableType == null || tableType.isBlank() ? new
                ArrayList<>() :
                Arrays.stream(tableType.strip().split("\\s+")).toList();
        ParsingMap parsingMap = parameters.contains("GLOBAL") ?
                currentScenarioState.getCurrentStep().getStepParsingMap() :
                currentScenarioState.getParsingMap();
        List<NodeMap> nodeMapList = new ArrayList<>();
        if (parameters.contains("DEFAULT")) {
            nodeMapList.addAll(parsingMap.getNodeMaps(MapConfigurations.MapType.DEFAULT));
        } else if (parameters.contains("OVERRIDE")) {
            nodeMapList.addAll(parsingMap.getNodeMaps(MapConfigurations.MapType.OVERRIDE_MAP));
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

    @Given("^SET (\".*\"\\s)?TABLE VALUES$")
    public static void setValues(String tableName, DataTable dataTable) {
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        if (tableName != null && !tableName.isBlank()) {
            currentScenarioState.put(tableName.trim(), toFlatMultimap(dataTable.asLists()));
            currentScenarioState.put(tableName.trim(), toRowsMultimap(dataTable));
        } else {
            currentScenarioState.mergeToRunMap(toFlatMultimap(dataTable.asLists()));
            currentScenarioState.mergeToRunMap(toRowsMultimap(dataTable));
        }
    }

    @Given("^save \"(.*)\" as \"(.*)\"$")
    public static void saveValues(String value, String key) {
        System.out.println("SAve values");
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        getCurrentScenarioState().put(key, value);
    }

}
