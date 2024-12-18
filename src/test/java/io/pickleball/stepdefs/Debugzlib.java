package io.pickleball.stepdefs;


//import io.cucumber.java.en.Given;
//import io.cucumber.java.en.Then;
//import io.cucumber.java.en.When;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.If;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
//import io.cucumber.messages.types.DataTable;

import static io.cucumber.utilities.GeneralUtilities.getStringTimeStamp;
import static io.cucumber.utilities.GeneralUtilities.waitTime;

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

    @Given("^DDDqq '(.*)' and '(.*)'$")
    public void DDDqq(String t1, String t2, DataTable dt) {
        System.out.println("With data Table: " + dt);
    }


    @Given("^DocString Test$")
    public void DDDqqa(DocString dt) {
        System.out.println("DocString: " + dt);
    }


//    @Given("^DDD (.*) and (.*)$")
//    public void DDD2(String t1, String t2) {
//        System.out.println("WITHOUT DATATABLE");
//    }


//    @Given("^.*DDD (.*) and (.*).*$")
//    public void DDD2(String t1, String t2) {
//
//    }


    @Given("^I am running a testlzz (.*) and (.*)$")
    public void iAmRunningATestlzz(String t1, String t2) {
//        System.out.println("DEBUG: Given step executed " + t1 + "  --- " + t2);
//        GlobalCache.teamCityPlugin.printRemotely("", "A");
    }

    @When("I execute a steplzz")
    public void iExecuteASteplzz() {
        System.out.println("DEBUG: When step executed");
    }

    @If("I should see debug outputlzz")
    public void iShouldSeeDebugOutputlzz() {
//        waitTime(3000L);
//        System.out.println("DEBUG: Then step executed" + getStringTimeStamp());
    }


    @Then("Errorzz")
    public void errorthrowlzz() throws Exception {
        new Exception("errorthrowlzz").printStackTrace();
    }
}