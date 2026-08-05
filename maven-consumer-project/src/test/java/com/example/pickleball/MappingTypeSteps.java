package com.example.pickleball;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.List;
import java.util.Map;

import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.ValueFormatting.fromSafeJsonNode;

/**
 * Consumer-only assertions for MappingSteps value preservation.
 *
 * <p>"HAS PRESERVED TYPE" inspects the underlying Jackson tree so that an
 * ObjectNode remains distinguishable from a Java Map and an ArrayNode remains
 * distinguishable from a Java List. Scalar JsonNodes are converted to their
 * normal Java values before their types are asserted.</p>
 *
 * <p>"RUN MAP QUERY ... RETURNS TYPE" exercises the public NodeMap/Tokenized
 * read behavior. Structured results remain Jackson ObjectNode/ArrayNode values,
 * including explicit terminal {@code []} collection queries.</p>
 */
public final class MappingTypeSteps {
    public MappingTypeSteps() {
    }

    @Given("^RETURN TYPE TEST OBJECT NODE$")
    public static ObjectNode returnTypeTestObjectNode() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("kind", "dynamic-object");
        result.putObject("nested")
                .put("count", 7)
                .put("active", true);
        return result;
    }

    @Then("^RUN MAP PATH \"([^\"]+)\" HAS PRESERVED TYPE \"([^\"]+)\"$")
    public static void assertRunMapPathType(
            String path,
            String expectedType
    ) {
        Object value = storedRunMapValue(path);
        if (!matchesType(value, expectedType)) {
            JsonNode rawValue = rawRunMapValue(path);
            throw new AssertionError(
                    "Expected RUN map path '" + path + "' to preserve type '"
                            + expectedType
                            + "', but preserved type was '"
                            + typeName(value)
                            + "' and raw stored node type was '"
                            + typeName(rawValue)
                            + "'. Value: " + value
            );
        }
    }

    @Then("^RUN MAP QUERY \"([^\"]+)\" RETURNS TYPE \"([^\"]+)\"$")
    public static void assertRunMapQueryType(
            String query,
            String expectedType
    ) {
        Object value = queriedRunMapValue(query);
        if (!matchesType(value, expectedType)) {
            throw new AssertionError(
                    "Expected RUN map query '" + query + "' to return type '"
                            + expectedType
                            + "', but returned type was '"
                            + typeName(value)
                            + "'. Value: " + value
            );
        }
    }

    @Then("^RUN MAP PATH \"([^\"]+)\" HAS VALUE \"([^\"]*)\"$")
    public static void assertRunMapPathValue(
            String path,
            String expectedValue
    ) {
        Object value = queriedRunMapValue(path);
        String actualValue = String.valueOf(value);
        if (!expectedValue.equals(actualValue)) {
            throw new AssertionError(
                    "Expected RUN map path '" + path + "' to have value '"
                            + expectedValue + "', but was '" + actualValue + "'."
            );
        }
    }

    /**
     * Returns the value as it is stored in the Jackson-backed RUN map.
     * Container nodes remain ObjectNode/ArrayNode. Scalar nodes become their
     * Java scalar values.
     */
    private static Object storedRunMapValue(String path) {
        JsonNode rawValue = rawRunMapValue(path);

        if (rawValue == null || rawValue.isNull()) {
            return null;
        }

        if (rawValue.isContainerNode()) {
            return rawValue;
        }
        return fromSafeJsonNode(rawValue);
    }

    /**
     * Exercises the public NodeMap/Tokenized query path.
     */
    private static Object queriedRunMapValue(String query) {
        return getRunMap().get(new Tokenized(requirePath(query)));
    }

    /**
     * Reads directly from the underlying Jackson tree for storage assertions.
     */
    private static JsonNode rawRunMapValue(String path) {
        String checkedPath = requirePath(path);
        String expression = Tokenized.preprocessReadQuery(checkedPath);
        return Tokenized.evaluate(getRunMap().getRoot(), expression);
    }

    private static boolean matchesType(Object value, String expectedType) {
        return switch (expectedType) {
            case "Map" -> value instanceof Map<?, ?>;
            case "List" -> value instanceof List<?>;
            default -> value != null
                    && expectedType.equals(value.getClass().getSimpleName());
        };
    }

    private static String typeName(Object value) {
        return value == null
                ? "null"
                : value.getClass().getSimpleName();
    }

    private static String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "RUN map path cannot be null or blank"
            );
        }
        return path.trim();
    }
}
