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
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.coredefinitions.NavigationSteps;
import tools.dscode.registry.GlobalRegistry;

import java.util.List;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.placeholder;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.type;
import static com.xpathy.Attribute.value;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.input;
import static org.assertj.core.api.Assertions.assertThat;

import static tools.dscode.common.evaluations.AviatorFunctions.processTernaryExpression;
import static tools.dscode.common.treeparsing.DefinitionContext.DEFAULT_EXECUTION_DICTIONARY;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;

public class CalculatorSteps {
    private int a, b, result;


    public static void main(String[] args) {
        String pre = "IF: 1 > 0 THEN:   print A ELSE: print 333";
        String post = processTernaryExpression("IF: 1 > 0 THEN:   print A ELSE: print 333");


    }


    @Given("config")
    public static void configs() {
        DEFAULT_EXECUTION_DICTIONARY.category("Button").or(
                (category, v, op) -> {
                    if (!v.asNormalizedText().equalsIgnoreCase("Submit"))
                        return null;
                    return input.byAttribute(type).withCase(LOWER).withNormalizeSpace().equals(v.asNormalizedText());
                }
        );
//        category("Button").inheritsFrom("visible","visibleText");

//        XPathyRegistry.registerAndBuilder("baseCategory",
//                (category, v, op) -> {
//                    if (v == null || v.isBlank())
//                        return null;
//
//                    return combineOr(
//                            any.byHaving(
//                                    XPathy.from("descendant-or-self::*")
//                                            .byHaving(deepNormalizedText(v))),
//                            any.byHaving(
//                                    XPathy.from("preceding::*")
//                                            .byHaving(deepNormalizedText(v)))
//                    );
//                },
//                (category, v, op) -> {
//                    XPathy selfInvisible = any.byCondition(invisible());
//                    String invisiblePredicate = extractPredicate("//*", selfInvisible.getXpath());
//                    XPathy selfVisible = any.byCondition(visible());
//                    String visiblePredicate = extractPredicate("//*", selfVisible.getXpath());
//                    return XPathy.from(
//                            "//*[" +
//                                    visiblePredicate +
//                                    " and not(ancestor::*[" +
//                                    invisiblePredicate +
//                                    "])]"
//                    );
//                }
//        );

//        XPathyRegistry.registerOrBuilder("Qqq",
//                (category, v, op) -> XPathy.from(Tag.any).byAttribute(role).equals("link").or().byAttribute(aria_label).equals("link"),
//                (category, v, op)-> XPathy.from(Tag.a)
//        );
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
