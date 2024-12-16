package io.pickleball.cucumberutilities;

import java.util.List;
import java.util.stream.Collectors;

public class DataTableUtilities {

    /**
     * Converts a GherkinMessagesDataTableArgument to a DataTable.
     *
     * @param gherkinTable The GherkinMessagesDataTableArgument to convert.
     * @return A DataTable representing the same table data.
     */
    public static io.cucumber.datatable.DataTable convertToDataTable(
            io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument gherkinTable) {

        // Extract raw table data
        List<List<String>> rawTable = gherkinTable.cells();

        // Create and return a DataTable
        return io.cucumber.datatable.DataTable.create(rawTable);
    }

    /**
     * Converts a DataTable to a GherkinMessagesDataTableArgument.
     *
     * @param dataTable The DataTable to convert.
     * @return A GherkinMessagesDataTableArgument representing the same table data.
     */
    public static io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument convertToGherkinMessagesDataTableArgument(
            io.cucumber.datatable.DataTable dataTable) {

        // Extract raw table data from the DataTable
        List<List<String>> rawTable = dataTable.cells();

        // Create a PickleTable from the raw data
        io.cucumber.messages.types.PickleTable pickleTable = new io.cucumber.messages.types.PickleTable(
                rawTable.stream()
                        .map(row -> new io.cucumber.messages.types.PickleTableRow(
                                row.stream()
                                        .map(io.cucumber.messages.types.PickleTableCell::new)
                                        .collect(Collectors.toList())))
                        .collect(Collectors.toList())
        );

        // Return a GherkinMessagesDataTableArgument
        return new io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument(pickleTable, 1); // Mock line number
    }

    /**
     * Creates a GherkinMessagesDataTableArgument from a string source.
     *
     * @param tableSource The string source representing the table.
     *                    Example: "| Column1 | Column2 |\n| Value1 | Value2 |".
     * @return A GherkinMessagesDataTableArgument representing the table.
     */
    public static io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument createGherkinMessagesDataTableArgumentFromString(
            String tableSource) {

        // Create a DataTable from the string source
        io.cucumber.datatable.DataTable dataTable = createDataTableFromString(tableSource);

        // Convert DataTable to GherkinMessagesDataTableArgument
        return convertToGherkinMessagesDataTableArgument(dataTable);
    }

    /**
     * Creates a DataTable from a string source.
     *
     * @param tableSource The string source representing the table.
     *                    Example: "| Column1 | Column2 |\n| Value1 | Value2 |".
     * @return A DataTable representing the table.
     */
    public static io.cucumber.datatable.DataTable createDataTableFromString(String tableSource) {
        // Parse the raw table from the string source
        List<List<String>> rawTable = parseTableSource(tableSource);

        // Create and return a DataTable
        return io.cucumber.datatable.DataTable.create(rawTable);
    }

    /**
     * Parses a table source string into a raw 2D list of strings.
     *
     * @param tableSource The string source representing the table.
     * @return A 2D list of strings representing the table rows and columns.
     */
    private static List<List<String>> parseTableSource(String tableSource) {
        return java.util.Arrays.stream(tableSource.split("\n")) // Split by rows
                .map(row -> java.util.Arrays.stream(row.split("\\|")) // Split by columns
                        .map(String::trim) // Trim whitespace
                        .filter(cell -> !cell.isEmpty()) // Exclude empty cells
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
    }

    /**
     * Converts a GherkinMessagesDataTableArgument to a DataTableArgument.
     *
     * @param gherkinTable The GherkinMessagesDataTableArgument to convert.
     * @return A DataTableArgument representing the same table data.
     */
    public static io.cucumber.core.stepexpression.DataTableArgument convertToDataTableArgument(
            io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument gherkinTable) {

        // Convert GherkinMessagesDataTableArgument to DataTable
        io.cucumber.datatable.DataTable dataTable = convertToDataTable(gherkinTable);

        // Use a transformer to wrap the DataTable
        io.cucumber.core.stepexpression.RawTableTransformer<io.cucumber.datatable.DataTable> transformer =
                table -> dataTable;

        // Create and return a DataTableArgument
        return new io.cucumber.core.stepexpression.DataTableArgument(transformer, dataTable.cells());
    }

    /**
     * Converts a DataTableArgument to a GherkinMessagesDataTableArgument.
     *
     * @param dataTableArg The DataTableArgument to convert.
     * @return A GherkinMessagesDataTableArgument representing the same table data.
     */
    public static io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument convertToGherkinMessagesDataTableArgument(
            io.cucumber.core.stepexpression.DataTableArgument dataTableArg) {

        // Extract the DataTable from the DataTableArgument
        io.cucumber.datatable.DataTable dataTable = (io.cucumber.datatable.DataTable) dataTableArg.getValue();

        // Convert DataTable to GherkinMessagesDataTableArgument
        return convertToGherkinMessagesDataTableArgument(dataTable);
    }

    /**
     * Creates a DataTableArgument from a string source.
     *
     * @param tableSource The string source representing the table.
     * @return A DataTableArgument representing the table.
     */
    public static io.cucumber.core.stepexpression.DataTableArgument createStepExpressionDataTableArgument(String tableSource) {
        // Create a DataTable from the string source
        io.cucumber.datatable.DataTable dataTable = createDataTableFromString(tableSource);

        // Use a transformer that wraps the DataTable
        io.cucumber.core.stepexpression.RawTableTransformer<io.cucumber.datatable.DataTable> dataTableTransformer =
                table -> dataTable;

        // Create and return the DataTableArgument
        return new io.cucumber.core.stepexpression.DataTableArgument(dataTableTransformer, dataTable.cells());
    }
}
