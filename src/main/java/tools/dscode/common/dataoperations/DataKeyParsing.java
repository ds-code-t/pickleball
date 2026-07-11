package tools.dscode.common.dataoperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.LIST_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toRowsStringMultimap;
import static tools.dscode.common.GlobalConstants.META_FLAG;
import static tools.dscode.common.evaluations.AviatorUtil.isTruthy;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.MappingProcessor.getDataMap;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.DATA_TABLE_ELEMENTS;
import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;

public class DataKeyParsing {

    public static final String DATA_KEY_FLAG = META_FLAG + "DATA_KEY_FLAG";
    public static final String DATA_INVERTER_KEY = META_FLAG + "DATA_INVERTER_KEY";
    public static final String DATA_VALUE_FLAG = META_FLAG + "DATA_VALUE_FLAG";
//    public static final String dataFLAG = META_FLAG + "DATA";


    public static DocString getStepDocStringArg() {
        return getRunningStep().getUnmodifiedDocString();
    }

    public static DataTable getStepDataTableArg() {
        return getRunningStep().getUnmodifiedDataTable();
    }

    public static JsonNode getDataFromDataElementKey(ElementMatch dataElement) {
        return dataElement.defaultText.isNullOrBlank() ? getRunningParsingMap().getPhraseMap().getData(dataElement) : getDataMap().getData(dataElement);
    }

    public static DataTable getDataTableFromDataElementKey(ElementMatch dataElement) {
        if (dataElement.defaultText.isNullOrBlank()) {
            DataTable dataTable = getRunningParsingMap().getPhraseMap().getDataTableFromDataMap(dataElement);
            if (dataTable != null) return dataTable;
            return getStepDataTableArg();
        }
        return getDataMap().getDataTableFromDataMap(dataElement);
    }

    public static Object parseDataKey(ElementMatch dataElement) {
        if (DATA_TABLE_ELEMENTS.contains(dataElement.categorySingular)) {
            if (dataElement.categorySingular.equals(TABLE_KEY))
                return getDataTableFromDataElementKey(dataElement);
            if (dataElement.categorySingular.equals(ROW_KEY)) {
                DataTable dataTable = getDataTableFromDataElementKey(dataElement);
                if(dataTable != null)
                {

                }
            }



        }

        String key = dataElement.defaultText.isNullOrBlank() ? "" : dataElement.defaultText.getValue().toString();
        StepExtension currentStep = getRunningStep();
        ParsingMap parsingMap = getRunningParsingMap();
        NodeMap phraseNodeMap = parsingMap.getPhraseMap();
//        ElementMatch savedDataElement = (ElementMatch) phraseNodeMap.get(DATA_KEY_FLAG);
//        JsonNode data = savedDataElement == null ? null : (JsonNode) phraseNodeMap.get(DATA_VALUE_FLAG);
        JsonNode data = key.isEmpty() ? phraseNodeMap.getData(dataElement) : getDataMap().getData(dataElement);
        DataTable dataTable = key.isEmpty() ? phraseNodeMap.getDataTableFromDataMap(dataElement) : getDataMap().getDataTableFromDataMap(dataElement);

        if (data == null) {
            if (currentStep.dataArgument != null) {


            }
        }

        if (key.isEmpty()) {
            if (data == null) {
                if (currentStep.dataArgument != null) {
                    data = currentStep.dataArgument;
                    phraseNodeMap.put(DATA_KEY_FLAG, dataElement);
                    phraseNodeMap.put(DATA_VALUE_FLAG, data);
                }
            }
        } else {

        }


        return null;
    }
}




