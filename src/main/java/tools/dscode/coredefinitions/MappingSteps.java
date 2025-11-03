//package tools.dscode.coredefinitions;
//
//import com.google.common.collect.LinkedListMultimap;
//import io.cucumber.core.runner.StepData;
//import io.cucumber.core.runner.StepExtension;
//import io.cucumber.datatable.DataTable;
//import io.cucumber.java.en.Given;
//import tools.dscode.common.CoreSteps;
//import tools.dscode.common.mappings.NodeMap;
//
//import java.util.List;
//
//import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
//import static io.cucumber.core.runner.util.TableUtils.toRowsMultimap;
//
//
//public class MappingSteps  extends CoreSteps {
//
//
//
//    // @NoLogging
//    @Given("^For every ROW in (:?\"(.*)\"\\s+)?DATA TABLE$")
//    public static void forEverRow(String tableName) {
//        System.out.println("@@forEverRow!!");
//
//        StepExtension currentStep = getCurrentScenarioState().getCurrentStep();
//        DataTable dataTable = null;
//        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
//        if (tableName.isEmpty()) {
//            StepData nestStep = currentStep.nextSibling;
//            if (nestStep != null && nestStep.dataTable!=null)
//                dataTable = nestStep.dataTable;
//        } else {
//            dataTable = (DataTable) getCurrentScenarioState().get("-DATATABLE." + tableName);
//        }
//        if (dataTable == null)
//            throw new RuntimeException("Data Table not defined");
//
//        LinkedListMultimap<String, LinkedListMultimap<String, String>> rowMap = toRowsMultimap(dataTable);
//        System.out.println("@@rowMap:::::: " + rowMap);
//        // if (!tableName.isEmpty())
//        // currentStep.getStepNodeMap().put(tableName.trim(), rowMap);
//        // else
//        // currentStep.getStepNodeMap().merge(rowMap);
//
//        System.out.println("@@currentStep.getStepParsingMap:: " + currentStep.getStepParsingMap());
//
//        List<LinkedListMultimap<String, String>> rows = rowMap.get("ROWS");
//        currentStep.put("ROWS=", rows);
//        System.out.println("@@rowss: " + rows);
//        System.out.println("@currentStepgetStepParsingMap " + currentStep.getStepParsingMap());
//        System.out
//                .println("@currentStepgetStepParsingMap get(\"ROWS\") " + currentStep.getStepParsingMap().get("ROWS"));
//        StepExtension lastSibling = null;
//        List<StepData> children = currentStep.getChildSteps();
//        currentStep.clearChildSteps();
//
//        System.out.println("@@rows.size(): " + rows.size());
//        for (int r = 0; r < rows.size(); r++) {
//            LinkedListMultimap<String, String> row = rows.get(r);
//            StepExtension nextSibling = currentStep.modifyStep("-" + r + "- " + currentStep.getStepText());
//            nextSibling.setStepParsingMap(currentStep.getStepParsingMap());
//            System.out.println("@@row: " + row);
//            nextSibling.mergeToStepMap(row);
//            nextSibling.put("ROW", row);
//            if (!tableName.isEmpty())
//                nextSibling.put(tableName + ".ROW", row);
//            nextSibling.setChildSteps(children);
//            currentStep.addChildStep(nextSibling);
//            if (lastSibling != null)
//                pairSiblings(lastSibling, nextSibling);
//            else
//                System.out.println("@@nextSibling getNextSibling: " + nextSibling.getNextSibling());
//
//            lastSibling = nextSibling;
//            System.out.println("\n\n@@nextSibling parsaing33:\n" + nextSibling.getStepParsingMap());
//        }
//
//    }
//
//    @Given("^-(\\d+)-( For .*)")
//    public static void loop(String count, String stepText) {
//        System.out.println("@@@LOOP _ " + count + "  -  " + stepText);
//        System.out.println("@@@LOOP _ passing mpa: " + getCurrentStep().getStepParsingMap());
//    }
//
//    @Given("^(:?\"(.*)\"\\s+)?DATA TABLE$")
//    public static void dataTable(String tableName, DataTable dataTable) {
//        System.out.println("Datatable Step tableName: " + tableName);
//        System.out.println("Datatable Step dataTable: " + dataTable);
//        // tableName = tableName == null || tableName.isBlank() ? "" :
//        // tableName.trim();
//        // getCurrentStep().putToTemplateStep(tableName, dataTable);
//        // getScenarioState().register(dataTable, tableName);
//    }
//
//    // // @Given("^([A-Z]*\\s+)*(:?\"(.*)\"\\s+)?DATA TABLE$")
//    // @Given("^wew(?:(DEFAULT|OVERRIDE)\\s+)?(:?\"(.*)\"\\s+)?DATA TABLE$")
//    // public static void setDataTableValues(String tableType, String tableName,
//    // DataTable dataTable) {
//    ////        if (tableName != null && !tableName.isBlank())
//////            getCurrentStep().templateStepObjectMap.put(TABLE_NAME, tableType.trim());
//    // List<String> parameters = tableType == null || tableType.isBlank() ? new
//    // ArrayList<>() :
//    // Arrays.stream(tableType.strip().split("\\s+")).toList();
//    // ParsingMap parsingMap = parameters.contains("GLOBAL") ?
//    // getCurrentStep().getStepParsingMap() :
//    // getScenarioState().getParsingMap();
//    // List<NodeMap> nodeMapList = new ArrayList<>();
//    // if (parameters.contains("DEFAULT")) {
//    // nodeMapList.addAll(parsingMap.getNodeMaps(MapConfigurations.MapType.DEFAULT));
//    // } else if (parameters.contains("OVERRIDE")) {
//    // nodeMapList.addAll(parsingMap.getNodeMaps(MapConfigurations.MapType.OVERRIDE_MAP));
//    // } else {
//    // nodeMapList.add(parsingMap.getPrimaryRunMap());
//    // }
//    // saveDataTableToStepMap(tableName, dataTable, nodeMapList);
//    // }
//
//    private static void saveDataTableToStepMap(String tableName, DataTable dataTable, List<NodeMap> nodeMaps) {
//        for (NodeMap nodeMap : nodeMaps) {
//            if (tableName != null && !tableName.isBlank()) {
//                nodeMap.put(tableName.trim(), toFlatMultimap(dataTable.asLists()));
//                nodeMap.put(tableName.trim(), toRowsMultimap(dataTable));
//            } else {
//                nodeMap.merge(toFlatMultimap(dataTable.asLists()));
//                nodeMap.merge(toRowsMultimap(dataTable));
//            }
//        }
//    }
//
//    // @Given("^SET (\".*\"\\s)?TABLE VALUES$")
//    // public static void setValues(String tableName, DataTable dataTable) {
//    // ScenarioState scenarioState = getScenarioState();
//    // if (tableName != null && !tableName.isBlank()) {
//    // scenarioState.put(tableName.trim(), toFlatMultimap(dataTable.asLists()));
//    // scenarioState.put(tableName.trim(), toRowsMultimap(dataTable));
//    // } else {
//    // scenarioState.mergeToRunMap(toFlatMultimap(dataTable.asLists()));
//    // scenarioState.mergeToRunMap(toRowsMultimap(dataTable));
//    // }
//    // }
//
//    @Given("^save \"(.*)\" as \"(.*)\"$")
//    public static void saveValues(String value, String key) {
//        System.out.println("SAve values");
//        try {
//            Thread.sleep(100L);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        getScenarioState().put(key, value);
//    }
//
//}
