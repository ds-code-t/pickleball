package tools.dscode.common.dataoperations;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.LIST_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;

public class DataKeyParsing {

    public static Object parseDataKey(ElementMatch dataElement) {
        String key = dataElement.defaultText.isNullOrBlank() ? "" : dataElement.defaultText.getValue().toString();
        Object returnObject = null;
        if (key.isEmpty()) {
            StepExtension currentStep = getRunningStep();
            boolean hasTable = currentStep.dataContextStepNodeMap.getRoot().has(TABLE_KEY);
            boolean hasDocString = !hasTable && currentStep.dataContextStepNodeMap.getRoot().has(DOCSTRING_KEY);
            Object data =  hasTable ?  currentStep.dataContextStepNodeMap.get(TABLE_KEY) : hasDocString? currentStep.dataContextStepNodeMap.get(DOCSTRING_KEY) : null;
            if(data != null)
            {
                switch (dataElement.categorySingular) {
                    case TABLE_KEY:
                    case DOCSTRING_KEY:
                        return data;
                    case LIST_KEY:
                        return data;
                    default:
                }
            }
        }
        else if (key.startsWith("&")) {
            returnObject = getReturnValue(key.substring(1));
        }
        else if (key.startsWith("/")) {
            returnObject = buildJsonFromPath(key.substring(1));
        } else {
            returnObject = getRunningParsingMap().get(dataElement);
        }

        System.out.println("@@returnObject: " + returnObject);
        System.out.println("@@returnObject.getClass: " + returnObject.getClass());
        return null;
    }

}
