package tools.dscode.zzzqqq;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import tools.dscode.registry.GlobalRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static tools.dscode.registry.GlobalRegistry.GLOBAL;
import static tools.dscode.registry.GlobalRegistry.LOCAL;

public class newSteps {
    private int a, b, result;

    @Given("QQQ")
    public void qqq1() {
        System.out.println("@@qqq1");
    }

    @Given("^QQQ2(.*)$")
    public void qqq2(String arg0) {
        System.out.println("@@qqq2" + arg0);
    }
}
