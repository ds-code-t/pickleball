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
import io.pickleball.exceptions.SoftFailureException;
import io.pickleball.customtypes.Coordinates;
//import io.cucumber.messages.types.DataTable;

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
        System.out.print("DEBUG: start " + t1 + "  --- " + t2);
        waitTime(800L);
        if(t1.contains("ERROR"))
            throw new RuntimeException("ERROR step-DEBUG: start " + t1 + "  --- " + t2);
        if(t1.contains("SOFT"))
            throw new SoftFailureException("ERROR step-DEBUG: start " + t1 + "  --- " + t2);
        System.out.println(" end");
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

    @Given("the user is at coordinates {stepText}")
    public void userAtCoordinates(Coordinates coordinates) {
        System.out.println("User is at: " + coordinates);
    }

}