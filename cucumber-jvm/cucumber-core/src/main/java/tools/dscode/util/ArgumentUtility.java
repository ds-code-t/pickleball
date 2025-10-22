package tools.dscode.util;

import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.core.stepexpression.StepTypeRegistry;
import io.cucumber.datatable.DataTable;
import io.cucumber.datatable.DataTableTypeRegistry;
import io.cucumber.datatable.DataTableTypeRegistryTableConverter;
import io.cucumber.docstring.DocString;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ArgumentUtility {
    private ArgumentUtility() {
    }

    // ---------- Public API ----------

    /**
     * Build a DocStringArgument from a Gherkin-style block:
     * """[contentType]\ncontent\n"""
     */
    public static DocStringArgument docString(String gherkinBlock) {
        ParsedDoc pd = parseDocString(gherkinBlock);
        try {
            Class<?> txIf = Class.forName("io.cucumber.core.stepexpression.DocStringTransformer");
            Object tx = Proxy.newProxyInstance(
                txIf.getClassLoader(),
                new Class<?>[] { txIf },
                (p, m, a) -> DocString.create((String) a[0], (String) a[1]));
            Constructor<DocStringArgument> c = DocStringArgument.class.getDeclaredConstructor(txIf, String.class,
                String.class);
            c.setAccessible(true);
            return c.newInstance(tx, pd.content, pd.contentType);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to construct DocStringArgument", e);
        }
    }

    /**
     * Empty docstring (no content type), identical behavior to an empty
     * feature-file docstring.
     */
    public static DocStringArgument emptyDocString() {
        return docString("\"\"\"\n\"\"\"");
    }

    /** Build a DataTableArgument from a Gherkin-style pipe table string. */
    public static DataTableArgument dataTable(String gherkinTable) {
        List<List<String>> raw = parseTable(gherkinTable);
        try {
            Class<?> txIf = Class.forName("io.cucumber.core.stepexpression.RawTableTransformer");
            Object tx = Proxy.newProxyInstance(
                txIf.getClassLoader(),
                new Class<?>[] { txIf },
                (p, m, a) -> createDataTableWithDefaultConverter((List<List<String>>) a[0]));
            Constructor<DataTableArgument> c = DataTableArgument.class.getDeclaredConstructor(txIf, List.class);
            c.setAccessible(true);
            return c.newInstance(tx, raw);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to construct DataTableArgument", e);
        }
    }

    /**
     * Empty header-only table so asMaps() returns [] (matches feature-file
     * semantics).
     */
    public static DataTableArgument emptyDataTable() {
        return dataTable("| |");
    }

    // ---------- Internals ----------

    private static DataTable createDataTableWithDefaultConverter(List<List<String>> raw) {
        // Use Cucumber’s default registries exactly like runtime extraction
        StepTypeRegistry stepRegistry = new StepTypeRegistry(Locale.ENGLISH);
        DataTableTypeRegistry dtRegistry = stepRegistry.dataTableTypeRegistry();
        var converter = new DataTableTypeRegistryTableConverter(dtRegistry);
        return DataTable.create(raw, converter);
    }

    private static final Pattern DOC_RX = Pattern.compile(
        "^\\s*\"\"\"(?:\\s*(\\S+))?\\R(.*?)(?:\\R)?\\s*\"\"\"\\s*$",
        Pattern.DOTALL);

    private record ParsedDoc(String content, String contentType) {
    }

    private static ParsedDoc parseDocString(String block) {
        var m = DOC_RX.matcher(block);
        if (!m.matches())
            throw new IllegalArgumentException("Invalid Gherkin docstring block");
        return new ParsedDoc(m.group(2), m.group(1)); // content, contentType
                                                      // (may be null)
    }

    private static List<List<String>> parseTable(String tableText) {
        String[] lines = tableText.split("\\R");
        List<List<String>> rows = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty())
                continue;
            if (!t.contains("|"))
                throw new IllegalArgumentException("Invalid Gherkin table row: " + line);
            String core = t.replaceFirst("^\\|", "").replaceFirst("\\|$", "");
            String[] cells = core.split("\\|", -1);
            List<String> row = new ArrayList<>(cells.length);
            for (String c : cells)
                row.add(c.trim());
            rows.add(row);
        }
        if (rows.isEmpty())
            throw new IllegalArgumentException("Empty Gherkin table");
        return rows;
    }
}
