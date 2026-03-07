//package tools.dscode.common.dataoperations;
//
//import com.google.common.collect.LinkedListMultimap;
//import io.cucumber.core.runner.CurrentScenarioState;
//import io.cucumber.core.runner.StepBase;
//import io.cucumber.core.runner.StepExtension;
//import io.cucumber.datatable.DataTable;
//import io.cucumber.java.en.Given;
//import tools.dscode.common.mappings.MapConfigurations;
//import tools.dscode.common.mappings.NodeMap;
//import tools.dscode.common.mappings.ParsingMap;
//import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//
//import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
//import static io.cucumber.core.runner.GlobalState.getRunningStep;
//import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
//import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
//import static io.cucumber.core.runner.util.TableUtils.toRowsMultimap;
//import static tools.dscode.common.GlobalConstants.MATCH_START;
//
//public class DataElementProcessing {
//
//    public static NodeMap getDataTableNodeMap(ElementMatch elementMatch) {
//        return getDataTableNodeMap(elementMatch, null);
//    }
//
//    public static NodeMap getDataTableNodeMap(ElementMatch elementMatch , NodeMap nodeMap) {
//        NodeMap phraseNodeMap = nodeMap == null ? new NodeMap(MapConfigurations.MapType.STEP_MAP) : nodeMap;
//        phraseNodeMap.setDataSource(MapConfigurations.DataSource.STEP_TABLE);
//        phraseNodeMap.merge(toRowsMultimap(getElementDataTable(elementMatch)));
//        return phraseNodeMap;
//    }
//
//    public static DataTable getElementDataTable(ElementMatch elementMatch) {
//        DataTable dataTable;
//        String tableName = elementMatch.defaultText.toNonNullString();
//        if (tableName.isEmpty()) {
//            dataTable = getRunningStep().getDataTable();
//        } else {
//            dataTable = (DataTable) getCurrentScenarioState().get("-" + TABLE_KEY + "_" + tableName);
//        }
//        if (dataTable == null)
//            throw new RuntimeException("Data Table not defined");
////        LinkedListMultimap<String, LinkedListMultimap<String, String>> rowMap = toRowsMultimap(dataTable);
////        if (!tableName.isEmpty())
////            nodeMap.put(tableName.trim(), rowMap);
////        else
////            nodeMap.merge(rowMap);
//        return dataTable;
//    }
//
//
//
//    @Given("^(?:(DEFAULT|OVERRIDE|GLOBAL)\\s+)+(:?\"(.*)\"\\s+)?DATA TABLE$")
//    public static void associateDataTableValues(String tableType, String tableName,
//                                          DataTable dataTable) {
//
//        //        if (tableName != null && !tableName.isBlank())
//        //            getCurrentStep().templateStepObjectMap.put(TABLE_NAME, tableType.trim());
//        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
//        List<String> parameters = tableType == null || tableType.isBlank() ? new
//                ArrayList<>() :
//                Arrays.stream(tableType.strip().split("\\s+")).toList();
//        ParsingMap parsingMap = parameters.contains("GLOBAL") ?
//                currentScenarioState.getCurrentStep().getStepParsingMap() :
//                currentScenarioState.getParsingMap();
//        List<NodeMap> nodeMapList = new ArrayList<>();
//        if (parameters.contains("DEFAULT")) {
//            nodeMapList.addAll(parsingMap.getNodeMaps(MapConfigurations.MapType.DEFAULT));
//        } else if (parameters.contains("OVERRIDE")) {
//            nodeMapList.addAll(parsingMap.getNodeMaps(MapConfigurations.MapType.OVERRIDE_MAP));
//        } else {
//            nodeMapList.add(parsingMap.getPrimaryRunMap());
//        }
//        setDataTablesToNodeMaps(tableName, dataTable, nodeMapList);
//    }
//
//    private static void setDataTablesToNodeMaps(String tableName, DataTable dataTable, List<NodeMap> nodeMaps) {
//        for (NodeMap nodeMap : nodeMaps) {
//            if (tableName != null && !tableName.isBlank()) {
//                nodeMap.put(tableName.trim(), toRowsMultimap(dataTable));
//            } else {
//                nodeMap.merge(toRowsMultimap(dataTable));
//            }
//        }
//    }
//
//
//
//
//
//
//
//
//
//
//    public static void processTableElement(ElementMatch elementMatch) {
//        String category = elementMatch.category.replaceAll("s$", "");
//        DataTable dataTable;
//        if (category.equalsIgnoreCase("Data Table")) {
//            dataTable = getElementDataTable(elementMatch);
//        }
//
//
//        StepExtension currentStep = getRunningStep();
//        DataTable dataTable = currentStep.getDataTable();
//        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
//        if (tableName.isEmpty()) {
//            StepBase nestStep = currentStep.nextSibling;
//            if (nestStep != null && nestStep.dataTable != null)
//                dataTable = nestStep.dataTable;
//        } else {
//            dataTable = (DataTable) getCurrentScenarioState().get("-" + TABLE_KEY + "_" + tableName);
//        }
//        if (dataTable == null)
//            throw new RuntimeException("Data Table not defined");
//
//        LinkedListMultimap<String, LinkedListMultimap<String, String>> rowMap = toRowsMultimap(dataTable);
//        if (!tableName.isEmpty())
//            currentStep.getDefaultStepNodeMap().put(tableName.trim(), rowMap);
//        else
//            currentStep.getDefaultStepNodeMap().merge(rowMap);
//
//
//        List<LinkedListMultimap<String, String>> rows = rowMap.get(ROW_KEY);
//        currentStep.put("ROWS=", rows);
//
//        StepExtension lastSibling = null;
//        StepExtension currentSibling = null;
//
//        currentStep.insertChildNesting();
//        for (int r = 0; r < rows.size(); r++) {
//            LinkedListMultimap<String, String> row = rows.get(r);
//            currentSibling = currentStep.cloneWithOverrides(MATCH_START + "ROW " + (r + 1) + ": " + row);
//            currentSibling.mergeToStepNodeMap(row);
//            currentStep.addChildStep(currentSibling);
//            if (lastSibling != null) {
//                currentSibling.previousSibling = lastSibling;
//                lastSibling.nextSibling = currentSibling;
//            }
//            lastSibling = currentSibling;
//
//
//            try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//        }
//    }
//
//}
