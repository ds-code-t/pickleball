package com.example.glue;

/** Consumer-style glue used to prove empty bodies are flagged and real bodies are not. */
public final class NoopSamples {
    public void emptyGlue() {
    }

    public void realGlue() {
        System.nanoTime();
    }

    public String returnsNull() {
        return null;
    }
}
