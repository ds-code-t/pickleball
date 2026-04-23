package io.cucumber.core.runner.util;

import io.cucumber.core.stepexpression.StepTypeRegistry;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getLocale;

public final class DataTableFactory {
    private DataTableFactory() {}

    /** Map -> 1-header + 1-data-row table. */
    public static DataTable fromMap(Map<?, ?> map) {
        List<String> header = new ArrayList<>();
        List<String> values = new ArrayList<>();
        map.forEach((k, v) -> {
            header.add(String.valueOf(k));
            values.add(String.valueOf(v));
        });
        return create(List.of(header, values));
    }

    /** List of maps -> 1-header + N-data-row table (keys from first map). */
    public static DataTable fromMaps(List<? extends Map<?, ?>> maps) {
        if (maps.isEmpty()) throw new IllegalArgumentException("Empty list");
        List<String> header = maps.get(0).keySet().stream().map(String::valueOf).toList();
        List<List<String>> raw = new ArrayList<>();
        raw.add(header);
        for (Map<?, ?> m : maps) {
            raw.add(header.stream().map(h -> String.valueOf(m.get(h))).toList());
        }
        return create(raw);
    }

    /** List of lists -> first list is header, rest are data rows. */
    public static DataTable fromLists(List<? extends List<?>> lists) {
        List<List<String>> raw = new ArrayList<>();
        for (List<?> row : lists) {
            raw.add(row.stream().map(String::valueOf).toList());
        }
        return create(raw);
    }

    /** Gherkin-style pipe-delimited string -> DataTable. */
    public static DataTable fromString(String tableText) {
        List<List<String>> raw = new ArrayList<>();
        for (String line : tableText.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String core = t.replaceFirst("^\\|", "").replaceFirst("\\|$", "");
            List<String> row = new ArrayList<>();
            for (String cell : core.split("\\|", -1)) row.add(cell.trim());
            raw.add(row);
        }
        return create(raw);
    }

    private static DataTable create(List<List<String>> raw) {
        var registry = new StepTypeRegistry(getLocale()).dataTableTypeRegistry();
        return DataTable.create(raw, new DataTableTypeRegistryTableConverter(registry));
    }
}