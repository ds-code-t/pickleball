package tools.dscode.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.xpathy.Attribute;
import com.xpathy.Condition;
import com.xpathy.Tag;
import com.xpathy.XPathy;
//import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.*;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Status;
import tools.dscode.common.servicecalls.JacksonUtils;
import tools.dscode.common.servicecalls.ToJsonNode;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.CalendarRegistry;
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
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static org.assertj.core.api.Assertions.assertThat;


import static tools.dscode.common.domoperations.elementstates.VisibilityConditions.noDisplay;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.servicecalls.ToJsonNode.sjson;
import static tools.dscode.common.treeparsing.DefinitionContext.DEFAULT_EXECUTION_DICTIONARY;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.customElementSuffixPredicate;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.descendantDeepNormalizedVisibleText;
import static tools.dscode.common.util.datetime.CalendarRegistry.DEFAULT_CALENDAR;
import static tools.dscode.common.util.datetime.CalendarRegistry.calendar;
import static tools.dscode.common.util.datetime.CalendarRegistry.getCalendar;
import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;

import org.intellij.lang.annotations.Language;

public class CalculatorSteps {


    @Given("^zcapitalize:(.*)$")
    public static String gettext(String text) {

        sjson("""
                {
                "A": "Some text"
                }
                """);

        new ToJsonNode.JsonRecord("""
                {
                "A": "Some text"
                }
                """);

        ToJsonNode t = new ToJsonNode(
                """
                        {
                        "A": "Some text"
                        }
                        """
        );

        t.json2 = """
                {
                "A": "Some text"
                }
                """;

        t.json3 = """
                {
                "A": "Some text"
                }
                """;

        System.out.println("capitalizing: " + text);
        return text.toUpperCase();
    }


    public static void main(String[] args) {

        JsonNode j = JacksonUtils.toJSON("""
                     {
                     "name": "John"
                     }
                """);


        JsonNode jsonNode = JacksonUtils.toJSON("""
                {
                  "name": "John",
                  "age": 30
                  }
                """);


        JsonNode yamlNode = JacksonUtils.toYAML("""
                
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
        ExecutionDictionary dict = getExecutionDictionary();
        System.out.println("@@noDisplay: " + any.byCondition(noDisplay));
        System.out.println("@@Menu: " + dict.andThenOr("Menu",ValueWrapper.createValueWrapper("aaa"), ExecutionDictionary.Op.EQUALS));
        XPathy deeplyNormalized = XPathy.from(XPathyUtils.deepNormalizedText(ValueWrapper.createValueWrapper("aaa"), ExecutionDictionary.Op.EQUALS));
        String noDisplayPredicate = any.byCondition(noDisplay).getXpath().replaceFirst("//\\*", "");
        XPathy co = XPathy.from(deeplyNormalized.getXpath() + noDisplayPredicate);
        System.out.println("@@deepNormalizedText(v, op): " + deeplyNormalized);
        System.out.println("@@noDisplayPredicate: " + noDisplayPredicate);
        System.out.println("@@co: " + co);
        System.out.println("@@descendantDeepNormalizedVisibleText(v, op): " + descendantDeepNormalizedVisibleText(ValueWrapper.createValueWrapper("aaa"), ExecutionDictionary.Op.EQUALS));
        System.out.println("@@deepNormalizedVisibleText(v, op): " +deepNormalizedVisibleText(ValueWrapper.createValueWrapper("aaa"), ExecutionDictionary.Op.EQUALS));
        List<WebElement> list;
        WebDriver driver = getDriver("BROWSER");

        ValueWrapper v = ValueWrapper.createValueWrapper("Status");
        ExecutionDictionary.Op op = ExecutionDictionary.Op.EQUALS;
//        XPathy t = new XPathy("//*[self::td or self::th or @role='cell' or @role='gridcell' or @role='columnheader' or @role='rowheader' or self::*" + customElementSuffixPredicate("cell") + "][ancestor::table and (count(preceding-sibling::*[self::td or self::th or @role='cell' or @role='gridcell' or @role='columnheader' or @role='rowheader' or self::*" + customElementSuffixPredicate("cell") + "]) + 1) = (count(((ancestor::table[1]//thead//*[self::tr or @role='row' or self::*" + customElementSuffixPredicate("row") + "][1]//*[self::th or @role='columnheader' or self::*" +
//                customElementSuffixPredicate("header") + dict.getDirectText(v, op) + ") | (ancestor::table[1]//*[self::tr or @role='row' or self::*" + customElementSuffixPredicate("row") + "][1]//*[self::th or @role='columnheader' or self::*" + customElementSuffixPredicate("header") + dict.getDirectText(v, op) + "))[1]/preceding-sibling::*[self::th or @role='columnheader' or self::*" + customElementSuffixPredicate("header") + "]) + 1)]");
        XPathy t = dict.cellsInColumnByHeaderText(v, op, customElementSuffixPredicate("row"), customElementSuffixPredicate("cell"), customElementSuffixPredicate("header"));
        System.out.println("@@@t: " + t.getXpath());
        System.out.println("\n\n----------- ");

        list = driver.findElements(t.getLocator());
        System.out.println("@@@list 1-: " + list.size());
        System.out.println("@@@list 1: " + list);

        for(WebElement e: list){
            System.out.println("@@@e: " + e);
            System.out.println("@@@e.getText: " + e.getText());
        }
        if(true)
            return;

        String xpath1 = "//*[(count(ancestor::table[1]//thead//*[self::tr or @role='row' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 3) = '-row']][1]//*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']][string(.) = 'Status']/preceding-sibling::*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']]) + 1) = 1]";
        String xpath2 = "//*[(count(ancestor::table[1]//*[self::tr or @role='row' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 3) = '-row']][1]//*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']][string(.) = 'Status']/preceding-sibling::*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']]) + 1) = 1]";
        try {
            list = driver.findElements(By.xpath(xpath1));
            System.out.println("@@@list 1-: " + list.size());
            System.out.println("@@@list 1: " + list);
        }
        catch (Exception e){
            System.out.println("@@xpath1 error");
            e.printStackTrace();
        }
        try {
            list = driver.findElements(By.xpath(xpath2));
            System.out.println("@@@list 2-: " + list.size());
            System.out.println("@@@list 2: " + list);
        }
        catch (Exception e){
            System.out.println("@@xpath2 error");
            e.printStackTrace();
        }

        String nxpath = "//*[(count((//thead//*[self::th])/preceding-sibling::*[self::th]) + 1) = 1]";

        try {
            System.out.println("@@@");
            list = driver.findElements(By.xpath(nxpath));
            System.out.println("@@@list nxpath-: " + list.size());
            System.out.println("@@@list nxpath: " + list);
        }
        catch (Exception e){
            System.out.println("@@xpathnxpath error");
            e.printStackTrace();
        }
        list = driver.findElements(t.getLocator());
        System.out.println("@@@list1: " + list.size());
        System.out.println("@@@list2: " + list);
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
