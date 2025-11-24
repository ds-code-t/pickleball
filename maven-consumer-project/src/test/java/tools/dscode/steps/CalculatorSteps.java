package tools.dscode.steps;

import com.xpathy.Attribute;
import com.xpathy.Condition;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.*;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.common.domoperations.XPathyRegistry;
import tools.dscode.coredefinitions.BrowserSteps;

import tools.dscode.coredefinitions.NavigationSteps;
import tools.dscode.registry.GlobalRegistry;

import java.util.List;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.placeholder;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.type;
import static com.xpathy.Tag.any;
import static org.assertj.core.api.Assertions.assertThat;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.domoperations.VisibilityConditions.extractPredicate;
import static tools.dscode.common.domoperations.VisibilityConditions.invisible;
import static tools.dscode.common.domoperations.VisibilityConditions.visible;
import static tools.dscode.common.domoperations.XPathyMini.orMap;
import static tools.dscode.common.domoperations.XPathyMini.textOp;
import static tools.dscode.common.domoperations.XPathyRegistry.combineOr;
import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.domoperations.XPathyUtils.deepNormalizedTextWrapped;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;
import static tools.dscode.registry.GlobalRegistry.LOCAL;

public class CalculatorSteps {
    private int a, b, result;


    public static void main(String[] args) {

//        XPathy locator =deepNormalizedTextWrapped("User Name");
//        System.out.println(locator.getXpath());
        System.out.println("@@Tag.button: " + Tag.button);
        System.out.println("@@Tag.button: " + Tag.button.byAttribute(Attribute.placeholder));
        System.out.println("@@Tag.button: " + Tag.button.byAttribute(Attribute.placeholder).equals("SS").or().tag(Tag.input));
        System.out.println("@@Tag.button: " + Tag.button.byAttribute(Attribute.placeholder).equals("SS").or().tag(Tag.input).byAttribute(Attribute.type).equals("password"));

    }


    @Given("configs")
    public static void configs() {
        XPathyRegistry.registerAndBuilder("baseCategory",
                (category, v, op) -> {
                    if (v == null || v.isBlank())
                        return null;

                    return combineOr(
                            any.byHaving(
                                    XPathy.from("descendant-or-self::*")
                                            .byHaving(deepNormalizedText(v))),
                            any.byHaving(
                                    XPathy.from("preceding::*")
                                            .byHaving(deepNormalizedText(v)))
                    );
                },
                (category, v, op) -> {
                    XPathy selfInvisible = any.byCondition(invisible());
                    String invisiblePredicate = extractPredicate("//*", selfInvisible.getXpath());
                    XPathy selfVisible = any.byCondition(visible());
                    String visiblePredicate = extractPredicate("//*", selfVisible.getXpath());
                    return XPathy.from(
                            "//*[" +
                                    visiblePredicate +
                                    " and not(ancestor::*[" +
                                    invisiblePredicate +
                                    "])]"
                    );
                }
        );

        XPathyRegistry.registerOrBuilder("Qqq",
                (category, v, op) -> XPathy.from(Tag.any).byAttribute(role).equals("link").or().byAttribute(aria_label).equals("link"),
                (category, v, op)-> XPathy.from(Tag.a)
        );
    }

    @Given("I have numbers {int} and {int}")
    public void i_have_numbers_and(int x, int y) {
        a = x;
        b = y;

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
