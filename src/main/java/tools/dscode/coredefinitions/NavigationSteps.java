package tools.dscode.coredefinitions;

import io.cucumber.java.en.When;
import io.cucumber.datatable.DataTable;
import org.openqa.selenium.chrome.ChromeDriver;

import java.util.List;

public class NavigationSteps {

    @When("I navigate to:")
    public void i_navigate_to(DataTable table) {
        ChromeDriver driver = BrowserSteps.getDriver();
        driver.get(table.asList().getFirst());
    }
}