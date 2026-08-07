package tools.dscode.common.dataelements;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Multimap;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.StringJoiner;

public final class DataStringFormatter {
    private DataStringFormatter() {
    }

    public static String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof DataCandidate candidate) {
            if (candidate.kind() == DataElementKind.DATA_ENTRY) {
                return format(candidate.key()) + "=" + format(candidate.value());
            }
            return format(DataMaterializer.materialize(candidate));
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode.isTextual()
                    ? jsonNode.textValue()
                    : jsonNode.toString();
        }
        if (value instanceof Multimap<?, ?> multimap) {
            StringJoiner joiner = new StringJoiner(", ", "{", "}");
            multimap.entries().forEach(entry ->
                    joiner.add(format(entry.getKey())
                            + "=" + format(entry.getValue()))
            );
            return joiner.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringJoiner joiner = new StringJoiner(", ", "{", "}");
            map.forEach((key, item) ->
                    joiner.add(format(key) + "=" + format(item))
            );
            return joiner.toString();
        }
        if (value instanceof Collection<?> collection) {
            return formatIterable(collection);
        }
        if (value.getClass().isArray()) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                joiner.add(format(Array.get(value, index)));
            }
            return joiner.toString();
        }
        return String.valueOf(value);
    }

    static String tableCell(TabularCell cell) {
        if (cell == null || cell.missing() || cell.value() == null) {
            return "";
        }
        return format(cell.value());
    }

    private static String formatIterable(Iterable<?> values) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        Iterator<?> iterator = values.iterator();
        while (iterator.hasNext()) {
            joiner.add(format(iterator.next()));
        }
        return joiner.toString();
    }
}
