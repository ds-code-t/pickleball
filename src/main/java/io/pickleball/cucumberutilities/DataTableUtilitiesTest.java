package io.pickleball.cucumberutilities;

public class DataTableUtilitiesTest {

    public static void main(String[] args) {
        String tableSource = "| Column1 | Column2 |\n| Value1 | Value2 |";

        // Create a DataTable from the string source
        io.cucumber.datatable.DataTable dataTable = DataTableUtilities.createDataTableFromString(tableSource);
        System.out.println("DataTable: " + dataTable.cells());

        // Create a GherkinMessagesDataTableArgument from the string source
        io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument gherkinTable =
                DataTableUtilities.createGherkinMessagesDataTableArgumentFromString(tableSource);
        System.out.println("GherkinMessagesDataTableArgument: " + gherkinTable.cells());

        // Convert DataTable to GherkinMessagesDataTableArgument
        io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument convertedGherkinTable =
                DataTableUtilities.convertToGherkinMessagesDataTableArgument(dataTable);
        System.out.println("Converted to GherkinMessagesDataTableArgument: " + convertedGherkinTable.cells());

        // Convert GherkinMessagesDataTableArgument to DataTable
        io.cucumber.datatable.DataTable convertedDataTable =
                DataTableUtilities.convertToDataTable(gherkinTable);
        System.out.println("Converted to DataTable: " + convertedDataTable.cells());


//        String tableSource = "| Column1 | Column2 |\n| Value1 | Value2 |";

        // Create a DataTableArgument from the string source
        io.cucumber.core.stepexpression.DataTableArgument stepExpressionDataTable =
                DataTableUtilities.createStepExpressionDataTableArgument(tableSource);

        // Print the raw table
        System.out.println("DataTableArgument raw table: " + stepExpressionDataTable.toString());

    }
}
