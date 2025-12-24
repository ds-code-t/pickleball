package tools.dscode.coredefinitions;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;

import java.util.ArrayList;
import java.util.List;

public class TableSteps {


    @Given("^Perform Actions$")
    public static void performUIActions(DataTable dataTable) {
        List<List<String>> lists =  new ArrayList<>(dataTable.asLists());
        List<String> elements = lists.removeFirst();


    }


}
