package com.example.pickleball;

import io.cucumber.java.en.Given;

public class ControlApiTestSteps {
    private static int invocationCount;
    private static int rawStackTracePrintCount;

    @Given("^CONTROL API TEST STEP$")
    public void controlApiTestStep() {
        invocationCount++;
    }

    @Given("^CONTROL API FAILING TEST STEP$")
    public void controlApiFailingTestStep() {
        throw new ExpectedControlFailure();
    }

    static void reset() {
        invocationCount = 0;
        rawStackTracePrintCount = 0;
    }

    static int invocationCount() {
        return invocationCount;
    }

    static int rawStackTracePrintCount() {
        return rawStackTracePrintCount;
    }

    private static final class ExpectedControlFailure extends RuntimeException {
        private ExpectedControlFailure() {
            super("expected detached control failure");
        }

        @Override
        public void printStackTrace() {
            rawStackTracePrintCount++;
            super.printStackTrace();
        }
    }
}
