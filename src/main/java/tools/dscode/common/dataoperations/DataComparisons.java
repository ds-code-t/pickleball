package tools.dscode.common.dataoperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapper.ValueTypes;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;

public final class DataComparisons {

    private DataComparisons() {}


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
            for (ElementMatch.TextOp textOp : elementMatch.textOps) {
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
        List<String> filteredKeys = filterKeys(keyMap, elementMatch, invertComparison);

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
        List<String> filteredKeys = filterKeysForComparisonOnly(keyMap, elementMatch, invertComparison);

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
        List<String> filteredKeys = filterKeysForComparisonOnly(keyMap, elementMatch, invertComparison);
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

        for (ElementMatch.TextOp textOp : elementMatch.textOps) {
            List<String> next = new ArrayList<>();
            for (String key : filtered) {
                boolean matches = matches(key, textOp);
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

    private static boolean matches(String candidate, ElementMatch.TextOp textOp) {
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
        BigInteger a = left.asBigInteger();
        BigInteger b = right.asBigInteger();
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

    private static <T> List<T> applySelection(List<T> input, ElementMatch elementMatch) {
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

    private static <T> List<T> selectByPosition(List<T> input, String elementPosition) {
        return switch (elementPosition) {
            case "", "last" -> List.of(input.get(input.size() - 1));
            case "first" -> List.of(input.get(0));
            default -> {
                int index = Integer.parseInt(elementPosition) - 1;
                yield (index >= 0 && index < input.size())
                        ? List.of(input.get(index))
                        : Collections.emptyList();
            }
        };
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private enum StringMode {
        EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH
    }

    public static List<String> getHeaders(NodeMap map){
        List<String> headers = new ArrayList<>();
        List<JsonNode> rows = map.getAsList(ROW_KEY);
        if (rows == null || rows.isEmpty()) return headers;
        JsonNode oneRow = rows.getFirst();
        if(oneRow instanceof ObjectNode objectNode)
        {
            objectNode.fieldNames().forEachRemaining(headers::add);
        }
        return headers;
    }
}