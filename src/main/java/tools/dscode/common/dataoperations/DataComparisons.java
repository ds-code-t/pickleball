package tools.dscode.common.dataoperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;

public final class DataComparisons {

    private DataComparisons() {
    }

    public static <T> List<T> filterGroupedValues(
            List<String> keyList,
            List<T> valueList,
            ElementMatch elementMatch,
            boolean invertComparison
    ) {
        List<T> filteredValues = new ArrayList<>();

        if (elementMatch.textOps == null || elementMatch.textOps.isEmpty()) {
            filteredValues.addAll(valueList);
            return applySelection(filteredValues, elementMatch);
        }

        int size = Math.min(keyList.size(), valueList.size());
        for (int i = 0; i < size; i++) {
            String key = keyList.get(i);
            boolean matchesAll = true;

            for (ElementMatch.TextOp elementTextOp : elementMatch.textOps) {
                boolean matches = matches(key, elementTextOp);
                if (invertComparison) {
                    matches = !matches;
                }
                if (!matches) {
                    matchesAll = false;
                    break;
                }
            }

            if (matchesAll) {
                filteredValues.add(valueList.get(i));
            }
        }

        return applySelection(filteredValues, elementMatch);
    }

    public static List<List<Object>> filterGroupedValues(
            LinkedListMultimap<String, Object> keyMap,
            ElementMatch elementMatch,
            boolean invertComparison
    ) {
        List<String> filteredKeys = filterKeys(
                keyMap,
                elementMatch,
                invertComparison
        );
        if (filteredKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<Object>> grouped = new ArrayList<>(filteredKeys.size());
        for (String key : filteredKeys) {
            grouped.add(new ArrayList<>(keyMap.get(key)));
        }
        return grouped;
    }

    public static List<Object> filterFlatValues(
            LinkedListMultimap<String, Object> keyMap,
            ElementMatch elementMatch,
            boolean invertComparison
    ) {
        List<String> filteredKeys = filterKeysForComparisonOnly(
                keyMap,
                elementMatch,
                invertComparison
        );

        List<Object> flat = new ArrayList<>();
        for (String key : filteredKeys) {
            flat.addAll(keyMap.get(key));
        }

        return applySelection(flat, elementMatch);
    }

    private static List<String> filterKeys(
            LinkedListMultimap<String, Object> keyMap,
            ElementMatch elementMatch,
            boolean invertComparison
    ) {
        List<String> filteredKeys = filterKeysForComparisonOnly(
                keyMap,
                elementMatch,
                invertComparison
        );
        return applySelection(filteredKeys, elementMatch);
    }

    private static List<String> filterKeysForComparisonOnly(
            LinkedListMultimap<String, Object> keyMap,
            ElementMatch elementMatch,
            boolean invertComparison
    ) {
        List<String> filtered = new ArrayList<>(keyMap.keySet());

        if (elementMatch.textOps == null || elementMatch.textOps.isEmpty()) {
            return filtered;
        }

        for (ElementMatch.TextOp elementTextOp : elementMatch.textOps) {
            List<String> next = new ArrayList<>();
            for (String key : filtered) {
                boolean matches = matches(key, elementTextOp);
                if (invertComparison) {
                    matches = !matches;
                }
                if (matches) {
                    next.add(key);
                }
            }
            filtered = next;
        }

        return filtered;
    }

    private static boolean matches(
            String candidate,
            ElementMatch.TextOp elementTextOp
    ) {
        TextOp textOp = new TextOp(
                elementTextOp.text(),
                elementTextOp.op()
        );
        return TextPredicateMatcher.matches(candidate, textOp);
    }

    private static <T> List<T> applySelection(
            List<T> input,
            ElementMatch elementMatch
    ) {
        if (input.isEmpty()) {
            return Collections.emptyList();
        }

        String selectionType = blankToEmpty(elementMatch.selectionType);
        String elementPosition = blankToEmpty(elementMatch.elementPosition);

        if (selectionType.isEmpty() && elementPosition.isEmpty()) {
            elementPosition = "last";
        }

        if ("any".equals(selectionType) || "every".equals(selectionType)) {
            return input;
        }

        return selectByPosition(input, elementPosition);
    }

    private static <T> List<T> selectByPosition(
            List<T> input,
            String elementPosition
    ) {
        return switch (elementPosition) {
            case "", "last" -> List.of(input.getLast());
            case "first" -> List.of(input.getFirst());
            default -> {
                int index = Integer.parseInt(elementPosition) - 1;
                yield index >= 0 && index < input.size()
                        ? List.of(input.get(index))
                        : Collections.emptyList();
            }
        };
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static List<String> getHeaders(NodeMap map) {
        List<String> headers = new ArrayList<>();
        List<JsonNode> rows = map.getAsList(ROW_KEY);
        if (rows == null || rows.isEmpty()) {
            return headers;
        }

        JsonNode oneRow = rows.getFirst();
        if (oneRow instanceof ObjectNode objectNode) {
            objectNode.fieldNames().forEachRemaining(headers::add);
        }
        return headers;
    }
}
