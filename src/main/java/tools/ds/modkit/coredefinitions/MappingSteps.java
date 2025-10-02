package tools.ds.modkit.coredefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;

public class MappingSteps {



    @Given("^(.*\\s+)?DATA TABLE$")
    public static void getDataTable(String tableName, DataTable dataTable) {
        System.out.println("@@getDataTable");
        System.out.println("@@tableName: " + tableName);
        System.out.println("@@dataTable: " + dataTable);
        // place Holder
    }





}
