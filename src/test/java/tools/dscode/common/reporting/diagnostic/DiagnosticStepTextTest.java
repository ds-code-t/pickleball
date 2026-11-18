package tools.dscode.common.reporting.diagnostic;

import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticStepTextTest {
    @TempDir
    Path tempDir;

    @Test
    void nativeStepEventStoresTheStepText() throws Exception {
        Path root = tempDir.resolve("diagnostic-runs");
        DiagnosticReporter reporter = new DiagnosticReporter(Map.of(
                "pkb_diagnostic_output", root.toString(),
                "pkb_run_id", "run-step-text"
        ));
        reporter.startScenario(null);
        StepExtension step = new StepExtension("* ", "the total is 10");
        reporter.beginStep(step);
        reporter.endStep(step, new Result(Status.PASSED, Duration.ZERO, null), null);
        Path events;
        try (Stream<Path> scenarios = Files.list(root.resolve("run-step-text").resolve("scenarios"))) {
            events = scenarios.filter(Files::isDirectory).findFirst().orElseThrow().resolve("events.jsonl");
        }
        String text = Files.readString(events);
        assertTrue(text.contains("the total is 10"), text);
        assertFalse(text.contains("\"text\":null"), text);
        assertFalse(text.contains("\"text\":\"null\""), text);
    }
}
