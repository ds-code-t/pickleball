package tools.dscode.coredefinitions;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import org.apache.poi.ss.usermodel.Row;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.annotations.DefinitionFlags;
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
import static tools.dscode.common.annotations.DefinitionFlag._NO_LOGGING;
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


//    @DefinitionFlags(_NO_LOGGING)
    @Given("^FOR EVERY (\".*\" )?DATA ROW IN THE (\".*\" )?DATA TABLE:$")
    public static void forEveryRow(String rowName, String tableName) {
        tableName = tableName == null || tableName.isBlank() ? "" : tableName ;
        rowName = rowName == null || rowName.isBlank() ? "" : rowName;
        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(", in the " + tableName + "Data Table, for every " + rowName + "Data Row:");
        currentStep.insertReplacement(modifiedStep);
    }



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
