package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringBuilder;
import io.cucumber.java.en.Given;

import java.util.Map;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;
import static io.cucumber.core.runner.CurrentScenarioState.registerScenarioObject;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.mappings.ParsingMap.configsRoot;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.util.GeneralUtils.isUsableValue;

public class ObjectRegistrationSteps {

    public static final String objRegistration = "(?i)^_";

    public static Object getObjectFromRegistryOrDefault(String objectName, String defaultObjectName) {
        String keyString = isUsableValue(objectName) ? objectName : defaultObjectName;
        return returnStepParameter(keyString);
    }

    public static Object returnStepParameter(String keyString) {
        if (keyString == null || keyString.isBlank()) {
            throw new IllegalArgumentException("Object registration key cannot be null or blank");
        }

        Object existing = getScenarioObject(keyString);
        if (existing != null) {
            return existing;
        }

        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension("_" + keyString);
        modifiedStep.argument = currentStep.argument;

        Object created = modifiedStep.runAndGetReturnValue();
        registerScenarioObject(keyString, created);
        return created;
    }

    @Given(objRegistration + "(.+)$")
    public Object constructObjectFromParsingMap(String pathKey) {
        if (pathKey == null || pathKey.isBlank()) {
            throw new IllegalArgumentException("Object construction path cannot be null or blank");
        }

        ObjectNode config = getObjectNodeConfig(pathKey);
        config.put("_pathKey", pathKey);

        String constructor = trimToNull(config.path("constructor").asText(null));
        if (constructor == null) {
            throw new RuntimeException(
                    "Parsing map entry '" + pathKey + "' must contain a top-level string property 'constructor'"
            );
        }

        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(constructor);
        modifiedStep.addArg(DocStringBuilder.createObjectNodeDocString(config));

        Object created = modifiedStep.runAndGetReturnValue();
        registerScenarioObject(pathKey, created);
        return created;
    }

    public static ObjectNode getObjectNodeConfig(String pathKey) {
        Object raw = getFromRunningParsingMapCaseInsensitive(configsRoot + "." + pathKey);

        if (raw == null) {
            throw new RuntimeException("No parsing-map entry found for object path '" + pathKey + "'");
        }

        if (raw instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }

        if (raw instanceof JsonNode jsonNode) {
            if (!jsonNode.isObject()) {
                throw new RuntimeException(
                        "Parsing-map entry '" + pathKey + "' resolved to " + jsonNode.getNodeType()
                                + " instead of an object/ObjectNode"
                );
            }
            return (ObjectNode) jsonNode.deepCopy();
        }

        if (raw instanceof Map<?, ?> map) {
            return MAPPER.convertValue(map, ObjectNode.class);
        }

        try {
            ObjectNode converted = MAPPER.convertValue(raw, ObjectNode.class);
            if (converted == null || converted.isEmpty()) {
                throw new RuntimeException(
                        "Parsing-map entry '" + pathKey + "' could not be converted to a usable ObjectNode"
                );
            }
            return converted;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Parsing-map entry '" + pathKey + "' was of type " + raw.getClass().getName()
                            + " and could not be converted to ObjectNode",
                    e
            );
        }
    }

    @Given("^GET:(.*)$")
    public Object getObjectFromRegistration(String objectName) {
        return returnStepParameter(objectName);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}