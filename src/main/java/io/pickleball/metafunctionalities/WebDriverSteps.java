package io.pickleball.metafunctionalities;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.drivers.UniversalWebDriver;

import static io.pickleball.cacheandstate.GlobalRegistry.callMethod;
import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;

public class WebDriverSteps {


    @Given("^Navigate (\".*\" )?(Chrome|Edge) Browser")
    public static void saveTableWithKey(String customBrowserName, String browserType, DataTable dataTable) {
        UniversalWebDriver driver = callMethod("WebDrivers." + browserType);
        if (customBrowserName != null && !customBrowserName.isBlank())
            driver.register(customBrowserName.trim());
    }

}
