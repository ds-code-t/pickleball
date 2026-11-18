package tools.dscode.warmglue;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.DataTableType;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.When;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Consumer-style glue for the warm JVM. The hook takes {@link Scenario}
 * and the step converts a table with {@code @DataTableType}.
 */
public class WarmGlueSteps {
    private String ready;

    @Before
    public void prepare(Scenario scenario) {
        ready = "ready:" + scenario.getName();
    }

    @DataTableType
    public SignIn signIn(Map<String, String> entry) {
        return new SignIn(entry.get("user"), entry.get("role"));
    }

    @When("the operator signs in with")
    public void signIn(DataTable table) throws Exception {
        SignIn row = table.asList(SignIn.class).getFirst();
        table.asMaps();
        String out = System.getProperty("pkb_warm_glue_result");
        if (out == null || out.isBlank()) {
            throw new IllegalStateException("pkb_warm_glue_result is not set");
        }
        Files.writeString(Path.of(out), ready + "|" + row.user() + "|" + row.role() + "\n", StandardCharsets.UTF_8);
    }

    public record SignIn(String user, String role) {
    }
}
