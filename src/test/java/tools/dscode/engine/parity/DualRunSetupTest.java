package tools.dscode.engine.parity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DualRunSetupTest {
    @Test
    void theSameGlueFeatureMatchesOnTheAdapterAndTheNativeEngine() throws Exception {
        String feature = """
                Feature: Echo
                  Scenario: Hi
                    Given native engine echo "hi"
                """;
        assertTrue(DualRun.compare(feature, EchoParityGlue.class).isEmpty());
    }
}
