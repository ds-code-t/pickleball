package io.pickleball.runner;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

@CucumberOptions(
        features = {"src/test/resources/features"},
        glue = {"io.pickleball.stepdefs"},
        plugin = {"pretty", "html:target/cucumber-reports/cucumber.html", "json:target/cucumber-reports/cucumber.json"},
        monochrome = true
)
public class Runner extends AbstractTestNGCucumberTests {
    public Runner() {
    }

    @BeforeTest(
            alwaysRun = true
    )
    @Parameters({"browser", "environment"})
    public void beforeTest(@Optional("chrome") String browser, @Optional("test") String environment) {
        System.out.println("Cucumber tags: " + System.getProperty("cucumber.filter.tags"));
    }
}
