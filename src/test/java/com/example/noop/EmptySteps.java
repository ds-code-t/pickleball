package com.example.noop;

/** Fixture. An empty body is noop glue. A body that calls something is not. */
public class EmptySteps {
    public void click(String name) {
    }

    public void real(String name) {
        name.length();
    }
}
