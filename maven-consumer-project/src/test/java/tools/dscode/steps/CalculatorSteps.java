package tools.dscode.steps;

import io.cucumber.java.en.*;
import tools.dscode.registry.GlobalRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;
import static tools.dscode.registry.GlobalRegistry.LOCAL;

public class CalculatorSteps {
    private int a, b, result;

    @Given("I have numbers {int} and {int}")
    public void i_have_numbers_and(int x, int y) {
        a = x; b = y;

        io.cucumber.core.feature.FeatureParser featureParser = GlobalRegistry.globalOf(io.cucumber.core.feature.FeatureParser.class);
        io.cucumber.core.runtime.FeaturePathFeatureSupplier featurePathFeatureSupplier = GlobalRegistry.localOf(io.cucumber.core.runtime.FeaturePathFeatureSupplier.class);

        System.out.println("@@GLOBAL:  " + GLOBAL);
        System.out.println("@@LOCAL:  " + LOCAL);
        System.out.println("@@featureParser:  " + featureParser);
        System.out.println("@@featurePathFeatureSupplier:  " + featurePathFeatureSupplier);
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
