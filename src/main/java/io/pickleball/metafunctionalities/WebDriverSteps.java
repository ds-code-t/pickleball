package io.pickleball.metafunctionalities;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.drivers.UniversalWebDriver;
import io.pickleball.exceptions.PickleballException;

import static io.pickleball.cacheandstate.GlobalRegistry.callMethod;
import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;

public class WebDriverSteps {


    @Given("^Navigate (?:\"(.*)\" )?(.*) Browser")
    public static void navigate(String customBrowserName, String browserType, DataTable dataTable) {
        getRunMaps().setTempMap(dataTable.asLinkedMultiMap());
        System.out.println("@@getRunMaps(): " + getRunMaps());
        UniversalWebDriver driver = callMethod("WebDrivers." + browserType);
        if (driver == null)
            throw new PickleballException(String.format("Browser '%s' not defined", browserType));
        if (customBrowserName != null && !customBrowserName.isBlank())
            driver.register(customBrowserName.trim());
    }

}
