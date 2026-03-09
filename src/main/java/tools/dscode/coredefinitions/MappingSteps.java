package tools.dscode.coredefinitions;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.*;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toFlatMultimap;
import static io.cucumber.core.runner.util.TableUtils.toRowsMultimap;
import static tools.dscode.common.GlobalConstants.MATCH_START;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;


public class MappingSteps extends CoreSteps {

    @Given("^(:?\"(.*)\"\\s+)?DOC STRING$")
    public static void docString(String docStringName, DocString docString) {
        String docStringNameText = (docStringName == null || docStringName.isBlank()) ? "" : " " + docStringName;
        stepInfo("Setting Doc String" + docStringNameText + ":" + docString);
    }

    @Given("^(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void dataTable(String tableName, DataTable dataTable) {
        String tableNameText = (tableName == null || tableName.isBlank()) ? "" : " " + tableName;
        stepInfo("Setting Data Table" + tableNameText + ":" + dataTable);
    }









//    @Given("^For every ROW in (:?\"(.*)\"\\s+)?DATA TABLE$")
//    public static void forEveryRow(String tableName) {
//        StepExtension currentStep = getRunningStep();
//
//        currentStep.grandChildrenSteps.addAll(currentStep.childSteps);
//        currentStep.childSteps.clear();
//
//        DataTable dataTable = currentStep.getDataTable();
//        tableName = tableName == null || tableName.isBlank() ? "" : tableName.trim();
//        if (tableName.isEmpty()) {
//            StepBase nestStep = currentStep.nextSibling;
//            if (nestStep != null && nestStep.dataTable != null)
//                dataTable = nestStep.dataTable;
//        } else {
//            dataTable = (DataTable) getCurrentScenarioState().get("-DATATABLE_" + tableName);
//        }
//        if (dataTable == null)
//            throw new RuntimeException("Data Table not defined");
//
//        LinkedListMultimap<String, LinkedListMultimap<String, String>> rowMap = toRowsMultimap(dataTable);
//        if (!tableName.isEmpty())
//            currentStep.getStepNodeMap().put(tableName.trim(), rowMap);
//        else
//            currentStep.getStepNodeMap().merge(rowMap);
//
//        List<LinkedListMultimap<String, String>> rows = rowMap.get("ROWS");
//
//        for (int r = 0; r < rows.size(); r++) {
//
//        }
//    }



    @Given("^SET (?:(DEFAULT|OVERRIDE|SINGLETON) )?VALUES$")
    public static void setValues(String mapType, DataTable dataTable) {
        NodeMap nodeMap = switch (mapType) {
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            case null, default -> MappingProcessor.getRunMap();
        };
        nodeMap.merge(toFlatMultimap(dataTable.asLists()));
    }

    @Given("^save \"(.*)\" as \"(.*)\"$")
    public static void saveValues(String value, String key) {
        stepInfo("SAve values");
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        getCurrentScenarioState().put(key, value);
    }

}
