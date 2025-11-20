package tools.dscode.steps;

import com.xpathy.XPathy;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.*;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.coredefinitions.BrowserSteps;

import tools.dscode.coredefinitions.NavigationSteps;
import tools.dscode.registry.GlobalRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedTextWrapped;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;
import static tools.dscode.registry.GlobalRegistry.LOCAL;

public class CalculatorSteps {
    private int a, b, result;
    public static void main(String[] args) {
        XPathy locator =deepNormalizedTextWrapped("User Name");
        System.out.println(locator.getXpath());
    }
    @Given("I have numbers {int} and {int}")
    public void i_have_numbers_and(int x, int y) {
        a = x; b = y;

        io.cucumber.core.feature.FeatureParser featureParser = GlobalRegistry.globalOf(io.cucumber.core.feature.FeatureParser.class);
        io.cucumber.core.runtime.FeaturePathFeatureSupplier featurePathFeatureSupplier = GlobalRegistry.localOf(io.cucumber.core.runtime.FeaturePathFeatureSupplier.class);
    }

    @When("I add them")
    public void i_add_them() {
        result = a + b;
    }

    @When("throw error")
    public void throwError() {
        throw new RuntimeException("testing error in step");
    }

    @Then("the result should be {int}")
    public void the_result_should_be(int expected) {
        assertThat(result).isEqualTo(expected);
    }




}
