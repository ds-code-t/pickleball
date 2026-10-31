package tools.dscode.testengine.parity;

import io.cucumber.java.en.Given;
import tools.dscode.testengine.PKB_props;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the run profile the scenario actually observed. */
public final class LaunchParitySteps {
    @Given("the launch parity probe records the resolved profile")
    public void record() throws Exception {
        String configured = System.getProperty("pickleball.parity.out");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("pickleball.parity.out is not set");
        }
        Path out = Path.of(configured);
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(out, PKB_props.runProfile() + "\n", StandardCharsets.UTF_8);
    }
}
