package tools.dscode.engine.parity;

import io.cucumber.java.en.Given;

public class EchoParityGlue {
    @Given("native engine echo {string}")
    public String echo(String value) {
        return value;
    }
}
