package tools.dscode.common.dataoperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapper.ValueTypes;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ValueFormatting;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.ValueFormatting.NON_SERIALIZABLE_FIELD;
import static tools.dscode.common.mappings.ValueFormatting.nonSerializable;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.util.TableUtils.CELL_KEY;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.ENTRY_KEY;
import static io.cucumber.core.runner.util.TableUtils.HEADER_KEY;
import static io.cucumber.core.runner.util.TableUtils.LIST_KEY;
import static io.cucumber.core.runner.util.TableUtils.MAP_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.VALUE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toListOfStringMultimap;
import static tools.dscode.common.GlobalConstants.META_FLAG;
import static tools.dscode.common.mappings.ValueFormatting.fromSafeJsonNode;
import static tools.dscode.common.treeparsing.preparsing.ParsedLine.createParsedLine;


/**
 * Filters and transforms Cucumber data sources using {@link ElementMatch} criteria.
 * Output is always a {@link NodeMap} whose category-named property is a {@link LinkedListMultimap}.
 */
public final class DataComparator {

    public static final String DATA_KEY_FLAG = META_FLAG + "DATA_KEY_FLAG";
    public static final String DATA_INVERTER_KEY = META_FLAG + "DATA_INVERTER_KEY";
    public static final String DATA_VALUE_FLAG = META_FLAG + "DATA_VALUE_FLAG";

    public static final String COLUMN_KEY = "Data Column";
    public static final String COLUMN_LIST_KEY = "Column List";

    public static final Set<String> DATA_TABLE_ELEMENTS = Set.of(
            TABLE_KEY, ROW_KEY, COLUMN_KEY, CELL_KEY, HEADER_KEY, VALUE_KEY, ENTRY_KEY, COLUMN_LIST_KEY
    );

    private DataComparator() {}

    public static ElementMatch tryParseDataElement(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            List<ElementMatch> matches = parseElementMatchesFromString(spec);
            if (matches.size() == 1 && matches.getFirst().elementTypes.contains(ElementType.DATA_TYPE)) {
                return matches.getFirst();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<ElementMatch> parseElementMatchesFromString(String elementString) {
        StepExtension stepExtension = getRunningStep().modifyStepExtension(" , " + elementString);
        ParsedLine lineData = createParsedLine(stepExtension);
        return lineData.phrases().stream()
                .flatMap(phraseData -> phraseData.getElementMatches().stream())
                .toList();
    }

    public static LinkedListMultimap<String, Object> readCategoryResult(NodeMap output, String category) {
        if (output == null || category == null || category.isBlank()) {
            return LinkedListMultimap.create();
        }
        return asStringObjectMultimap(output.getByNormalizedPath(category));
    }

    public static NodeMap filter(NodeMap input, ElementMatch query, boolean invertComparison) {
        if (input == null || query == null) {
            return emptyResult(query, invertComparison, null);
        }

        LinkedListMultimap<String, Object> stageOne = extractStageOne(input, query);
        LinkedListMultimap<String, Object> stageTwo = applyTextOps(stageOne, query, invertComparison);
        LinkedListMultimap<String, Object> stageThree = applySelection(stageTwo, query);

        return buildOutput(input, query, invertComparison, stageThree);
    }

    // ── Stage 1 ──────────────────────────────────────────────────────────────

    private static LinkedListMultimap<String, Object> extractStageOne(NodeMap input, ElementMatch query) {
        String category = query.categorySingular;
        if (category == null || category.isBlank()) {
            return LinkedListMultimap.create();
        }

        LinkedListMultimap<String, Object> sameCategory = readCategoryMultimap(input, category);
        if (!sameCategory.isEmpty()) {
            return LinkedListMultimap.create(sameCategory);
        }

        DataTable rawTable = readRawDataTable(input);
        if (rawTable != null && category.equals(COLUMN_KEY)) {
            return extractColumnsFromTable(rawTable);
        }
        if (rawTable != null && DATA_TABLE_ELEMENTS.contains(category)) {
            LinkedListMultimap<String, Object> fromTable = extractFromRawTable(rawTable, category);
            if (!fromTable.isEmpty()) {
                return fromTable;
            }
        }

        if (category.equals(MAP_KEY) && rawTable != null) {
            return extractRowLike(rawTable);
        }


        if (category.equals(COLUMN_KEY)) {
            return LinkedListMultimap.create();
        }
        LinkedListMultimap<String, Object> fromJson = extractFromConvertedSources(input, query, rawTable == null);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }

        if (rawTable != null) {
            return extractFromRawTable(rawTable, category);
        }

        return LinkedListMultimap.create();
    }

    private static LinkedListMultimap<String, Object> extractFromRawTable(DataTable table, String category) {
        return switch (category) {
            case ROW_KEY, MAP_KEY -> extractRowLike(table);
            case COLUMN_KEY -> extractColumnsFromTable(table);
            case LIST_KEY -> extractListsFromTable(table);
            case COLUMN_LIST_KEY -> extractColumnListsFromTable(table);
            case ENTRY_KEY -> extractEntriesFromTable(table);
            case CELL_KEY -> extractAllCellsFromTable(table);
            case HEADER_KEY -> extractHeaderCellsFromTable(table);
            case VALUE_KEY -> extractValueCellsFromTable(table);
            case TABLE_KEY -> wrapTypedValues(findTypedValues(readAllSourceRoots(null, table), DataTable.class));
            case DOCSTRING_KEY -> wrapTypedValues(findTypedValues(readAllSourceRoots(null, null), DocString.class));
            default -> LinkedListMultimap.create();
        };
    }

    private static LinkedListMultimap<String, Object> extractFromConvertedSources(
            NodeMap input,
            ElementMatch query,
            boolean jsonPreferredForMap
    ) {
        String category = query.categorySingular;
        List<Object> roots = readAllSourceRoots(input, readRawDataTable(input));

        if (category.equals(MAP_KEY) && !jsonPreferredForMap) {
            return LinkedListMultimap.create();
        }

        LinkedListMultimap<String, Object> merged = LinkedListMultimap.create();
        for (Object root : roots) {
            merged.putAll(extractFromRoot(root, category, null));
        }
        return merged;
    }

    private static LinkedListMultimap<String, Object> extractFromRoot(Object root, String category, String jsonEntryKey) {
        if (root == null) {
            return LinkedListMultimap.create();
        }

        if (root instanceof ListMultimap<?, ?> multimap) {
            if (category.equals(MAP_KEY)) {
                return extractMapFromRowMultimap(multimap);
            }
            return extractFromMultimap(multimap, category);
        }
        if (root instanceof DataTable dataTable) {
            return extractFromRawTable(dataTable, category);
        }

        if (root instanceof JsonNode jsonNode) {
            return extractFromJsonNode(jsonNode, category, jsonEntryKey);
        }

        if (root instanceof Map<?, ?> map) {
            LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                out.putAll(extractFromRoot(entry.getValue(), category, key));
            }
            return out;
        }

        if (root instanceof List<?> list) {
            LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
            for (Object item : list) {
                out.putAll(extractFromRoot(item, category, jsonEntryKey));
            }
            return out;
        }

        if (category.equals(CELL_KEY) || category.equals(VALUE_KEY)) {
            return selfKeyedScalar(unboxScalar(root));
        }

        return LinkedListMultimap.create();
    }

    private static LinkedListMultimap<String, Object> extractFromMultimap(
            ListMultimap<?, ?> multimap,
            String category
    ) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (Map.Entry<?, ?> entry : multimap.entries()) {
            String key = stringValue(entry.getKey());
            Object value = entry.getValue();
            switch (category) {
                case ROW_KEY, MAP_KEY, COLUMN_KEY -> out.put(key, value);
                case LIST_KEY, COLUMN_LIST_KEY -> {
                    if (value instanceof List<?> list) {
                        out.put(listKeyFromList(list), list);
                    } else {
                        out.put(key, value);
                    }
                }
                case ENTRY_KEY -> out.putAll(extractEntriesFromValue(value, key));
                case CELL_KEY -> out.putAll(destructureScalars(value, true));
                case VALUE_KEY -> out.putAll(destructureScalars(value, false));
                case HEADER_KEY -> out.putAll(destructureKeys(value));
                case TABLE_KEY -> {
                    if (value instanceof DataTable) {
                        out.put(key, value);
                    }
                }
                case DOCSTRING_KEY -> {
                    if (value instanceof DocString) {
                        out.put(key, value);
                    }
                }
                default -> out.put(key, value);
            }
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractFromJsonNode(
            JsonNode node,
            String category,
            String jsonEntryKey
    ) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return LinkedListMultimap.create();
        }

        if (node.isValueNode()) {
            if (category.equals(CELL_KEY) || category.equals(VALUE_KEY)) {
                return selfKeyedScalar(unboxJsonScalar(node));
            }
            return LinkedListMultimap.create();
        }

        if (node.isArray()) {
            LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
            for (JsonNode child : node) {
                out.putAll(extractFromJsonNode(child, category, jsonEntryKey));
            }
            return out;
        }

        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        node.properties().forEach(entry -> {
            String fieldKey = entry.getKey();
            out.putAll(extractFromJsonNode(entry.getValue(), category, fieldKey));
        });
        return out;
    }

    private static LinkedListMultimap<String, Object> extractRowLike(DataTable table) {
        List<LinkedListMultimap<String, String>> rows = toListOfStringMultimap(table);
        if (rows == null) {
            return LinkedListMultimap.create();
        }
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (LinkedListMultimap<String, String> row : rows) {
            String key = firstRowCellKey(row);
            out.put(key, row);
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractColumnsFromTable(DataTable table) {
        List<List<String>> rows = table.asLists();
        if (rows == null || rows.isEmpty()) {
            return LinkedListMultimap.create();
        }

        List<List<String>> transposed = transpose(rows);
        if (transposed.isEmpty()) {
            return LinkedListMultimap.create();
        }

        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (List<String> transposedRow : transposed) {
            if (transposedRow.isEmpty()) {
                continue;
            }
            String outerKey = transposedRow.getFirst();
            LinkedListMultimap<String, String> inner = LinkedListMultimap.create();
            for (int i = 1; i < transposedRow.size(); i++) {
                inner.put(outerKey, transposedRow.get(i));
            }
            out.put(outerKey, inner);
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractListsFromTable(DataTable table) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (List<String> row : table.asLists()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            out.put(row.getFirst(), new ArrayList<>(row));
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractColumnListsFromTable(DataTable table) {
        List<List<String>> rows = table.asLists();
        if (rows == null || rows.isEmpty()) {
            return LinkedListMultimap.create();
        }

        int columnCount = rows.stream().mapToInt(List::size).max().orElse(0);
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (int c = 0; c < columnCount; c++) {
            List<String> column = new ArrayList<>();
            for (List<String> row : rows) {
                column.add(c < row.size() ? row.get(c) : "");
            }
            if (!column.isEmpty()) {
                out.put(column.getFirst(), column);
            }
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractEntriesFromTable(DataTable table) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        List<LinkedListMultimap<String, String>> rows = toListOfStringMultimap(table);
        if (rows == null) {
            return out;
        }
        for (LinkedListMultimap<String, String> row : rows) {
            out.putAll(extractEntriesFromValue(row, firstRowCellKey(row)));
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractEntriesFromValue(Object value, String fallbackKey) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        if (value instanceof ListMultimap<?, ?> multimap) {
            for (Map.Entry<?, ?> entry : multimap.entries()) {
                String key = stringValue(entry.getKey());
                out.put(key, singleEntryMap(key, entry.getValue()));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                out.put(key, singleEntryMap(key, entry.getValue()));
            }
            return out;
        }
        if (fallbackKey != null && !fallbackKey.isBlank()) {
            out.put(fallbackKey, singleEntryMap(fallbackKey, value));
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractAllCellsFromTable(DataTable table) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (List<String> row : table.asLists()) {
            if (row == null) {
                continue;
            }
            for (String cell : row) {
                out.put(cell, cell);
            }
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> extractHeaderCellsFromTable(DataTable table) {
        List<List<String>> rows = table.asLists();
        if (rows == null || rows.isEmpty()) {
            return LinkedListMultimap.create();
        }
        return selfKeyedScalars(rows.getFirst());
    }

    private static LinkedListMultimap<String, Object> extractValueCellsFromTable(DataTable table) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        List<List<String>> rows = table.asLists();
        if (rows == null || rows.size() < 2) {
            return out;
        }
        for (int r = 1; r < rows.size(); r++) {
            for (String cell : rows.get(r)) {
                out.put(cell, cell);
            }
        }
        return out;
    }

    private static LinkedListMultimap<String, Object> destructureScalars(Object value, boolean keepKeysAsSelfValues) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        if (value == null) {
            return out;
        }

        if (value instanceof JsonNode jsonNode) {
            if (jsonNode.isValueNode()) {
                Object scalar = unboxJsonScalar(jsonNode);
                return selfKeyedScalar(scalar);
            }
            if (jsonNode.isArray()) {
                for (JsonNode child : jsonNode) {
                    out.putAll(destructureScalars(child, keepKeysAsSelfValues));
                }
                return out;
            }
            jsonNode.properties().forEach(entry -> {
                if (keepKeysAsSelfValues) {
                    out.putAll(destructureScalars(entry.getValue(), true));
                } else {
                    out.putAll(destructureScalars(entry.getValue(), false));
                }
            });
            return out;
        }

        if (value instanceof ListMultimap<?, ?> multimap) {
            for (Map.Entry<?, ?> entry : multimap.entries()) {
                if (keepKeysAsSelfValues) {
                    out.putAll(destructureScalars(entry.getValue(), true));
                } else {
                    out.putAll(destructureScalars(entry.getValue(), false));
                }
            }
            return out;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (keepKeysAsSelfValues) {
                    out.putAll(destructureScalars(entry.getValue(), true));
                } else {
                    out.putAll(destructureScalars(entry.getValue(), false));
                }
            }
            return out;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.putAll(destructureScalars(item, keepKeysAsSelfValues));
            }
            return out;
        }

        if (isScalar(value)) {
            return selfKeyedScalar(value);
        }

        return out;
    }

    private static LinkedListMultimap<String, Object> destructureKeys(Object value) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        if (value == null) {
            return out;
        }

        if (value instanceof JsonNode jsonNode) {
            if (jsonNode.isObject()) {
                jsonNode.fieldNames().forEachRemaining(field -> out.put(field, field));
            } else if (jsonNode.isArray()) {
                for (JsonNode child : jsonNode) {
                    out.putAll(destructureKeys(child));
                }
            }
            return out;
        }

        if (value instanceof ListMultimap<?, ?> multimap) {
            for (Object key : multimap.keySet()) {
                String keyString = stringValue(key);
                out.put(keyString, keyString);
            }
            return out;
        }

        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                String keyString = stringValue(key);
                out.put(keyString, keyString);
            }
            return out;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.putAll(destructureKeys(item));
            }
        }

        return out;
    }

    // ── Stage 2 ──────────────────────────────────────────────────────────────

    private static LinkedListMultimap<String, Object> applyTextOps(
            LinkedListMultimap<String, Object> input,
            ElementMatch query,
            boolean invertComparison
    ) {
        if (input == null || input.isEmpty()) {
            return LinkedListMultimap.create();
        }
        if (query.textOps == null || query.textOps.isEmpty()) {
            return LinkedListMultimap.create(input);
        }

        LinkedListMultimap<String, Object> filtered = LinkedListMultimap.create();
        for (Map.Entry<String, Object> entry : input.entries()) {
            String key = entry.getKey();
            boolean matchesAll = true;
            for (ElementMatch.TextOp textOp : query.textOps) {
                boolean matches = matches(key, textOp);
                if (invertComparison) {
                    matches = !matches;
                }
                if (!matches) {
                    matchesAll = false;
                    break;
                }
            }
            if (matchesAll) {
                filtered.put(key, entry.getValue());
            }
        }
        return filtered;
    }

    static boolean matches(String candidate, ElementMatch.TextOp textOp) {
        ValueWrapper left = ValueWrapper.createValueWrapper(candidate);
        ValueWrapper right = textOp.text();
        ExecutionDictionary.Op op = textOp.op();
        return switch (op) {
            case DEFAULT, EQUALS -> compareStrings(left, right, StringMode.EQUALS);
            case CONTAINS -> compareStrings(left, right, StringMode.CONTAINS);
            case STARTS_WITH -> compareStrings(left, right, StringMode.STARTS_WITH);
            case ENDS_WITH -> compareStrings(left, right, StringMode.ENDS_WITH);
            case MATCHES -> Pattern.compile(right.toNonNullString()).matcher(candidate == null ? "" : candidate).find();
            case GT -> compareNumbers(left, right) > 0;
            case GTE -> compareNumbers(left, right) >= 0;
            case LT -> compareNumbers(left, right) < 0;
            case LTE -> compareNumbers(left, right) <= 0;
            case HAS, HAS_NOT -> true;
        };
    }

    private static int compareNumbers(ValueWrapper left, ValueWrapper right) {
        BigInteger a = left.asForcedSimpleNumber();
        BigInteger b = right.asForcedSimpleNumber();
        return a.compareTo(b);
    }

    private static boolean compareStrings(ValueWrapper left, ValueWrapper right, StringMode mode) {
        ValueTypes type = right.type;
        String a;
        String b;
        boolean ignoreCase = false;

        switch (type) {
            case DOUBLE_QUOTED -> {
                a = left.asNormalizedText();
                b = right.asNormalizedText();
            }
            case SINGLE_QUOTED -> {
                a = left.asNormalizedText();
                b = right.asNormalizedText();
                ignoreCase = true;
            }
            case BACK_TICKED -> {
                a = left.getValue() == null ? "" : left.getValue().toString();
                b = right.getValue() == null ? "" : right.getValue().toString();
            }
            case TILDE_QUOTED -> {
                ValueWrapper leftStripped = left.stripAllNonLetters();
                ValueWrapper rightStripped = right.stripAllNonLetters();
                a = leftStripped == null ? "" : leftStripped.toNonNullString();
                b = rightStripped == null ? "" : rightStripped.toNonNullString();
                ignoreCase = true;
            }
            default -> {
                a = left.asNormalizedText();
                b = right.asNormalizedText();
            }
        }

        a = a == null ? "" : a;
        b = b == null ? "" : b;

        if (ignoreCase) {
            String aa = a.toLowerCase();
            String bb = b.toLowerCase();
            return switch (mode) {
                case EQUALS -> aa.equals(bb);
                case CONTAINS -> aa.contains(bb);
                case STARTS_WITH -> aa.startsWith(bb);
                case ENDS_WITH -> aa.endsWith(bb);
            };
        }

        return switch (mode) {
            case EQUALS -> a.equals(b);
            case CONTAINS -> a.contains(b);
            case STARTS_WITH -> a.startsWith(b);
            case ENDS_WITH -> a.endsWith(b);
        };
    }

    // ── Stage 3 ──────────────────────────────────────────────────────────────

    private static LinkedListMultimap<String, Object> applySelection(
            LinkedListMultimap<String, Object> input,
            ElementMatch query
    ) {
        if (input == null || input.isEmpty()) {
            return LinkedListMultimap.create();
        }

        String selectionType = blankToEmpty(query.selectionType);
        String elementPosition = blankToEmpty(query.elementPosition);
        if (selectionType.isEmpty() && elementPosition.isEmpty()) {
            selectionType = "last";
        }

        if ("any".equals(selectionType) || "every".equals(selectionType)) {
            if ("every".equals(selectionType) && !elementPosition.isEmpty()) {
                return selectEveryNth(input, elementPosition);
            }
            return LinkedListMultimap.create(input);
        }

        if (elementPosition.isEmpty()) {
            elementPosition = "last";
        }
        return selectByPosition(input, elementPosition);
    }

    private static LinkedListMultimap<String, Object> selectByPosition(
            LinkedListMultimap<String, Object> input,
            String elementPosition
    ) {
        List<Map.Entry<String, Object>> entries = new ArrayList<>(input.entries());
        if (entries.isEmpty()) {
            return LinkedListMultimap.create();
        }

        int index = switch (elementPosition) {
            case "first" -> 0;
            case "last" -> entries.size() - 1;
            default -> Integer.parseInt(elementPosition) - 1;
        };

        if (index < 0 || index >= entries.size()) {
            return LinkedListMultimap.create();
        }

        Map.Entry<String, Object> selected = entries.get(index);
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        out.put(selected.getKey(), selected.getValue());
        return out;
    }

    private static LinkedListMultimap<String, Object> selectEveryNth(
            LinkedListMultimap<String, Object> input,
            String elementPosition
    ) {
        int step = Integer.parseInt(elementPosition);
        if (step <= 0) {
            return LinkedListMultimap.create();
        }

        List<Map.Entry<String, Object>> entries = new ArrayList<>(input.entries());
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (int i = step - 1; i < entries.size(); i += step) {
            Map.Entry<String, Object> entry = entries.get(i);
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    // ── Output / input helpers ───────────────────────────────────────────────

    private static NodeMap buildOutput(
            NodeMap input,
            ElementMatch query,
            boolean invertComparison,
            LinkedListMultimap<String, Object> results
    ) {
        NodeMap output = new NodeMap();
        String category = query == null ? "" : query.categorySingular;
        if (category != null && !category.isBlank()) {
            storeRetainingReference(output, category, results == null ? LinkedListMultimap.create() : results);
        }
        if (query != null) {
            output.put(DATA_KEY_FLAG, query);
        }
        output.put(DATA_INVERTER_KEY, invertComparison);
        if (input != null) {
            Object rawValue = readMetadataValue(input, DATA_VALUE_FLAG);
            if (rawValue != null) {
                output.put(DATA_VALUE_FLAG, rawValue);
            }
        }
        return output;
    }

    private static NodeMap emptyResult(ElementMatch query, boolean invertComparison, NodeMap input) {
        return buildOutput(input, query, invertComparison, LinkedListMultimap.create());
    }


    private static Object readMetadataValue(NodeMap input, String key) {
        if (input == null || key == null) {
            return null;
        }
        Object viaTokenized = input.get(key);
        if (viaTokenized != null) {
            return viaTokenized;
        }
        return input.getByNormalizedPath(key);
    }

    private static void storeRetainingReference(NodeMap output, String key, Object value) {
        if (output == null || key == null) {
            return;
        }
        if (value == null) {
            output.getRoot().set(key, JsonNodeFactory.instance.nullNode());
            return;
        }
        String id = UUID.randomUUID().toString();
        nonSerializable.put(id, value);
        ObjectNode refNode = MAPPER.createObjectNode();
        refNode.put(NON_SERIALIZABLE_FIELD, id);
        output.getRoot().set(key, refNode);
    }
    private static DataTable readRawDataTable(NodeMap input) {
        if (input == null) {
            return null;
        }
        Object raw = readMetadataValue(input, DATA_VALUE_FLAG);
        if (raw instanceof DataTable dataTable) {
            return dataTable;
        }
        return null;
    }

    private static LinkedListMultimap<String, Object> readCategoryMultimap(NodeMap input, String category) {
        if (input == null || category == null || category.isBlank()) {
            return LinkedListMultimap.create();
        }
        Object stored = input.getByNormalizedPath(category);
        return asStringObjectMultimap(stored);
    }

    @SuppressWarnings("unchecked")
    private static LinkedListMultimap<String, Object> asStringObjectMultimap(Object stored) {
        if (stored == null) {
            return LinkedListMultimap.create();
        }
        if (stored instanceof LinkedListMultimap<?, ?> multimap) {
            LinkedListMultimap<String, Object> copy = LinkedListMultimap.create();
            multimap.entries().forEach(e -> copy.put(stringValue(e.getKey()), e.getValue()));
            return copy;
        }
        if (stored instanceof ListMultimap<?, ?> multimap) {
            LinkedListMultimap<String, Object> copy = LinkedListMultimap.create();
            multimap.entries().forEach(e -> copy.put(stringValue(e.getKey()), e.getValue()));
            return copy;
        }
        if (stored instanceof JsonNode jsonNode) {
            Object restored = fromSafeJsonNode(jsonNode);
            return asStringObjectMultimap(restored);
        }
        return LinkedListMultimap.create();
    }

    private static List<Object> readAllSourceRoots(NodeMap input, DataTable rawTable) {
        List<Object> roots = new ArrayList<>();
        if (input != null) {
            ElementMatch priorKey = readElementMatch(readMetadataValue(input, DATA_KEY_FLAG));
            if (priorKey != null && priorKey.categorySingular != null) {
                LinkedListMultimap<String, Object> prior = readCategoryMultimap(input, priorKey.categorySingular);
                if (!prior.isEmpty()) {
                    roots.addAll(prior.values());
                }
            }
            for (String category : DATA_TABLE_ELEMENTS) {
                if (priorKey != null && category.equals(priorKey.categorySingular)) {
                    continue;
                }
                LinkedListMultimap<String, Object> property = readCategoryMultimap(input, category);
                if (!property.isEmpty()) {
                    roots.addAll(property.values());
                }
            }
        }
        if (rawTable != null) {
            roots.add(rawTable);
        }
        return roots;
    }

    private static ElementMatch readElementMatch(Object value) {
        if (value instanceof ElementMatch elementMatch) {
            return elementMatch;
        }
        return null;
    }

    private static <T> LinkedListMultimap<String, Object> wrapTypedValues(List<Map.Entry<String, T>> entries) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        for (Map.Entry<String, T> entry : entries) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static <T> List<Map.Entry<String, T>> findTypedValues(List<Object> roots, Class<T> type) {
        List<Map.Entry<String, T>> found = new ArrayList<>();
        for (Object root : roots) {
            collectTypedValues(root, null, type, found);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static <T> void collectTypedValues(Object node, String key, Class<T> type, List<Map.Entry<String, T>> found) {
        if (node == null) {
            return;
        }
        if (type.isInstance(node)) {
            found.add(Map.entry(key == null ? "" : key, (T) node));
            return;
        }
        if (node instanceof ListMultimap<?, ?> multimap) {
            for (Map.Entry<?, ?> entry : multimap.entries()) {
                collectTypedValues(entry.getValue(), stringValue(entry.getKey()), type, found);
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectTypedValues(entry.getValue(), stringValue(entry.getKey()), type, found);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectTypedValues(item, key, type, found);
            }
            return;
        }
        if (node instanceof JsonNode jsonNode) {
            if (jsonNode.isObject()) {
                jsonNode.properties().forEach(entry ->
                        collectTypedValues(fromSafeJsonNode(entry.getValue()), entry.getKey(), type, found));
            } else if (jsonNode.isArray()) {
                for (JsonNode child : jsonNode) {
                    collectTypedValues(fromSafeJsonNode(child), key, type, found);
                }
            }
        }
    }

    private static List<List<String>> transpose(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
        List<List<String>> transposed = new ArrayList<>();
        for (int c = 0; c < maxCols; c++) {
            List<String> column = new ArrayList<>();
            for (List<String> row : rows) {
                column.add(c < row.size() ? row.get(c) : "");
            }
            transposed.add(column);
        }
        return transposed;
    }

    private static LinkedListMultimap<String, Object> extractMapFromRowMultimap(ListMultimap<?, ?> row) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : row.entries()) {
            map.put(stringValue(entry.getKey()), entry.getValue());
        }
        out.put(firstRowCellKey(row), map);
        return out;
    }

    private static String firstRowCellKey(ListMultimap<?, ?> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        return stringValue(row.values().iterator().next());
    }


    private static String listKeyFromList(List<?> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        return stringValue(list.getFirst());
    }

    private static LinkedListMultimap<String, Object> selfKeyedScalar(Object scalar) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        if (scalar == null) {
            return out;
        }
        String key = stringValue(scalar);
        out.put(key, scalar);
        return out;
    }

    private static LinkedListMultimap<String, Object> selfKeyedScalars(List<String> scalars) {
        LinkedListMultimap<String, Object> out = LinkedListMultimap.create();
        if (scalars == null) {
            return out;
        }
        for (String scalar : scalars) {
            out.put(scalar, scalar);
        }
        return out;
    }

    private static Map<String, Object> singleEntryMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Object unboxJsonScalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isLong()) {
            return node.longValue();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText("");
    }

    private static Object unboxScalar(Object value) {
        if (value instanceof JsonNode jsonNode) {
            return unboxJsonScalar(jsonNode);
        }
        return value;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || (value instanceof JsonNode node && node.isValueNode());
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonNode jsonNode && jsonNode.isValueNode()) {
            return jsonNode.asText("");
        }
        return String.valueOf(value);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private enum StringMode {
        EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH
    }
}
