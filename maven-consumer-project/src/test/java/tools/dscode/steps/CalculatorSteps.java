package tools.dscode.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.xpathy.Attribute;
import com.xpathy.Condition;
import com.xpathy.Tag;
import com.xpathy.XPathy;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.*;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Status;
import tools.dscode.common.servicecalls.JacksonUtils;
import tools.dscode.coredefinitions.NavigationSteps;
import tools.dscode.registry.GlobalRegistry;

import java.util.List;
import java.util.Map;

import static com.xpathy.Attribute.aria_label;
import static com.xpathy.Attribute.id;
import static com.xpathy.Attribute.placeholder;
import static com.xpathy.Attribute.role;
import static com.xpathy.Attribute.type;
import static com.xpathy.Attribute.value;
import static com.xpathy.Case.LOWER;
import static com.xpathy.Tag.any;
import static com.xpathy.Tag.input;
import static io.cucumber.core.runner.CurrentScenarioState.getScenarioLogRoot;
import static io.cucumber.core.runner.CurrentScenarioState.logToScenario;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.assertj.core.api.Assertions.assertThat;


import static tools.dscode.common.treeparsing.DefinitionContext.DEFAULT_EXECUTION_DICTIONARY;
import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;
import org.intellij.lang.annotations.Language;
public class CalculatorSteps {


    public static void main(String[] args) {

        JsonNode j = JacksonUtils.toJSON("""
                     {
                     "name": "John"
                     }
                """);


        JsonNode jsonNode = JacksonUtils.toJSON( """
                {
                  "name": "John",
                  "age": 30
                  }
                """);



        JsonNode yamlNode =  JacksonUtils.toYAML("""

                        name: John


                age: 30""");

        JsonNode xmlNode = JacksonUtils.
                toXML(
                        """
                                <person>
                                  <name>John</
                                    name>
                                  <age>30</age>
                                </person>""");
        String formattedJson = JacksonUtils.formatJSON(
                """

                            {
                          "name": "John",
                          "age": 30
                        }""");
        System.out.println(
                formattedJson);

        String
                formattedYaml = JacksonUtils
                .formatYAML("""

                                name: John


                        age: 30""");
        System.out.println(formattedYaml);

        String formattedXml = JacksonUtils.formatXML(
                """
                        <person>
                          <name>John</name>
                          <age>30</age>
                        </person>""");
        System.out.println(formattedXml);

    }

//    static {
//        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//            try {
//            System.err.println("@@getAllStackTraces*******");
//            System.err.println("@@getAllStackTraces******* 1 " + Thread.getAllStackTraces());
//            System.err.println("@@getAllStackTraces0:: " + Thread.getAllStackTraces());
//            System.err.println("@@getAllStackTraces1:: " + Thread.getAllStackTraces().entrySet().size());
//            System.err.println("@@getAllStackTraces2:: " + Thread.getAllStackTraces().size());
//            System.err.println("@@getAllStackTraces3:: " + Thread.getAllStackTraces());
//            System.err.println("\n=== JVM shutdown hook: listing NON-DAEMON live threads ===:: ");
//            System.err.println("@@getAllStackTraces---:: " + Thread.getAllStackTraces().entrySet().size());
//            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
//                Thread t = e.getKey();
//                if (t.isAlive() && !t.isDaemon()) {
//                    System.err.println("NON-DAEMON: " + t.getName()
//                            + " state=" + t.getState()
//                            + " group=" + (t.getThreadGroup() == null ? "null" : t.getThreadGroup().getName()));
//                    StackTraceElement[] st = e.getValue();
//                    // print a few frames (enough to identify owner)
//                    for (int i = 0; i < Math.min(st.length, 12); i++) {
//                        System.err.println("    at " + st[i]);
//                    }
//                }
//            }
//            System.err.println("=== end non-daemon threads ===\n");
//            }
//            catch (Throwable t){
//                t.printStackTrace();
//
//            }
//        }, "TestJvmThreadProbe-shutdown"));
//
//
//    }


//    @BeforeAll
//    public static void beforeAll() {
//        System.err.println("cucumber.plugin=" + System.getProperty("cucumber.plugin"));
//        System.err.println("cucumber.publish.enabled=" + System.getProperty("cucumber.publish.enabled"));
//        System.err.println("cucumber.execution.summary.print=" + System.getProperty("cucumber.execution.summary.print"));
//        System.err.println("junit.jupiter.execution.parallel.enabled=" +
//                System.getProperty("junit.jupiter.execution.parallel.enabled"));
//        System.err.println("junit.jupiter.execution.parallel.mode.default=" +
//                System.getProperty("junit.jupiter.execution.parallel.mode.default"));
//
//
//    }

//    @AfterAll
//    public static void afterAll() {
//        ThreadDumps.dumpStacksAsync("cucumber @AfterAll");
//    }

    @Given("test2")
    public static void test2() {

    }

    private int a, b, result;

    @Given("error")
    public static void errorTest() {
        throw new RuntimeException("error test1");
    }

    @Given("justwait")
    public static void justwait() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Given("location")
    public static void locationTest() {
        Pickle pickle = getCurrentScenarioState().pickle;


        if (pickle.getExamplesLocation() != null && pickle.getExamplesLocation().isPresent()) {

        }
    }

    @Given("logTest")
    public static void logTest() {
        WebDriver driver = getDefaultDriver();
        Entry entry = logToScenario("logTest");
        entry.logInfo("child1").status(Status.FAIL).screenshot("Test").start().logInfo("gradnchaild1").status(Status.PASS).timestamp().parent.stop();
        entry.logInfo("child2").status(Status.INFO).timestamp().logInfo("gradnchaild2").screenshot("Test").status(Status.FAIL).timestamp();
        entry.logInfo("child3").status(Status.PASS).timestamp().logInfo("gradnchaild3").screenshot().status(Status.INFO).timestamp();
        entry.logInfo("child2").status(Status.SKIP).timestamp().logInfo("gradnchaild4").screenshot("4").status(Status.SKIP).timestamp();


        entry.logInfo("zchild1").status(Status.FAIL).start().logInfo("zgradnchaild1").status(Status.PASS).timestamp().parent.stop()
                .logInfo("zchild2").status(Status.INFO).timestamp().logInfo("zgzradnchaild2").status(Status.FAIL).timestamp()
                .logInfo("zchild3").status(Status.PASS).timestamp().logInfo("zgradnchaild3").status(Status.INFO).timestamp()
                .logInfo("zchild2").status(Status.SKIP).timestamp().logInfo("zgradnchaild4").screenshot("4).logInfo(\"zgzradnchaild2\").status(Status.FAIL).timestamp()\n" +
                        "        .logInfo(\"zchild3\").status(Status.PASS).timestamp().logInfo(\"zgradnchaild3\").status(Status.INFO).timestam").status(Status.SKIP).timestamp();

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
