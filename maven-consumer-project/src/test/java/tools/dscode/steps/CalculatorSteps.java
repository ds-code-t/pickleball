package tools.dscode.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.openqa.selenium.remote.RemoteWebDriver;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.reporting.logging.Status;
import tools.dscode.common.servicecalls.JacksonUtils;
import tools.dscode.common.servicecalls.ToJsonNode;
import tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils;
import tools.dscode.common.util.datetime.BusinessCalendar;
import tools.dscode.common.util.datetime.CalendarRegistry;
import tools.dscode.coredefinitions.NavigationSteps;
import tools.dscode.coredefinitions.ObjectRegistrationSteps;
import tools.dscode.registry.GlobalRegistry;

import java.util.ArrayList;
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
import static tools.dscode.common.gherkinoperations.DynamicExecution.runScenarioFromTag;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.FileAndDataParsing.readResourceFile;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMap;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;
import static tools.dscode.common.servicecalls.ToJsonNode.sjson;
import static tools.dscode.common.treeparsing.DefinitionContext.DEFAULT_EXECUTION_DICTIONARY;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.colocatedDeepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.customElementSuffixPredicate;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.deepNormalizedVisibleText;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.descendantDeepNormalizedVisibleText;
import static tools.dscode.common.util.datetime.CalendarRegistry.DEFAULT_CALENDAR;
import static tools.dscode.common.util.datetime.CalendarRegistry.calendar;
import static tools.dscode.common.util.datetime.CalendarRegistry.getCalendar;
//import static tools.dscode.coredefinitions.ObjectRegistrationSteps.getDefaultDriver;
//import static tools.dscode.coredefinitions.ObjectRegistrationSteps.getDriver;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;

import org.intellij.lang.annotations.Language;

public class CalculatorSteps {

    public static void main(String[] args) {
        String x1 = "//div";
        String x2 = "//div[@role]";
//        String x2 = "(//img | //i | //a[normalize-space(.)=''] | //*[@role='icon' or local-name()='svg'])[parent::div]";
        List<XPathy> list = new ArrayList<>();
        list.add(XPathy.from(x1));
        list.add(XPathy.from(x2));
        System.out.println("$$combineAnd-list: " + list);
        System.out.println("$$combineAnd-return: " + combineAnd(list));
        System.out.println("$$combineAnd-return-reversed: " + combineAnd(list.reversed()));
    }

    //    @Given("_-CREATE_LOCAL_DRIVER")
//    public RemoteWebDriver createDriver(ObjectNode configuration) throws Exception {
//        return DriverConstruction.createDriver(configuration);
//    }
    @Given("^dataTableTest1(?: (.*))?$")
    public static void dataTableTest1(String value, DataTable dataTable) {
        System.out.println("@@dataTableTest1: " + value);
        System.out.println("@@dataTable: " + dataTable);


    }

    @Given("^test3b(?: (.*))?$")
    public static void test3b(String value) {
        String x1 = "//div";
        String x2 = "//*[@role]";
        System.out.println("@@combineAnd: " + combineAnd(x1, x2));
    }


    @Given("^tprint (.*)$")
    public static void printVal(String message) {
        stepInfo("PRINT#$@#$@#$: " + message);
    }


    @Given("^call scenario(?: (.*))?$")
    public static void callScenario(String scenarioTag) {
        runScenarioFromTag(scenarioTag);
    }


    @Given("^test1(?: (.*))?$")
    public static void test1(String value) {
        Entry root = getRunningStep().stepEntry;

        root.child("UI Style Test Matrix")
                .tag("Global")
                .start()

                .child("Scenario - PASS / INFO")
                .tag("Scenario")
                .level(Level.INFO)
                .status(Status.PASS)
                .timestamp()
                .up()

                .child("Scenario - FAIL / ERROR")
                .tag("Scenario")
                .level(Level.ERROR)
                .status(Status.FAIL)
                .timestamp()
                .up()

                .child("Step - PASS / DEBUG")
                .tag("Step")
                .level(Level.DEBUG)
                .status(Status.PASS)
                .timestamp()
                .up()

                .child("Step - WARN / WARN")
                .tag("Step")
                .level(Level.WARN)
                .status(Status.WARN)
                .timestamp()
                .up()

                .child("Step - FAIL / INFO")
                .tag("Step")
                .level(Level.INFO)
                .status(Status.FAIL)
                .timestamp()
                .up()

                .child("Phrase - INFO / TRACE")
                .tag("Phrase")
                .level(Level.TRACE)
                .status(Status.INFO)
                .timestamp()
                .up()

                .child("Phrase - SKIP / DEBUG")
                .tag("Phrase")
                .level(Level.DEBUG)
                .status(Status.SKIP)
                .timestamp()
                .up()

                .child("Screenshot - PASS / INFO")
                .tag("Screenshot")
                .level(Level.INFO)
                .status(Status.PASS)
                .timestamp()
                .up()

                .child("Screenshot - FAIL / ERROR")
                .tag("Screenshot")
                .level(Level.ERROR)
                .status(Status.FAIL)
                .timestamp()
                .up()

                .child("Default - no tag - UNKNOWN / TRACE")
                .level(Level.TRACE)
                .status(Status.UNKNOWN)
                .timestamp()
                .up()

                .stop(Status.PASS);
    }



        @Given("^EntryTest1(?: (.*))?$")
        public static void entryTest1(String value) {
            getRunningStep().stepEntry.level(Level.INFO).status(Status.PASS);
        }

        @Given("^EntryTest2(?: (.*))?$")
        public static void entryTest2(String value) {
            getRunningStep().stepEntry.level(Level.INFO).status(Status.FAIL);
        }

        @Given("^EntryTest3(?: (.*))?$")
        public static void entryTest3(String value) {
            getRunningStep().stepEntry.level(Level.INFO).status(Status.WARN);
        }

        @Given("^EntryTest4(?: (.*))?$")
        public static void entryTest4(String value) {
            getRunningStep().stepEntry.level(Level.INFO).status(Status.SKIP);
        }

        @Given("^EntryTest5(?: (.*))?$")
        public static void entryTest5(String value) {
            getRunningStep().stepEntry.level(Level.INFO).status(Status.UNKNOWN);
        }

        @Given("^EntryTest6(?: (.*))?$")
        public static void entryTest6(String value) {
            getRunningStep().stepEntry.level(Level.ERROR).status(Status.FAIL);
        }

        @Given("^EntryTest7(?: (.*))?$")
        public static void entryTest7(String value) {
            getRunningStep().stepEntry.level(Level.ERROR).status(Status.PASS);
        }

        @Given("^EntryTest8(?: (.*))?$")
        public static void entryTest8(String value) {
            getRunningStep().stepEntry.level(Level.ERROR).status(Status.WARN);
        }

        @Given("^EntryTest9(?: (.*))?$")
        public static void entryTest9(String value) {
            getRunningStep().stepEntry.level(Level.WARN).status(Status.WARN);
        }

        @Given("^EntryTest10(?: (.*))?$")
        public static void entryTest10(String value) {
            getRunningStep().stepEntry.level(Level.WARN).status(Status.PASS);
        }

        @Given("^EntryTest11(?: (.*))?$")
        public static void entryTest11(String value) {
            getRunningStep().stepEntry.level(Level.WARN).status(Status.FAIL);
        }

        @Given("^EntryTest12(?: (.*))?$")
        public static void entryTest12(String value) {
            getRunningStep().stepEntry.level(Level.DEBUG).status(Status.PASS);
        }

        @Given("^EntryTest13(?: (.*))?$")
        public static void entryTest13(String value) {
            getRunningStep().stepEntry.level(Level.DEBUG).status(Status.FAIL);
        }

        @Given("^EntryTest14(?: (.*))?$")
        public static void entryTest14(String value) {
            getRunningStep().stepEntry.level(Level.DEBUG).status(Status.UNKNOWN);
        }

        @Given("^EntryTest15(?: (.*))?$")
        public static void entryTest15(String value) {
            getRunningStep().stepEntry.level(Level.TRACE).status(Status.PASS);
        }

        @Given("^EntryTest16(?: (.*))?$")
        public static void entryTest16(String value) {
            getRunningStep().stepEntry.level(Level.TRACE).status(Status.SKIP);
        }

        @Given("^EntryTest17(?: (.*))?$")
        public static void entryTest17(String value) {
            getRunningStep().stepEntry.level(Level.TRACE).status(Status.UNKNOWN);
        }

    @Given("^ProfileTest1(?: (.*))?$")
    public static void profileTest1(String value) {
        getRunningStep().stepEntry
                .tag("Scenario")
                .tag("Compact")
                .level(Level.INFO)
                .status(Status.PASS);
    }

    @Given("^ProfileTest2(?: (.*))?$")
    public static void profileTest2(String value) {
        getRunningStep().stepEntry
                .tag("Scenario")
                .tag("Spacious")
                .level(Level.ERROR)
                .status(Status.FAIL);
    }

    @Given("^ProfileTest3(?: (.*))?$")
    public static void profileTest3(String value) {
        getRunningStep().stepEntry
                .tag("Step")
                .tag("Rounded")
                .level(Level.WARN)
                .status(Status.WARN);
    }

    @Given("^ProfileTest4(?: (.*))?$")
    public static void profileTest4(String value) {
        getRunningStep().stepEntry
                .tag("Step")
                .tag("Sharp")
                .level(Level.DEBUG)
                .status(Status.PASS);
    }

    @Given("^ProfileTest5(?: (.*))?$")
    public static void profileTest5(String value) {
        getRunningStep().stepEntry
                .tag("Phrase")
                .tag("Elevated")
                .level(Level.TRACE)
                .status(Status.INFO);
    }

    @Given("^ProfileTest6(?: (.*))?$")
    public static void profileTest6(String value) {
        getRunningStep().stepEntry
                .tag("Phrase")
                .tag("Flat")
                .level(Level.INFO)
                .status(Status.SKIP);
    }

    @Given("^ProfileTest7(?: (.*))?$")
    public static void profileTest7(String value) {
        getRunningStep().stepEntry
                .tag("Screenshot")
                .tag("Bold Title")
                .level(Level.ERROR)
                .status(Status.FAIL);
    }

    @Given("^ProfileTest8(?: (.*))?$")
    public static void profileTest8(String value) {
        getRunningStep().stepEntry
                .tag("Screenshot")
                .tag("Wide Title")
                .level(Level.WARN)
                .status(Status.WARN);
    }

    @Given("^ProfileTest9(?: (.*))?$")
    public static void profileTest9(String value) {
        getRunningStep().stepEntry
                .tag("Scenario")
                .tag("Uppercase Title")
                .level(Level.INFO)
                .status(Status.UNKNOWN);
    }

    @Given("^ProfileTest10(?: (.*))?$")
    public static void profileTest10(String value) {
        getRunningStep().stepEntry
                .tag("Step")
                .tag("Monospace Title")
                .level(Level.DEBUG)
                .status(Status.PASS);
    }

    @Given("^ProfileTest11(?: (.*))?$")
    public static void profileTest11(String value) {
        getRunningStep().stepEntry
                .tag("Phrase")
                .tag("Center Title")
                .level(Level.TRACE)
                .status(Status.INFO);
    }

    @Given("^ProfileTest12(?: (.*))?$")
    public static void profileTest12(String value) {
        getRunningStep().stepEntry
                .tag("Scenario")
                .tag("Muted Visual")
                .level(Level.WARN)
                .status(Status.WARN);
    }

    @Given("^ProfileTest13(?: (.*))?$")
    public static void profileTest13(String value) {
        getRunningStep().stepEntry
                .tag("Step")
                .tag("Rounded")
                .tag("Elevated")
                .tag("Bold Title")
                .level(Level.ERROR)
                .status(Status.FAIL);
    }

    @Given("^ProfileTest14(?: (.*))?$")
    public static void profileTest14(String value) {
        getRunningStep().stepEntry
                .tag("Phrase")
                .tag("Compact")
                .tag("Monospace Title")
                .tag("Wide Title")
                .level(Level.DEBUG)
                .status(Status.UNKNOWN);
    }

    @Given("^test2b(?: (.*))?$")
    public static void test2b(String value) {
        System.out.println("@@test: " + value);
        runScenarioFromTag("%zer1");
//        System.out.println(Thread.currentThread().getContextClassLoader().getResource("runconfigs"));
//        System.out.println(Thread.currentThread().getContextClassLoader().getResource("runconfigs/"));
//        runScenarioFromTag("%aztag99");

//        RemoteWebDriver driver = getDefaultDriver();
//        System.out.println("@@driver: " + driver);
//        ObjectRegistrationSteps.cleanup(driver);
//        System.out.println("@@cleaned up: " + driver);
//        System.out.println("@@test2: " + value);
//        System.out.println("@@" +value + ": " +  getFromRunningParsingMap(value));
    }

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

//    @Given("test2")
//    public static void test2() {
//        ExecutionDictionary dict = getExecutionDictionary();
//
//
//        XPathy deeplyNormalized = XPathy.from(XPathyUtils.deepNormalizedText(ValueWrapper.createValueWrapper("aaa"), ExecutionDictionary.Op.EQUALS));
//        String noDisplayPredicate = any.byCondition(noDisplay).getXpath().replaceFirst("//\\*", "");
//        XPathy co = XPathy.from(deeplyNormalized.getXpath() + noDisplayPredicate);
//        List<WebElement> list;
//        WebDriver driver = getDefaultDriver("BROWSER");
//
//        ValueWrapper v = ValueWrapper.createValueWrapper("Status");
//        ExecutionDictionary.Op op = ExecutionDictionary.Op.EQUALS;
    /// /        XPathy t = new XPathy("//*[self::td or self::th or@role='cell'or@role='gridcell'or@role='columnheader'or@role='rowheader'or self::*" + customElementSuffixPredicate("cell") + "][ancestor::table and (count(preceding-sibling::*[self::td or self::th or@role='cell'or@role='gridcell'or@role='columnheader'or@role='rowheader'or self::*" + customElementSuffixPredicate("cell") + "]) + 1) = (count(((ancestor::table[1]//thead//*[self::tr or @role='row' or self::*" + customElementSuffixPredicate("row") + "][1]//*[self::th or @role='columnheader' or self::*" +
    /// /                customElementSuffixPredicate("header") + dict.getDirectText(v, op) + ") | (ancestor::table[1]//*[self::tr or @role='row' or self::*" + customElementSuffixPredicate("row") + "][1]//*[self::th or @role='columnheader' or self::*" + customElementSuffixPredicate("header") + dict.getDirectText(v, op) + "))[1]/preceding-sibling::*[self::th or@role='columnheader'or self::*" + customElementSuffixPredicate("header") + "]) + 1)]");
//        XPathy t = dict.cellsInColumnByHeaderText(v, op, customElementSuffixPredicate("row"), customElementSuffixPredicate("cell"), customElementSuffixPredicate("header"));
//        System.out.println("\n\n----------- ");
//
//        list = driver.findElements(t.getLocator());
//
//        for(WebElement e: list){
//        }
//        if(true)
//            return;
//
//        String xpath1 = "//*[(count(ancestor::table[1]//thead//*[self::tr or @role='row' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 3) = '-row']][1]//*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']][string(.) = 'Status']/preceding-sibling::*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']]) + 1) = 1]";
//        String xpath2 = "//*[(count(ancestor::table[1]//*[self::tr or @role='row' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 3) = '-row']][1]//*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']][string(.) = 'Status']/preceding-sibling::*[self::th or @role='columnheader' or self::*[contains(local-name(), '-') and substring(local-name(), string-length(local-name()) - 6) = '-header']]) + 1) = 1]";
//        try {
//            list = driver.findElements(By.xpath(xpath1));
//        }
//        catch (Exception e){
//            e.printStackTrace();
//        }
//        try {
//            list = driver.findElements(By.xpath(xpath2));
//        }
//        catch (Exception e){
//            e.printStackTrace();
//        }
//
//        String nxpath = "//*[(count((//thead//*[self::th])/preceding-sibling::*[self::th]) + 1) = 1]";
//
//        try {
//            list = driver.findElements(By.xpath(nxpath));
//        }
//        catch (Exception e){
//            e.printStackTrace();
//        }
//        list = driver.findElements(t.getLocator());
//    }

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
//        WebDriver driver = getDefaultDriver();
        Entry entry = logToScenario("logTest");
        entry.info("child1").status(Status.FAIL).screenshot("Test").start().info("gradnchaild1").status(Status.PASS).timestamp().parent.stop();
        entry.info("child2").status(Status.INFO).timestamp().info("gradnchaild2").screenshot("Test").status(Status.FAIL).timestamp();
        entry.info("child3").status(Status.PASS).timestamp().info("gradnchaild3").screenshot().status(Status.INFO).timestamp();
        entry.info("child2").status(Status.SKIP).timestamp().info("gradnchaild4").screenshot("4").status(Status.SKIP).timestamp();


        entry.info("zchild1").status(Status.FAIL).start().info("zgradnchaild1").status(Status.PASS).timestamp().parent.stop()
                .info("zchild2").status(Status.INFO).timestamp().info("zgzradnchaild2").status(Status.FAIL).timestamp()
                .info("zchild3").status(Status.PASS).timestamp().info("zgradnchaild3").status(Status.INFO).timestamp()
                .info("zchild2").status(Status.SKIP).timestamp().info("zgradnchaild4").screenshot("4).logInfo(\"zgzradnchaild2\").status(Status.FAIL).timestamp()\n" +
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
