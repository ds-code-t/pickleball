package tools.dscode.workbench.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void sparseReadersReturnCatalogIndexClustersAndSummaryWithoutEventsOrScreenshots() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path run = root.resolve("run-1");
        Path scenario = run.resolve("scenarios/scenario-1");
        Files.createDirectories(scenario.resolve("screenshots"));
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-1","outcome":"FAILED"}]}
                """);
        Files.writeString(run.resolve("run-index.json"), """
                {"runId":"run-1","outcome":"FAILED","scenarioCount":1}
                """);
        Files.writeString(run.resolve("clusters.json"), """
                {"clusters":[{"id":"c1","size":1}]}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"scenarioId":"scenario-1","outcome":"FAILED","lastStepText":"Then stay"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), "{\"stepText\":\"secret-event\"}\n");
        Files.write(scenario.resolve("screenshots/frame-1.png"), new byte[]{9, 9, 9});

        DiagnosticEvidenceNavigator navigator = new DiagnosticEvidenceNavigator(project);
        String catalog = navigator.catalogDocument().toString();
        assertTrue(catalog.contains("run-1"));
        assertFalse(catalog.contains("secret-event"));

        String runDocument = navigator.runDocument("run-1").toString();
        assertTrue(runDocument.contains("FAILED"));
        assertTrue(runDocument.contains("\"clusters\""));
        assertFalse(runDocument.contains("secret-event"));
        assertFalse(runDocument.contains("frame-1.png"));

        String summary = navigator.scenarioSummaryDocument("run-1", "scenario-1").toString();
        assertTrue(summary.contains("Then stay"));
        assertFalse(summary.contains("secret-event"));
        assertFalse(summary.contains("frame-1.png"));
    }
}
