package com.example.glue;

import io.cucumber.java.en.Given;

/** Consumer-style glue used to prove empty bodies are flagged and real bodies are not. */
public final class NoopSamples {
    @Given("agent fast empty glue")
    public void emptyGlue() {
    }

    public void realGlue() {
        System.nanoTime();
    }

    public String returnsNull() {
        return null;
    }
}
