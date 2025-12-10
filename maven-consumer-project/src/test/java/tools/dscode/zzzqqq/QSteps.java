package tools.dscode.zzzqqq;

import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.ReturnStep;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.edge.EdgeDriver;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static org.assertj.core.api.Assertions.assertThat;
import static tools.dscode.common.util.DebugUtils.printDebug;

public class QSteps {
    private int a, b, result;



    @ReturnStep("rrr")
    public void runInternal() {

    }

    @Given("I open {browser}")
    public void i_open_browser(Object driver) {
        // driver is either a ChromeDriver or EdgeDriver
        System.out.println("Got driver: " + driver.getClass().getSimpleName());
    }

    @Given("chromium {browser}")
    public void chromiumGet(EdgeDriver driver) {
        // driver is either a ChromeDriver or EdgeDriver
        System.out.println("Got driver: " + driver.getClass().getSimpleName());
    }

    @Given("chrome {browser}")
    public void chrome(ChromiumDriver driver) {
        // driver is either a ChromeDriver or EdgeDriver
        System.out.println("Got driver: " + driver.getClass().getSimpleName());
    }


    @Given("^zargt1 (.*)$")
    public void arg1(Integer z) {


    }

    @Given("QQQ")
    public void qqq1() {

    }




    @Given("map")
    public void mapTest(Map<String, String> map) {

    }

    @Given("list")
    public void listTest(List<String> list) {

    }

    @Given("set")
    public void setTest(Set<String> set) {

    }

    @Given("string")
    public void stringTest(String string) {

    }
    @Given("dataTable")
    public void dataTableTest(DataTable dataTable) {

    }



    @Given("get browser {returnStepParameter}")
    public void chrometest1(Object param, DocString docString) {



    }

    @Given("^xQQQ2(.*)$")
    public void qqq2(String arg0 ) {


    }


    @Given("^zdatatable (.*)$")
    public void dataTable(String arg0, DataTable table) {


    }

    @Given("^zprint (.*)$")
    public static void printVal(String message) {
        System.out.println("PRINT: " + message);
        PickleStepTestStep executingPickleStepTestStep = getRunningStep().executingPickleStepTestStep;
        System.out.println("nestingLevel1-: " + getRunningStep().getNestingLevel());
        System.out.println("nestingLevel2-: " + executingPickleStepTestStep.getPickleStep().nestingLevel);

        System.out.println("zzz1: " + getRunningStep().executingPickleStepTestStep.getStepText());
        System.out.println("zzz2: " + getRunningStep().executingPickleStepTestStep.getStep().getText());
    }

    public static void main(String[] args) {
        System.out.println("Hello World!");
        new QSteps().qqq2("QQQQQQQQQQQQQQ");
    }
}
