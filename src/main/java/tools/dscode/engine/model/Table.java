package tools.dscode.engine.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Neutral data table. Cucumber {@code DataTable} exists only at the adapter edge.
 */
public record Table(List<List<String>> rows) {
    public Table {
        List<List<String>> copy = new ArrayList<>();
        if (rows != null) {
            for (List<String> row : rows) {
                copy.add(row == null ? List.of() : List.copyOf(row));
            }
        }
        rows = List.copyOf(copy);
    }

    public static Table empty() {
        return new Table(List.of());
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
