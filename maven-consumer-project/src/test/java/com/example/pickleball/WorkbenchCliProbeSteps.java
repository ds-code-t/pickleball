package com.example.pickleball;

import io.cucumber.java.en.Given;

/** Non-browser steps used only by the Workbench CLI end-to-end probes. */
public final class WorkbenchCliProbeSteps {
    @Given("the workbench cli probe records a pass")
    public void pass() {
        // The Workbench asserts the diagnostic pack, not this method's return.
    }

    @Given("the workbench cli probe fails on purpose")
    public void fail() {
        throw new AssertionError("workbench cli probe failure");
    }
}
