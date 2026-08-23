package tools.dscode.workbench.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticEvidenceNavigatorTest {
    @TempDir
    Path project;

    @Test
    void readsCatalogThenIndexAndScreenshotFramesWithoutInventingRuns() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path run = root.resolve("run-1");
        Path scenario = run.resolve("scenarios/scenario-1");
        Path shots = scenario.resolve("screenshots");
        Files.createDirectories(shots);
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-1","outcome":"PASSED"}]}
                """);
        Files.writeString(run.resolve("run-index.json"), """
                {"runId":"run-1","outcome":"PASSED"}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"lastStepText":"Then stay"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"stepText":"Given navigate to: URL.home"}
                {"stepText":"Then stay"}
                """);
        Path png = shots.resolve("frame-1.png");
        Files.write(png, new byte[]{1, 2, 3});

        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        assertTrue(navigator.available());
        assertEquals("run-1", navigator.catalogRuns().getFirst().runId());

        DiagnosticEvidenceNavigator.Timeline timeline = navigator.timeline(run);
        assertEquals(1, timeline.frames().size());
        assertEquals("Given navigate to: URL.home", timeline.frames().getFirst().stepText());

        assertTrue(navigator.layers(run, "scenario-1").stream()
                .anyMatch(layer -> layer.layer() == DiagnosticEvidenceNavigator.Layer.EVENTS && layer.present()));
        assertTrue(new DiagnosticEvidenceNavigator(project, project.resolve("missing")).catalogRuns().isEmpty());
    }
}
