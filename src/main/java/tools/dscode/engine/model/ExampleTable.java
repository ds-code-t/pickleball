package tools.dscode.engine.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One Examples table. Each data row keeps the header so a scenario outline
 * can be expanded without going back to Cucumber.
 */
public record ExampleTable(
        String name,
        List<String> tags,
        List<String> headers,
        List<List<String>> rows,
        SourceLocation location
) {
    public ExampleTable {
        name = name == null ? "" : name;
        tags = tags == null ? List.of() : List.copyOf(tags);
        headers = headers == null ? List.of() : List.copyOf(headers);
        List<List<String>> copy = new ArrayList<>();
        if (rows != null) {
            for (List<String> row : rows) {
                copy.add(row == null ? List.of() : List.copyOf(row));
            }
        }
        rows = List.copyOf(copy);
        location = location == null ? SourceLocation.unknown() : location;
    }
}
