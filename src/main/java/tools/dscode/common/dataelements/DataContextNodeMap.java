package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import io.cucumber.datatable.DataTable;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DataContextNodeMap extends NodeMap {
    private final DataSelection selection;
    private final DataCandidate candidate;

    private DataContextNodeMap(
            DataSelection selection,
            DataCandidate candidate
    ) {
        super(MapConfigurations.MapType.PHRASE_MAP);
        this.selection = selection;
        this.candidate = candidate;
    }

    public static DataContextNodeMap forSelection(
            DataSelection selection
    ) {
        return new DataContextNodeMap(
                Objects.requireNonNull(selection),
                null
        );
    }

    public static DataContextNodeMap forCandidate(
            DataCandidate candidate
    ) {
        return new DataContextNodeMap(
                null,
                Objects.requireNonNull(candidate)
        );
    }

    public static List<?> contextualValues(
            DataExecutionResult result
    ) {
        Objects.requireNonNull(result, "result");
        if (result instanceof ContextResult contextResult
                && contextResult.selection()
                .query()
                .returnAttribute() == null) {
            return Collections.singletonList(
                    forSelection(contextResult.selection())
            );
        }
        if (result instanceof IterationResult iterationResult
                && iterationResult.selection()
                .query()
                .returnAttribute() == null) {
            List<DataContextNodeMap> values = new ArrayList<>(
                    iterationResult.selection().size()
            );
            iterationResult.selection()
                    .candidates()
                    .forEach(item ->
                            values.add(forCandidate(item))
                    );
            return Collections.unmodifiableList(values);
        }
        return result.values();
    }

    public DataContext context() {
        return selectedCandidate().context();
    }

    public Object materialize() {
        return candidate != null
                ? DataMaterializer.materialize(candidate)
                : selection.materializeTerminal();
    }

    @Override
    protected ObjectNode materializeRoot() {
        Object value = materialize();
        ObjectNode projected = project(value);
        projected.set(
                MAP_TYPE_KEY,
                toSafeJsonNode(getMapType())
        );
        return projected;
    }

    @Override
    protected void writeValue(
            Tokenized query,
            Object value
    ) {
        DataCandidate selected = writableCandidate();
        MutationPath path = MutationPath.from(query);

        switch (selected.kind()) {
            case DATA_TABLE -> replaceTableCell(
                    selected.context(),
                    path,
                    value
            );
            case DATA_ROW, DATA_COLUMN -> replaceEntry(
                    selected,
                    path,
                    value
            );
            case DATA_LIST, DATA_COLUMN_LIST ->
                    replaceTabularListItem(
                            selected,
                            path,
                            value
                    );
            case DATA_CELL, DATA_ENTRY, DATA_HEADER, DATA_VALUE ->
                    replaceTabularScalar(
                            selected,
                            path,
                            value
                    );
            case LIST -> replaceListItem(
                    selected,
                    path,
                    value
            );
            case MAP -> replaceMapValue(
                    selected,
                    path,
                    value
            );
            case MULTIMAP -> replaceMultimapValue(
                    selected,
                    path,
                    value
            );
            case SET -> throw unsupported(
                    "Replacing Set members is structural mutation."
            );
            default -> throw unsupported(
                    selected.kind().singularName()
                            + " mutation is not implemented."
            );
        }
    }

    @Override
    protected void removeValue(String key) {
        throw unsupported(
                "Removing values is structural mutation."
        );
    }

    @Override
    protected void clearAllValues() {
        throw unsupported(
                "Clearing a Data Element context is structural mutation."
        );
    }

    @Override
    public Object getByNormalizedPath(String path) {
        return new NodeMap(materializeRoot())
                .getByNormalizedPath(path);
    }

    @Override
    public String getStringValue(String path) {
        Object value = getByNormalizedPath(path);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public void putReference(String key, JsonNode value) {
        throw unsupported(
                "Reference writes cannot replace a Data Element cursor."
        );
    }

    @Override
    public void merge(ObjectNode other) {
        throw unsupported(
                "Merging would change the Data Element shape."
        );
    }

    @Override
    public void merge(Map<?, ?> other) {
        throw unsupported(
                "Merging would change the Data Element shape."
        );
    }

    @Override
    public void merge(LinkedListMultimap<?, ?> other) {
        throw unsupported(
                "Merging would change the Data Element shape."
        );
    }

    @Override
    public void merge(List<?> keys, List<?> values) {
        throw unsupported(
                "Merging would change the Data Element shape."
        );
    }

    private DataCandidate selectedCandidate() {
        if (candidate != null) {
            return candidate;
        }
        if (selection == null || selection.isEmpty()) {
            throw new DataQueryException(
                    "The Data Element context has no selected cursor."
            );
        }
        return selection.first();
    }

    private DataCandidate writableCandidate() {
        if (candidate != null) {
            return candidate;
        }
        if (selection == null || selection.size() != 1) {
            throw new DataQueryException(
                    "A mutable Data Element context must resolve to "
                            + "exactly one cursor. Iterate plural "
                            + "selections before writing."
            );
        }
        return selection.first();
    }

    private static void replaceTableCell(
            DataContext context,
            MutationPath path,
            Object value
    ) {
        if (path.parts().size() != 2) {
            throw unsupported(
                    "Data Table replacement requires row.column indexes."
            );
        }
        context.replaceCell(
                new DataCoordinate(
                        path.index(0),
                        path.index(1)
                ),
                value
        );
    }

    private static void replaceEntry(
            DataCandidate candidate,
            MutationPath path,
            Object value
    ) {
        String key = path.requiredPart(0);
        Integer requestedOccurrence =
                path.optionalIndex(1);
        List<DataEntryValue> matches = candidate.currentEntries()
                .stream()
                .filter(entry -> sameKey(entry.key(), key))
                .toList();
        if (matches.isEmpty()) {
            throw unsupported(
                    "Adding a new table key is structural mutation: "
                            + key
            );
        }
        int occurrence = requestedOccurrence == null
                ? matches.size() - 1
                : requestedOccurrence;
        if (occurrence < 0 || occurrence >= matches.size()) {
            throw unsupported(
                    "The requested duplicate key occurrence does not "
                            + "exist: " + key + "[" + occurrence + "]"
            );
        }
        candidate.context().replaceCell(
                matches.get(occurrence).coordinate(),
                value
        );
    }

    private static void replaceTabularListItem(
            DataCandidate candidate,
            MutationPath path,
            Object value
    ) {
        int index = path.valueIndex();
        DataCoordinate anchor = candidate.coordinate();
        DataCoordinate coordinate =
                candidate.kind() == DataElementKind.DATA_LIST
                        ? new DataCoordinate(
                                anchor.row(),
                                index
                        )
                        : new DataCoordinate(
                                index,
                                anchor.column()
                        );
        candidate.context().replaceCell(coordinate, value);
    }

    private static void replaceTabularScalar(
            DataCandidate candidate,
            MutationPath path,
            Object value
    ) {
        path.requireScalarValuePath();
        DataCoordinate coordinate = candidate.coordinate();
        if (candidate.kind() == DataElementKind.DATA_HEADER) {
            coordinate = new DataCoordinate(
                    0,
                    coordinate.column()
            );
        }
        candidate.context().replaceCell(coordinate, value);
    }

    private static void replaceListItem(
            DataCandidate candidate,
            MutationPath path,
            Object value
    ) {
        candidate.context().replaceListItem(
                candidate.value(),
                path.valueIndex(),
                value
        );
    }

    private static void replaceMapValue(
            DataCandidate candidate,
            MutationPath path,
            Object value
    ) {
        String requestedKey = path.requiredPart(0);
        Map<?, ?> current = requireMap(candidate.currentValue());
        Object actualKey = current.keySet()
                .stream()
                .filter(key -> sameKey(key, requestedKey))
                .reduce((first, second) -> second)
                .orElseThrow(() -> unsupported(
                        "Adding a new Map key is structural mutation: "
                                + requestedKey
                ));
        candidate.context().replaceMapValue(
                candidate.value(),
                actualKey,
                value
        );
    }

    private static void replaceMultimapValue(
            DataCandidate candidate,
            MutationPath path,
            Object value
    ) {
        String requestedKey = path.requiredPart(0);
        Multimap<?, ?> current =
                requireMultimap(candidate.currentValue());
        Object actualKey = current.keys()
                .stream()
                .filter(key -> sameKey(key, requestedKey))
                .findFirst()
                .orElseThrow(() -> unsupported(
                        "Adding a new Multimap key is structural mutation: "
                                + requestedKey
                ));
        int count = (int) current.entries()
                .stream()
                .filter(entry -> Objects.equals(
                        entry.getKey(),
                        actualKey
                ))
                .count();
        Integer requestedOccurrence =
                path.optionalIndex(1);
        int occurrence = requestedOccurrence == null
                ? count - 1
                : requestedOccurrence;
        candidate.context().replaceMultimapValue(
                candidate.value(),
                actualKey,
                occurrence,
                value
        );
    }

    private static Map<?, ?> requireMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new DataQueryException(
                "The selected cursor is not a Map."
        );
    }

    private static Multimap<?, ?> requireMultimap(Object value) {
        if (value instanceof Multimap<?, ?> multimap) {
            return multimap;
        }
        throw new DataQueryException(
                "The selected cursor is not a Multimap."
        );
    }

    private static boolean sameKey(
            Object actual,
            String requested
    ) {
        return Objects.equals(
                String.valueOf(actual),
                requested
        );
    }

    private static ObjectNode project(Object value) {
        if (value instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        if (value instanceof DataTable dataTable) {
            ObjectNode result = MAPPER.createObjectNode();
            result.set(
                    "Row",
                    MAPPER.valueToTree(dataTable.cells())
            );
            return result;
        }
        if (value instanceof Multimap<?, ?> multimap) {
            Map<Object, Collection<Object>> grouped =
                    new LinkedHashMap<>();
            multimap.asMap().forEach((key, values) ->
                    grouped.put(
                            key,
                            new ArrayList<Object>(values)
                    )
            );
            return requireObjectNode(grouped);
        }
        JsonNode node = toSafeJsonNode(value);
        if (node instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }

        ObjectNode result = MAPPER.createObjectNode();
        if (node instanceof ArrayNode arrayNode) {
            result.set("value", arrayNode.deepCopy());
        } else {
            result.set("value", node);
        }
        return result;
    }

    private static ObjectNode requireObjectNode(Object value) {
        JsonNode node = toSafeJsonNode(value);
        if (node instanceof ObjectNode objectNode) {
            return objectNode.deepCopy();
        }
        throw new DataQueryException(
                "The Data Element projection is not object-shaped."
        );
    }

    private static DataQueryException unsupported(
            String detail
    ) {
        return new DataQueryException(
                detail + " Phase 5 supports replacement of existing "
                        + "cells, List items, Map values, and Multimap "
                        + "entry values only."
        );
    }

    private record MutationPath(List<String> parts) {
        private static MutationPath from(Tokenized query) {
            String source = query.originalQuery.strip();
            if (source.isEmpty()) {
                throw unsupported(
                        "A replacement path is required."
                );
            }
            List<String> parts = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean quoted = false;
            for (int index = 0; index < source.length(); index++) {
                char character = source.charAt(index);
                if (character == '`') {
                    quoted = !quoted;
                    continue;
                }
                if (!quoted
                        && (character == '.'
                        || character == '['
                        || character == ']')) {
                    addPart(parts, current);
                    continue;
                }
                current.append(character);
            }
            addPart(parts, current);
            return new MutationPath(List.copyOf(parts));
        }

        private static void addPart(
                List<String> parts,
                StringBuilder current
        ) {
            String part = current.toString().strip();
            current.setLength(0);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }

        private String requiredPart(int index) {
            if (index < 0 || index >= parts.size()) {
                throw unsupported(
                        "The replacement path is incomplete."
                );
            }
            return parts.get(index);
        }

        private int index(int position) {
            String part = requiredPart(position);
            try {
                return Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                throw unsupported(
                        "Expected a zero-based numeric index but found: "
                                + part
                );
            }
        }

        private Integer optionalIndex(int position) {
            return position < parts.size()
                    ? index(position)
                    : null;
        }

        private int valueIndex() {
            int position = parts.size() > 1
                    && parts.getFirst().equalsIgnoreCase("value")
                    ? 1
                    : 0;
            return index(position);
        }

        private void requireScalarValuePath() {
            if (parts.size() != 1) {
                throw unsupported(
                        "A scalar cursor accepts only the value path."
                );
            }
            String part = parts.getFirst();
            if (!part.equalsIgnoreCase("value")
                    && !part.equalsIgnoreCase("Data Value")) {
                throw unsupported(
                        "A scalar cursor accepts only the value path."
                );
            }
        }
    }
}
