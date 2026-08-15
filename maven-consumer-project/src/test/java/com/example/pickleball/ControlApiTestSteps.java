package com.example.pickleball;

import io.cucumber.java.en.Given;

public class ControlApiTestSteps {
    private static int invocationCount;

    @Given("^CONTROL API TEST STEP$")
    public void controlApiTestStep() {
        invocationCount++;
    }

    static void reset() {
        invocationCount = 0;
    }

    static int invocationCount() {
        return invocationCount;
    }
}
