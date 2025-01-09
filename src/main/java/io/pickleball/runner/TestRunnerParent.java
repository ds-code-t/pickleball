package io.pickleball.runner;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
// import io.cucumber.junit.CucumberOptions.SnippetType; // Uncomment if using custom snippet types

@CucumberOptions(
        // Path to your feature files
        features = "src/test/resources/features",

        // Package with step definitions
        glue = "io.pickleball.stepdefs",
        // Reporting plugins
        plugin = {
                "pretty",
                "html:target/cucumber-reports/cucumber.html",
                "json:target/cucumber-reports/cucumber.json"
                // Add more plugins if needed
        },

        // Console output settings
        monochrome = true

        // Uncomment and customize the following options as needed:

        // tags = "@SmokeTest or @RegressionTest",
        // dryRun = false,
        // strict = true,
        // snippets = SnippetType.CAMELCASE,
        // name = {"^Test scenario name$"},
        // publish = true
)
public class TestRunnerParent extends AbstractTestNGCucumberTests {

//         public Object[][] scenarios() {
//             return super.scenarios();
//         }
//
////         Placeholder for custom setup methods
//         @BeforeClass(alwaysRun = true)
//         public void setUpClass() {
//             // Custom setup code
//         }
//
////         Placeholder for custom teardown methods
//         @AfterClass(alwaysRun = true)
//         public void tearDownClass() {
//             // Custom teardown code
//         }

        // Optional: Custom before test setup with parameters
        @BeforeTest(alwaysRun = true)
        @Parameters({"browser", "environment"})
        public void beforeTest(@Optional("chrome") String browser,
                               @Optional("test") String environment) {
                // Add custom test setup logic
            System.out.println("Cucumber tags: " + System.getProperty("cucumber.filter.tags"));
        }


//                // Add custom method setup logic
//        }
}
