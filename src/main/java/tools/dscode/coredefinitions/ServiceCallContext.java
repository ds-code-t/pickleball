package tools.dscode.coredefinitions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.NodeMap;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * Owns the mutable state for one service-call invocation.
 *
 * <p>The scenario-step map exposes singleton {@code request} and
 * {@code response} objects for convenient local access. The complete
 * service-call object is also appended under the resolved call name in both
 * the scenario-step map and the run map. All three locations reference the
 * same request and response node instances.</p>
 */
final class ServiceCallContext {

    static final String CALL_NAME = "SERVICE CALL NAME";
    static final String REQUEST = "request";
    static final String RESPONSE = "response";
    static final String STEP_SCENARIO_NAME = "SCENARIO NAME";

    private static final String NAME = "name";
    private static final String SCENARIO_NAME = "scenarioName";

    private ServiceCallContext() {
    }

    static ObjectNode initialize(
        NodeMap scenarioMap,
        NodeMap runMap,
        String callName,
        String scenarioName
    ) {
        String resolvedCallName = requireName(callName, "service-call name");
        String resolvedScenarioName = normalize(scenarioName);

        if (resolvedScenarioName.isBlank()) {
            resolvedScenarioName = resolvedCallName;
        }

        validateCallName(resolvedCallName);

        ObjectNode request = MAPPER.createObjectNode();
        ObjectNode response = MAPPER.createObjectNode();

        // Direct singleton working nodes for steps inside the called scenario.
        scenarioMap.getRoot().put(CALL_NAME, resolvedCallName);
        scenarioMap.getRoot().set(REQUEST, request);
        scenarioMap.getRoot().set(RESPONSE, response);

        ObjectNode serviceCall = MAPPER.createObjectNode();
        serviceCall.put(NAME, resolvedCallName);
        serviceCall.put(SCENARIO_NAME, resolvedScenarioName);
        serviceCall.set(REQUEST, request);
        serviceCall.set(RESPONSE, response);

        // Named access inside the called scenario, for example:
        // <readItemCall.response.statusCode>
        appendServiceCall(
            scenarioMap,
            resolvedCallName,
            serviceCall,
            "scenario-step map"
        );

        // Named access from the calling/root scenario and run-wide history.
        appendServiceCall(
            runMap,
            resolvedCallName,
            serviceCall,
            "run map"
        );

        return serviceCall;
    }

    static ObjectNode request(NodeMap scenarioMap) {
        return requiredObjectNode(scenarioMap, REQUEST);
    }

    static ObjectNode response(NodeMap scenarioMap) {
        return requiredObjectNode(scenarioMap, RESPONSE);
    }

    static String callName(NodeMap scenarioMap) {
        JsonNode directValue = scenarioMap.getRoot().get(CALL_NAME);
        String name = jsonText(directValue);

        if (!name.isBlank()) {
            return name;
        }

        // Compatibility fallback for maps created before CALL_NAME became a
        // direct singleton scalar.
        name = objectText(scenarioMap.get(CALL_NAME));
        if (!name.isBlank()) {
            return name;
        }

        name = scenarioName(scenarioMap);
        if (!name.isBlank()) {
            return name;
        }

        throw new IllegalStateException(
            "No service-call name or scenario name is available in the scenario-step map"
        );
    }

    static String scenarioName(NodeMap scenarioMap) {
        return objectText(scenarioMap.get(STEP_SCENARIO_NAME));
    }

    static ObjectNode objectChild(ObjectNode parent, String fieldName) {
        JsonNode existing = parent.get(fieldName);

        if (existing == null || existing.isNull()) {
            ObjectNode created = MAPPER.createObjectNode();
            parent.set(fieldName, created);
            return created;
        }

        if (existing instanceof ObjectNode objectNode) {
            return objectNode;
        }

        throw new IllegalStateException(
            "Service-call property '" + fieldName + "' exists but is not an object"
        );
    }

    static void replaceContents(ObjectNode target, ObjectNode replacement) {
        if (target == replacement) {
            return;
        }

        target.removeAll();
        target.setAll(replacement);
    }

    private static ObjectNode requiredObjectNode(NodeMap scenarioMap, String key) {
        JsonNode value = scenarioMap.getRoot().get(key);

        if (value instanceof ObjectNode objectNode) {
            return objectNode;
        }

        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                "Service-call context was not initialized: missing '" + key + "' object"
            );
        }

        throw new IllegalStateException(
            "Service-call context property '" + key + "' is not an object"
        );
    }

    private static void appendServiceCall(
        NodeMap nodeMap,
        String callName,
        ObjectNode serviceCall,
        String mapDescription
    ) {
        ObjectNode root = nodeMap.getRoot();
        JsonNode existing = root.get(callName);
        ArrayNode calls;

        if (existing == null || existing.isNull()) {
            calls = root.putArray(callName);
        } else if (existing instanceof ArrayNode arrayNode) {
            calls = arrayNode;
        } else {
            throw new IllegalStateException(
                "The "
                    + mapDescription
                    + " property '"
                    + callName
                    + "' already exists and is not an array"
            );
        }

        calls.add(serviceCall);
    }

    private static void validateCallName(String callName) {
        if (
            CALL_NAME.equals(callName)
                || REQUEST.equals(callName)
                || RESPONSE.equals(callName)
        ) {
            throw new IllegalArgumentException(
                "Service-call name '"
                    + callName
                    + "' is reserved. Choose a name other than '"
                    + CALL_NAME
                    + "', '"
                    + REQUEST
                    + "', or '"
                    + RESPONSE
                    + "'."
            );
        }
    }

    private static String requireName(String value, String description) {
        String normalized = normalize(value);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                "A non-blank " + description + " is required"
            );
        }

        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String objectText(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof JsonNode node) {
            return jsonText(node);
        }

        return String.valueOf(value).trim();
    }

    private static String jsonText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }

        if (value.isArray()) {
            if (value.isEmpty()) {
                return "";
            }
            return jsonText(value.get(value.size() - 1));
        }

        return value.asText("").trim();
    }
}
