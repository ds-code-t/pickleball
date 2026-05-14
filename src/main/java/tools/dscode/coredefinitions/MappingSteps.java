package tools.dscode.coredefinitions;


import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;

import java.util.Map;

import static io.cucumber.core.runner.GlobalState.*;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toFlatStringMultimap;
import static io.cucumber.core.runner.util.TableUtils.toRowsStringMultimap;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.MappingProcessor.getDataTableMap;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;
import static tools.dscode.common.variables.RunVars.resolveFromVars;


public class MappingSteps extends CoreSteps {



    @Given("^CLEAR SAVED VALUES(:.*)?$")
    public static void clearRunMap(String keys) {
        String[] keyArray = keys == null || keys.trim().length() == 1
                ? new String[0]
                : java.util.Arrays.stream(keys.substring(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        getRunMap().clearValues(keyArray);

        if (keyArray.length == 0) {
            stepInfo("Clearing Scenario Run Map");
        } else {
            stepInfo("Clearing Scenario Run Map values: " + String.join(", ", keyArray));
        }
    }

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

    @Given("^SET \"(.*)\" DATA TABLE$")
    public static void setDataTable(String tableName, DataTable dataTable) {
        getDataTableMap().put(TABLE_KEY, Map.of(tableName.trim(), toRowsStringMultimap(dataTable)));
        stepInfo("Setting Data Table" + tableName + ":" + dataTable);
    }


    //    @DefinitionFlags(_NO_LOGGING)
    @Given("^FOR EVERY (\".*\" )?DATA ROW IN THE (\".*\" )?DATA TABLE:$")
    public static void forEveryRow(String rowName, String tableName) {
        tableName = tableName == null || tableName.isBlank() ? "" : tableName;
        rowName = rowName == null || rowName.isBlank() ? "" : rowName;
        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(", in the " + tableName + "Data Table, for every " + rowName + "Data Row:");
        currentStep.addReplacementStep(modifiedStep);
    }


    @Given("^SET (?:(DEFAULT|OVERRIDE|SINGLETON) )?VALUES$")
    public static void setValues(String mapType, DataTable dataTable) {
        NodeMap nodeMap = switch (mapType) {
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            case null, default -> getRunMap();
        };
        nodeMap.merge(toFlatStringMultimap(dataTable.asLists()));
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

    @Given("(?i)^resolveVar:(.+)$")
    public static Object resolveToVarStepDef(String varName) {
        return resolveFromVars(varName);
    }

    @Given("^ARG$")
    public static Object getArgValue() {
        return getRunningStep().argument.getValue();
    }

    @Given("^PATH:(.*)$")
    public static Object getFromPath(String path) {
        if(path == null || path.isBlank()) return null;
        return buildJsonFromPath(path.trim());
    }


}
