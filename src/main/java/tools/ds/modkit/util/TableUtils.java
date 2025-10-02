package tools.ds.modkit.util;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;

import java.util.*;

import static tools.ds.modkit.util.CucumberQueryUtil.*;

public class TableUtils {

    public static <K, V>
    LinkedListMultimap<String, LinkedListMultimap<K, V>> toRowsMultimap(DataTable dataTable) {
        List<LinkedListMultimap<K, V>> rowList = toListOfMultimap(dataTable);
        LinkedListMultimap<String, LinkedListMultimap<K, V>> returnMap = LinkedListMultimap.create();
        assert rowList != null;
        rowList.forEach(r -> returnMap.put("ROW", r));
        return returnMap;
    }

    public static <K, V> List<LinkedListMultimap<K, V>> toListOfMultimap(DataTable dataTable) {
        List<List<String>> lists = dataTable.asLists();
        if (lists == null || lists.isEmpty()) {
            return List.of();
        }

        List<String> headerStrings = lists.getFirst();
        if (headerStrings == null) {
            return null; // preserves original behavior
        }

        @SuppressWarnings("unchecked")
        List<? extends K> header = (List<? extends K>) headerStrings;

        List<LinkedListMultimap<K, V>> out = new ArrayList<>();
        for (int r = 1; r < lists.size(); r++) {
            List<String> rowStrings = lists.get(r);
            @SuppressWarnings("unchecked")
            List<? extends V> row = (List<? extends V>) rowStrings;
            out.add(toMultimap(header, row)); // your existing generic helper
        }
        return out;
    }


    public static <K, V> LinkedListMultimap<K, V> toFlatMultimap(List<? extends List<?>> table) {
        if (table == null || table.isEmpty() || table.size() < 2) {
            throw new IllegalArgumentException("Input table must not be null or empty");
        }

        // First row is the header
        List<?> header = table.getFirst();
        LinkedListMultimap<K, V> multimap = LinkedListMultimap.create();

        // Remaining rows are values
        for (int r = 1; r < table.size(); r++) {
            List<?> row = table.get(r);
            if (row.size() != header.size()) {
                throw new IllegalArgumentException(
                        "Row " + r + " size " + row.size() + " does not match header size " + header.size()
                );
            }
            for (int c = 0; c < header.size(); c++) {
                @SuppressWarnings("unchecked")
                K key = (K) header.get(c);
                @SuppressWarnings("unchecked")
                V value = (V) row.get(c);
                multimap.put(key, value);
            }
        }

        return multimap;
    }


    public static <K, V> LinkedListMultimap<K, V> toMultimap(List<? extends K> keys, List<? extends V> values) {
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("Keys and values must be the same size");
        }

        LinkedListMultimap<K, V> multimap = LinkedListMultimap.create();
        for (int i = 0; i < keys.size(); i++) {
            multimap.put(keys.get(i), values.get(i));
        }
        return multimap;
    }

    public static LinkedListMultimap<String, String> exampleHeaderValueMap(io.cucumber.core.gherkin.Pickle pickle) {
        if (pickle == null) return null;

        // unwrap to the private GherkinMessagesPickle
        Object gmPickle = pickle; // in runtime it *is* a GherkinMessagesPickle
        Optional<Examples> exOpt = examplesOf(gmPickle);
        if (exOpt.isEmpty()) return null;

        Examples ex = exOpt.get();
        Optional<TableRow> headerOpt = ex.getTableHeader();
        if (headerOpt.isEmpty()) return null;

        // Find the matching row by last AST node id
        io.cucumber.messages.types.Pickle mp = messagePickle(gmPickle);
        String lastAstId = mp.getAstNodeIds().isEmpty() ? null
                : mp.getAstNodeIds().get(mp.getAstNodeIds().size() - 1);

        TableRow row = null;
        if (lastAstId != null) {
            row = ex.getTableBody().stream()
                    .filter(r -> Objects.equals(r.getId(), lastAstId))
                    .findFirst().orElse(null);
        }

        if (row == null) return null;

        // Build header->value map
        List<String> headers = headerOpt.get().getCells().stream()
                .map(TableCell::getValue).toList();
        List<String> values = row.getCells().stream()
                .map(TableCell::getValue).toList();
        return toMultimap(headers, values);
    }


}
