package tools.dscode.engine.adapter;

import io.cucumber.java.en.Given;

import java.util.ArrayList;
import java.util.List;

public final class SuiteHookSteps {
    public static final List<String> ORDER = new ArrayList<>();

    private SuiteHookSteps() {
    }

    public static void reset() {
        ORDER.clear();
    }

    @Given("a suite hook step")
    public static void step() {
        ORDER.add("step");
    }
}
