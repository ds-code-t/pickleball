package tools.dscode.engine.match;

import io.cucumber.java.en.Given;

/** Consumer-style glue. The host test does not import Cucumber. */
public class JavaStepHostFixture {
    @Given("^I count (\\d+)$")
    public String count(String group) {
        return group;
    }
}
