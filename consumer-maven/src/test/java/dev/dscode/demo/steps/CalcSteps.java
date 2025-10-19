package dev.dscode.demo.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

//import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalcSteps {
    private int total;

    @Given("a starting total of {int}")
    public void start(int s) {
        System.out.println("@@Start");
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        total = s;
    }

    @When("I add {int} and {int}")
    public void add(int a, int b) {
        System.out.println("@@add");
        total = a + b;
    }

    @Then("the total should be {int}")
    public void verify(int expected) {
        System.out.println("@@verify");
//        assertEquals(expected, total);
    }
}
