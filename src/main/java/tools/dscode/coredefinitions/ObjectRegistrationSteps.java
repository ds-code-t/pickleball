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

public class ObjectRegistrationSteps {

    public static final String objRegistrationPrefix = "\u207A_OR";
    public static final String objRegistration = "(?i)^" + objRegistrationPrefix;

    public static final String objCreationPrefix = "\u207A_OC";
    public static final String objCreation = "(?i)^" + objCreationPrefix;

    public static final String objActionPrefix = "\u207A_AE";
    public static final String objAction = "(?i)^" + objActionPrefix;

    public static final String objConfigIdentityPrefix = "__CONFIG_BY_IDENTITY__";

    public static final String pathKeyProperty = "_pathKey";
    public static final String postActionsProperty = "postActions";
    public static final String cleanupProperty = "cleanup";

    @Given(objRegistration + "(.+)$")
    public static Object constructObjectFromParsingMap(String pathKey) {
        if (pathKey == null || pathKey.isBlank()) {
            throw new IllegalArgumentException("Object construction path cannot be null or blank");
        }

        Object existing = getScenarioObject(pathKey);
        if (existing != null) {
            return existing;
        }

        ObjectNode config = getObjectNodeConfig(pathKey);
        config.put(pathKeyProperty, pathKey);

        String constructor = trimToNull(config.path("constructor").asText(null));
        if (constructor == null) {
            throw new RuntimeException(
                    "Parsing map entry '" + pathKey + "' must contain a top-level string property 'constructor'"
            );
        }

        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(objCreationPrefix + constructor);
        modifiedStep.addArg(DocStringBuilder.createObjectNodeDocString(config));

        Object created = modifiedStep.runAndGetReturnValue();
        registerObjectAndConfig(pathKey, created, config);

        JsonNode postActions = config.get(postActionsProperty);
        if (postActions != null && !postActions.isNull()) {
            created = runRegisteredActions(pathKey, created, config, postActions, postActionsProperty);
            registerObjectAndConfig(pathKey, created, config);
        }

        return created;
    }

    public static Object cleanup(Object object) {
        if (object == null) {
            return null;
        }

        ObjectNode config = getRegisteredObjectConfig(object);
        if (config == null) {
            return object;
        }

        String pathKey = trimToNull(config.path(pathKeyProperty).asText(null));
        if (pathKey == null) {
            return object;
        }

        JsonNode cleanup = config.get(cleanupProperty);
        if (cleanup == null || cleanup.isNull()) {
            return object;
        }

        registerObjectAndConfig(pathKey, object, config);

        Object cleaned = runRegisteredActions(pathKey, object, config, cleanup, cleanupProperty);
        registerObjectAndConfig(pathKey, cleaned, config);
        return cleaned;
    }

    public static Object cleanupByPathKey(String pathKey) {
        String key = trimToNull(pathKey);
        if (key == null) {
            return null;
        }

        Object object = getScenarioObject(key);
        if (object == null) {
            return null;
        }

        return cleanup(object);
    }

    private static Object runRegisteredActions(
            String pathKey,
            Object current,
            ObjectNode config,
            JsonNode actions,
            String propertyName
    ) {
        if (actions.isTextual()) {
            return runSingleRegisteredAction(pathKey, current, config, actions.asText(), propertyName);
        }

        if (actions.isArray()) {
            Object value = current;
            for (JsonNode node : actions) {
                if (node == null || node.isNull() || !node.isTextual()) {
                    throw new RuntimeException(
                            "Parsing map entry '" + pathKey + "' has non-text " + propertyName + " entry: " + node
                    );
                }
                value = runSingleRegisteredAction(pathKey, value, config, node.asText(), propertyName);
            }
            return value;
        }

        throw new RuntimeException(
                "Parsing map entry '" + pathKey + "' has invalid top-level '" + propertyName + "'. " +
                        "Expected string or array of strings, but got: " + actions.getNodeType()
        );
    }

    private static Object runSingleRegisteredAction(
            String pathKey,
            Object current,
            ObjectNode config,
            String actionStep,
            String propertyName
    ) {
        String action = trimToNull(actionStep);
        if (action == null) {
            return current;
        }

        registerObjectAndConfig(pathKey, current, config);

        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(objActionPrefix + action);
        modifiedStep.addArg(DocStringBuilder.createDocString(Object.class, pathKey, "registry"));

        Object returned = modifiedStep.runAndGetReturnValue();
        Object result = returned != null ? returned : getScenarioObject(pathKey);
        if (result == null) {
            result = current;
        }

        registerObjectAndConfig(pathKey, result, config);
        return result;
    }

    public static void registerObjectAndConfig(String pathKey, Object object, ObjectNode config) {
        if (pathKey != null && !pathKey.isBlank() && object != null) {
            registerScenarioObject(pathKey, object);
        }
        registerObjectConfig(object, config);
    }

    public static void registerObjectConfig(Object object, ObjectNode config) {
        if (object == null || config == null) {
            return;
        }
        registerScenarioObject(getObjectConfigKey(object), config.deepCopy());
    }

    public static ObjectNode getRegisteredObjectConfig(Object object) {
        if (object == null) {
            return null;
        }

        Object raw = getScenarioObject(getObjectConfigKey(object));
        if (raw == null) {
            return null;
        }

        if (raw instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }

        if (raw instanceof JsonNode jsonNode) {
            if (!jsonNode.isObject()) {
                throw new RuntimeException(
                        "Registered config for object '" + getObjectIdentityKey(object) + "' was "
                                + jsonNode.getNodeType() + " instead of ObjectNode"
                );
            }
            return (ObjectNode) jsonNode.deepCopy();
        }

        try {
            return MAPPER.convertValue(raw, ObjectNode.class);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Registered config for object '" + getObjectIdentityKey(object)
                            + "' could not be converted to ObjectNode",
                    e
            );
        }
    }

    public static String getObjectConfigKey(Object object) {
        return objConfigIdentityPrefix + getObjectIdentityKey(object);
    }

    public static String getObjectIdentityKey(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }
        return (object.getClass().getName() + "_" + Integer.toHexString(System.identityHashCode(object))).replaceAll("[^a-zA-Z0-9]", "_");
    }

    public static ObjectNode getObjectNodeConfig(String pathKey) {
        Object raw = getFromRunningParsingMapCaseInsensitive(configsRoot + "." +  pathKey);

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
    public static Object getFromRegistryOrConstruct(String objectName) {
        return constructObjectFromParsingMap(objectName);
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}