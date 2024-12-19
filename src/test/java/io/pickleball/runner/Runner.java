package io.pickleball.runner;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import io.cucumber.testng.FeatureWrapper;
import io.cucumber.testng.PickleWrapper;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

import java.util.Arrays;
import java.util.Comparator;


@CucumberOptions(
        features = {"src/test/resources/features"},
        glue = {"io.pickleball.stepdefs"},
        plugin = {"pretty", "html:target/cucumber-reports/cucumber.html", "json:target/cucumber-reports/cucumber.json"}
//        monochrome = true

)
public class Runner extends AbstractTestNGCucumberTests {
    public Runner() {
        System.out.println("@@Runner: " + Thread.currentThread());
    }

    @BeforeTest(
            alwaysRun = true
    )
    @Parameters({"browser", "environment"})
    public void beforeTest(@Optional("chrome") String browser, @Optional("test") String environment) {
        System.out.println("Cucumber tags: " + System.getProperty("cucumber.filter.tags"));
    }

    @Override
    @DataProvider(parallel = true)
    public Object[][] scenarios() {
        // Retrieve the scenarios from the superclass
        Object[][] scenarios = super.scenarios();

        // Read the system property for execution order
        String order = System.getProperty("execution.order", "default");
        System.out.println("Execution order: " + order);


        Arrays.sort(scenarios, Comparator.comparing(
                        o -> ((PickleWrapper) ((Object[]) o)[0]).getPickle().getPriority())
                .thenComparing(o -> ((PickleWrapper) ((Object[]) o)[0]).getPickle().getName()) // Sort alphabetically by name
                .thenComparingInt(o -> ((PickleWrapper) ((Object[]) o)[0]).getPickle().getLine())); // Secondary sort by line number


        System.out.println("@@super.scenarios().length: " + scenarios.length);
        return scenarios;
    }


    @Override
    public void runScenario(PickleWrapper pickleWrapper, FeatureWrapper featureWrapper) {
        System.out.println("@@Start Scenario " + pickleWrapper.getPickle().getName());
        super.runScenario(pickleWrapper, featureWrapper);
        System.out.println("@@End Scenario " + pickleWrapper.getPickle().getName());
    }

}
