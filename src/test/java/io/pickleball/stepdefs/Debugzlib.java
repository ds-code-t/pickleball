package io.pickleball.stepdefs;


//import io.cucumber.java.en.Given;
//import io.cucumber.java.en.Then;
//import io.cucumber.java.en.When;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
//import io.cucumber.java.en.Then;
//import io.cucumber.java.en.When;

//import cucumber.api.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pickleball.exceptions.SoftFailureException;
//import io.cucumber.messages.types.DataTable;

import java.util.List;

import static io.pickleball.cucumberutilities.GeneralUtilities.waitTime;

public class Debugzlib {

//    public Debugzlib(){
//        System.out.println("@@DebugSteps2");
//    }
//
//    public static void main(String[] args) {
//        System.out.println("@@main- iAmRunningATest()");
//        iAmRunningATestl2();
//    }
//
//    @Given("I am running a testl22")
//    public static void iAmRunningATestl2() {
//        System.out.println("DEBUG: STATIC! Given step executed");
//    }

//    @Before(order = 0)
//    public void before() {
//        System.out.println("@Before Scenario ");
//    }

//    public void before(Scenario scenario) {
//        System.out.println("@Before Scenario: " + scenario);
//    }

    @Given("^DDDqq '(.*)' and '(.*)'$")
    @Given("^DDDqq '(.*)' and '(.*)'$")
    public void DDDqq(String t1, String t2, DataTable dt) {
        waitTime(300L);
        System.out.println("With data Table: " + dt);
    }

//    @Given("^DocString Test$")
//    public void DDDqqa(DocString dt) {
//        System.out.println("DocString: " + dt);
//    }


    @When("baa")
    public void abiExecuteASteplzz() {
        System.out.println("baa DEBUG");
    }

    @When("^bc (.*) a steplzz")
    public void biExecuteASteplzz(String arg) {
        System.out.println("DEBUG: bc: " + arg);
    }

    @Given("bbb (.*)")
    public static void aRunSteps(String a) {
        System.out.println("RunSteps " + a);
    }


    @Given("I have the following string: {quotedString}")
    public void handleQuotedString(String quotedString) {
        System.out.println(quotedString);
    }


    @Given("^I am running a testlzz (.*) and ([^\\s]*)$")
    public void iAmRunningATestlzz(String t1, String t2, DataTable dataTable) {
        System.out.println("@@iAmRunningATestlzz=========");
        if (t1.contains("%"))
            System.out.println("!%STEP: " + t1);
        System.out.println("DdataTable " + dataTable);
//        System.out.println("DdataTable.asMaps: " + dataTable.asMaps());
        System.out.println("DdataTable.asLinkedMultiMaps: " + dataTable.asLinkedMultiMaps());
        System.out.println("DEBUG: start " + t1 + "  --- " + t2);
//        waitTime(800L);
        if (t1.contains("ERROR"))
            throw new RuntimeException("ERROR step-DEBUG: start " + t1 + "  --- " + t2);
        if (t1.contains("SOFT"))
            throw new SoftFailureException("ERROR step-DEBUG: start " + t1 + "  --- " + t2);
        System.out.println(" end");
    }

//    @Given("^\\|I am running a testlzz (.*) and (.*)$")
//    public void biAmRunningATestlzz(String t1, String t2, DataTable dataTable) {
//        System.out.println(" BBBBBBB , t1:" + t1 + " , t2:" + t2);
//    }

    @Given("I have the following string list: {stringList}")
    public void handleStringList(List<String> stringList) {
        // stringList will contain: ["item, one", "item \"two\"", "item three", ""]
        System.out.println(stringList.size());
        stringList.forEach(s -> System.out.println("\n@@item: " + s));
    }

    @When("I execute a steplzz")
    public void iExecuteASteplzz() {
        System.out.println("DEBUG: When step executed");
    }

    @When("I should see debug outputlzz")
    public void iShouldSeeDebugOutputlzz() {
//        waitTime(3000L);
//        System.out.println("DEBUG: Then step executed" + getStringTimeStamp());
    }


    @Then("Errorzz")
    public void errorthrowlzz() throws Exception {
        new Exception("errorthrowlzz").printStackTrace();
    }


    @Given("I perform an action")
    public void Test3(DataTable dataTable) {
        System.out.println("I perform an action: " + dataTable);
    }

    @Given("^Do you like cucumber\\?$")
    public void doYouLikeCucumber() {
        System.out.println("The step was matched literally with a question mark.");
    }

    @Given("^I see colo(?:u?)r$")
    public void iSeeColor() {
        System.out.println("The step was matched using '?' as a regex meta-character.");
    }


}