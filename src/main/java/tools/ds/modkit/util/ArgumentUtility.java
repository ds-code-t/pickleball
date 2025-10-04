package tools.ds.modkit.util;

import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ArgumentUtility {
    private ArgumentUtility() {}

    // ===== Public API =====
    public static DocStringArgument docString(String gherkinBlock) {
        ParsedDoc pd = parseDocString(gherkinBlock);
        try {
            Class<?> txIf = Class.forName("io.cucumber.core.stepexpression.DocStringTransformer");
            Object tx = Proxy.newProxyInstance(txIf.getClassLoader(), new Class<?>[]{txIf},
                    (proxy, m, args) -> DocString.create((String) args[0], (String) args[1]));
            Constructor<DocStringArgument> c =
                    DocStringArgument.class.getDeclaredConstructor(txIf, String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(tx, pd.content, pd.contentType);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to construct DocStringArgument", e);
        }
    }

    public static DataTableArgument dataTable(String gherkinTable) {
        List<List<String>> raw = parseTable(gherkinTable);
        try {
            Class<?> txIf = Class.forName("io.cucumber.core.stepexpression.RawTableTransformer");
            Object tx = Proxy.newProxyInstance(txIf.getClassLoader(), new Class<?>[]{txIf},
                    (proxy, m, args) -> DataTable.create((List<List<String>>) args[0]));
            Constructor<DataTableArgument> c =
                    DataTableArgument.class.getDeclaredConstructor(txIf, List.class);
            c.setAccessible(true);
            return c.newInstance(tx, raw);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to construct DataTableArgument", e);
        }
    }

    /** Convenience: creates an empty DocStringArgument (equivalent to """ """ with no content). */
    public static DocStringArgument emptyDocString() {
        return docString("\"\"\"\n\"\"\"");
    }

    /** Convenience: creates an empty DataTableArgument (no rows or columns). */
    public static DataTableArgument emptyDataTable() {
        return dataTable("| |");
    }

    // ===== Parsing helpers =====
    private static final Pattern DOC_RX = Pattern.compile(
            "^\\s*\"\"\"(?:\\s*(\\S+))?\\R(.*?)(?:\\R)?\\s*\"\"\"\\s*$",
            Pattern.DOTALL);

    private record ParsedDoc(String content, String contentType) {}

    private static ParsedDoc parseDocString(String block) {
        var m = DOC_RX.matcher(block);
        if (!m.matches())
            throw new IllegalArgumentException("Not a valid Gherkin docstring block");
        String contentType = m.group(1);
        String content = m.group(2);
        return new ParsedDoc(content, contentType);
    }

    private static List<List<String>> parseTable(String tableText) {
        String[] lines = tableText.split("\\R");
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.contains("|"))
                throw new IllegalArgumentException("Not a valid Gherkin table row: " + line);
            String core = trimmed.replaceFirst("^\\|", "").replaceFirst("\\|$", "");
            String[] cells = core.split("\\|", -1);
            List<String> row = new ArrayList<>(cells.length);
            for (String c : cells) row.add(c.trim());
            rows.add(row);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Empty Gherkin table");
        return rows;
    }
}
