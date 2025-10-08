package tools.ds.modkit.coredefinitions;

import annotations.NoLogging;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.mappings.NodeMap;
import tools.ds.modkit.mappings.ParsingMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static tools.ds.modkit.extensions.StepExtension.getCurrentStep;
import static tools.ds.modkit.extensions.StepRelationships.pairSiblings;
import static tools.ds.modkit.mappings.NodeMap.DataSource.TABLE_ROW;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.TableUtils.toFlatMultimap;
import static tools.ds.modkit.util.TableUtils.toRowsMultimap;

public class MappingSteps {

    public static final String TABLE_KEY = "\u206A_TABLE_KEY";


    //    @NoLogging
    @Given("^For every ROW in (:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void forEverRow(String tableName) {
        System.out.println("@@forEverRow!!");

        StepExtension currentStep = getCurrentStep();
        DataTable dataTable = null;
        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
        if (tableName.isEmpty()) {
            StepExtension nestStep = currentStep.getNextSibling();
            if (nestStep != null && nestStep.isDataTableStep)
                dataTable = nestStep.getDataTable();
        } else {
            dataTable = (DataTable) getScenarioState().getInstance(tableName);
        }
        if (dataTable == null)
            throw new RuntimeException("Data Table not defined");
        LinkedListMultimap<String, LinkedListMultimap<String, String>> rowMap = toRowsMultimap(dataTable);
        if (!tableName.isEmpty())
            currentStep.getStepNodeMap().put(tableName.trim(), rowMap);
        else
            currentStep.getStepNodeMap().merge(rowMap);

        System.out.println("@@currentStep.getStepParsingMap:: " + currentStep.getStepParsingMap());

        List<LinkedListMultimap<String, String>> rows = rowMap.get("ROW");

        System.out.println("@@rowss: " + rows);
        System.out.println("@@rows.size: " + rows.size());
        StepExtension lastSibling = null;
        List<StepExtension> children = currentStep.getChildSteps();
        currentStep.clearChildSteps();
        for (int r = 0; r < rows.size(); r++) {

            LinkedListMultimap<String, String> row = rows.get(r);
            NodeMap rowNodeMap = new NodeMap(row);
            System.out.println("@@row=: " + row);
            rowNodeMap.setDataSource(TABLE_ROW);
            rowNodeMap.setMapType(ParsingMap.MapType.STEP_MAP);
            System.out.println("@@step== " + "-" + r + "- " + currentStep.getStepText());
            StepExtension nextSibling = currentStep.modifyStep("qq-" + r + "- " + currentStep.getStepText());
            nextSibling.inheritFromParent = false;
            System.out.println("\n\n@@rowNodeMap: "  + rowNodeMap );
            System.out.println("\n\n@@nextSibling parsaing11:\n"  + nextSibling.getStepParsingMap() );
            nextSibling.addToStepParsingMap(rowNodeMap);
            System.out.println("\n\n@@nextSibling parsaing22:\n"  + nextSibling.getStepParsingMap() );
            System.out.println("\n\n@@setchildren "  + children.size() + "  -  " + children);
            nextSibling.setChildSteps(children);
            currentStep.addChildStep(nextSibling);
            System.out.println("@@nextSibling: " + nextSibling);
            System.out.println("@@lastSibling: " + lastSibling);
            if (lastSibling != null)
                pairSiblings(lastSibling, nextSibling);
            lastSibling = nextSibling;
            System.out.println("\n\n@@nextSibling parsaing33:\n"  + nextSibling.getStepParsingMap() );
        }

    }

    @Given("^qq-(\\d+)-( For .*)")
    public static void loop(String count, String stepText) {

        System.out.println("@@@LOOP _ " + count + "  -  " + stepText);
    }

    @Given("^(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void dataTable(String tableName, DataTable dataTable) {
        System.out.println("Datatable Step tableName: " + tableName);
        System.out.println("Datatable Step dataTable: " + dataTable);
//        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
//        getCurrentStep().putToTemplateStep(tableName, dataTable);
//        getScenarioState().register(dataTable, tableName);
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
