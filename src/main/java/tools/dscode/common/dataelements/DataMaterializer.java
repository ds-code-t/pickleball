package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.datatable.DataTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DataMaterializer {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private DataMaterializer() {
    }

    public static Object materialize(DataCandidate candidate) {
        return switch (candidate.kind()) {
            case DATA_TABLE -> candidate.context().materializeDeclaredType();
            case DATA_ROW, DATA_COLUMN -> materializeEntries(candidate.entries());
            case DATA_LIST, DATA_COLUMN_LIST -> immutableList(candidate.value());
            case DATA_ENTRY -> materializeEntry(candidate);
            case DATA_CELL, DATA_HEADER, DATA_VALUE -> candidate.value();
            default -> candidate.value();
        };
    }

    public static Object materializeTerminal(
            List<DataCandidate> candidates,
            DataQuery query
    ) {
        if (query.returnAttribute() != null) {
            if (query.cardinality().many()) {
                List<Object> values = new ArrayList<>(candidates.size());
                for (DataCandidate candidate : candidates) {
                    values.add(candidate.returnProjection(
                            query.returnAttribute()
                    ));
                }
                return Collections.unmodifiableList(values);
            }
            return candidates.getFirst().returnProjection(
                    query.returnAttribute()
            );
        }

        if (!query.cardinality().many()) {
            return materialize(candidates.getFirst());
        }

        return switch (query.kind()) {
            case DATA_ROW, DATA_COLUMN, DATA_ENTRY ->
                    materializeJsonArray(candidates);
            case DATA_TABLE,
                 DATA_LIST,
                 DATA_COLUMN_LIST,
                 DATA_CELL,
                 DATA_HEADER,
                 DATA_VALUE -> materializeJavaList(candidates);
            default -> materializeJavaList(candidates);
        };
    }

    public static DataTable toDataTable(TabularMatrix matrix) {
        List<List<String>> rows = matrix.toStringRows();
        if (rows.isEmpty()) {
            rows = List.of(List.of(""));
        }
        return DataTable.create(rows);
    }

    private static ObjectNode materializeEntries(
            List<DataEntryValue> entries
    ) {
        ObjectNode result = MAPPER.createObjectNode();
        for (DataEntryValue entry : entries) {
            String key = entry.key() == null
                    ? ""
                    : String.valueOf(entry.key());
            JsonNode value = toJsonNode(entry.value());

            JsonNode existing = result.get(key);
            if (existing == null) {
                result.set(key, value);
                continue;
            }

            if (existing instanceof ArrayNode arrayNode) {
                arrayNode.add(value);
                continue;
            }

            ArrayNode duplicates = MAPPER.createArrayNode();
            duplicates.add(existing);
            duplicates.add(value);
            result.set(key, duplicates);
        }
        return result;
    }

    private static ObjectNode materializeEntry(DataCandidate candidate) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.set("Data Header", toJsonNode(candidate.key()));
        entry.set("Data Value", toJsonNode(candidate.value()));
        return entry;
    }

    private static ArrayNode materializeJsonArray(
            List<DataCandidate> candidates
    ) {
        ArrayNode result = MAPPER.createArrayNode();
        for (DataCandidate candidate : candidates) {
            result.add(toJsonNode(materialize(candidate)));
        }
        return result;
    }

    private static List<Object> materializeJavaList(
            List<DataCandidate> candidates
    ) {
        List<Object> result = new ArrayList<>(candidates.size());
        for (DataCandidate candidate : candidates) {
            result.add(materialize(candidate));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<?> immutableList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    private static JsonNode toJsonNode(Object value) {
        if (value instanceof JsonNode jsonNode) {
            return jsonNode.deepCopy();
        }
        return value == null
                ? MAPPER.nullNode()
                : MAPPER.valueToTree(value);
    }
}
