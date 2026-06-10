package tools.dscode.steps;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;

public class LocalSteps {
    @Given("I have a DataTable")
    public static void testdfd(DataTable dataTable)
    {
        System.out.println("@@dataTableTest1: " + dataTable);
    }
}
