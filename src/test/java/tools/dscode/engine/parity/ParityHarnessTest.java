package tools.dscode.engine.parity;

import org.junit.jupiter.api.Test;
import tools.dscode.coredefinitions.LogAndAssertSteps;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParityHarnessTest {
    @Test
    void frameworkAssertMatchesAndSuitesAreInTheHarness() throws Exception {
        String assertFeature = """
                Feature: Assert
                  Scenario: One
                    * ASSERT: "a" equals "a"
                """;
        List<DualRun.Divergence> framework = ParityHarness.compare(assertFeature, LogAndAssertSteps.class);
        assertTrue(framework.isEmpty(), framework.toString());
        assertFalse(ParityHarness.featuresUnder(Path.of("src/test/resources")).isEmpty());
        assertFalse(ParityHarness.featuresUnder(Path.of("maven-consumer-project/src/test/resources/features")).isEmpty());
    }
}
