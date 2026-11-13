package dev.domengine.snapshot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/** Checks a document against the draft 2020-12 subset this schema uses. */
public final class SnapshotSchemas {
    private SnapshotSchemas() {
    }

    public static void assertValid(JsonNode schema, JsonNode document) {
        List<String> errors = new ArrayList<>();
        check(schema, schema, document, "$", errors);
        if (!errors.isEmpty()) fail(String.join("\n", errors));
    }

    private static void check(JsonNode root, JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode()) {
            errors.add(path + " missing schema");
            return;
        }
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            if (!ref.startsWith("#/$defs/")) {
                errors.add(path + " bad $ref " + ref);
                return;
            }
            check(root, root.path("$defs").path(ref.substring("#/$defs/".length())), value, path, errors);
            return;
        }
        if (schema.has("anyOf")) {
            List<String> best = null;
            for (JsonNode option : schema.get("anyOf")) {
                List<String> attempt = new ArrayList<>();
                check(root, option, value, path, attempt);
                if (attempt.isEmpty()) return;
                if (best == null || attempt.size() < best.size()) best = attempt;
            }
            if (best != null) errors.addAll(best);
            return;
        }
        if (schema.has("const") && !same(schema.get("const"), value)) {
            errors.add(path + " const " + schema.get("const") + " != " + value);
            return;
        }
        if (schema.has("enum") && !enumHas(schema.get("enum"), value)) {
            errors.add(path + " enum " + value);
            return;
        }
        if (schema.has("type") && !typeOk(schema.get("type"), value)) {
            errors.add(path + " type " + schema.get("type") + " != " + value);
            return;
        }
        if (value != null && value.isObject()) {
            JsonNode properties = schema.path("properties");
            JsonNode additional = schema.get("additionalProperties");
            if (schema.has("required")) {
                for (JsonNode name : schema.get("required")) {
                    if (!value.has(name.asText())) errors.add(path + " missing " + name.asText());
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = properties.get(field.getKey());
                if (child != null) {
                    check(root, child, field.getValue(), path + "." + field.getKey(), errors);
                } else if (additional != null && additional.isObject()) {
                    check(root, additional, field.getValue(), path + "." + field.getKey(), errors);
                } else if (additional != null && additional.isBoolean() && !additional.asBoolean()) {
                    errors.add(path + " unexpected " + field.getKey());
                }
            }
        }
        if (value != null && value.isArray() && schema.has("items")) {
            int index = 0;
            for (JsonNode item : value) {
                check(root, schema.get("items"), item, path + "[" + index + "]", errors);
                index++;
            }
        }
    }

    private static boolean enumHas(JsonNode options, JsonNode value) {
        for (JsonNode option : options) if (same(option, value)) return true;
        return false;
    }

    private static boolean same(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return false;
        if (expected.equals(actual)) return true;
        if (expected.isNumber() && actual.isNumber()) return expected.asDouble() == actual.asDouble();
        if (expected.isTextual() && actual.isTextual()) return expected.asText().equals(actual.asText());
        return false;
    }

    private static boolean typeOk(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode one : type) if (typeOk(one, value)) return true;
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
