package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;
import tools.dscode.workbench.player.WorkbenchSaveResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSessionActionsTest {
    @TempDir
    Path project;

    @Test
    void openScenarioExamplePlayAndDiagnosticRunShareOneCommandPath() throws Exception {
        Path feature = project.resolve("src/test/resources/features/shop.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Shop
                  Scenario Outline: Buy
                    When buy <item>
                    Examples:
                      | item |
                      | a |
                      | b |
                      | c |
                    Examples:
                      | item |
                      | d |
                      | e |
                      | f |
                """);
        try (WorkbenchController controller = new WorkbenchController(project)) {
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("session-test"));
            WorkbenchSessionActions actions = new WorkbenchSessionActions(controller);
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> {
                Map<String, Object> opened = actions.openScenario("shop.feature", "Buy", "2.2");
                assertEquals("SUCCESS", opened.get("status"));
                assertEquals(5, controller.playback().origin().exampleRow());
                assertTrue(controller.playback().playPlan().steps().getFirst().executeText().contains("buy e"));

                Map<String, Object> first = actions.example("5 1");
                assertEquals("SUCCESS", first.get("status"));
                assertEquals(1, controller.playback().origin().exampleRow());
                assertTrue(controller.playback().playPlan().steps().getFirst().executeText().contains("buy a"));

                Map<String, Object> paused = actions.pause();
                assertEquals("PAUSED", paused.get("playback"));

                Map<String, Object> played = actions.play();
                assertEquals("FAILED", played.get("status"));

                Map<String, Object> shown = actions.diagnosticRun("run-42");
                assertEquals("SUCCESS", shown.get("status"));
                assertEquals("run-42", actions.selectedDiagnosticRun());

                Map<String, Object> stopped = actions.dispatch("stop", Map.of());
                assertEquals("SUCCESS", stopped.get("status"));
                assertEquals("STOPPED", stopped.get("playback"));
            });
            IllegalArgumentException invalid = assertThrows(
                    IllegalArgumentException.class,
                    () -> actions.example("0")
            );
            assertTrue(invalid.getMessage().contains("'0'"));
            assertFalse(invalid.getMessage().startsWith("No scenarios matched"));
        }
    }

    @Test
    void windowReceivesEverySessionCommand() throws Exception {
        Path feature = project.resolve("src/test/resources/features/shop.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Shop
                  Scenario: Buy
                    When buy a
                """);
        try (WorkbenchController controller = new WorkbenchController(project)) {
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("session-test"));
            WorkbenchSessionActions actions = new WorkbenchSessionActions(controller);
            RecordingWindow window = new RecordingWindow(feature);
            actions.installWindow(window);
            List<Map<String, String>> calls = List.of(
                    Map.of("op", "open-scenario", "feature", "shop.feature", "name", "Buy", "example", "1"),
                    Map.of("op", "example", "example", "1"),
                    Map.of("op", "play"),
                    Map.of("op", "from-here", "fromHere", "1"),
                    Map.of("op", "pause"),
                    Map.of("op", "execute-step", "text", "When buy a"),
                    Map.of("op", "insert-step", "text", "Then stay"),
                    Map.of("op", "update-step", "text", "When buy a"),
                    Map.of("op", "diagnostic-run", "run", "run-42"),
                    Map.of("op", "save"),
                    Map.of("op", "refresh"),
                    Map.of("op", "session-sync"),
                    Map.of("op", "worker-start"),
                    Map.of("op", "worker-restart"),
                    Map.of("op", "worker-stop"),
                    Map.of("op", "stop")
            );
            for (Map<String, String> args : calls) {
                String op = args.get("op");
                Map<String, Object> result = actions.dispatch(op, args);
                assertEquals("SUCCESS", result.get("status"), op + " " + result);
                assertEquals(op, result.get("op"), result.toString());
                assertTrue(window.calls.contains(op), window.calls.toString());
            }
            assertEquals("WRITTEN", window.save.status());
        }
    }

    private static final class RecordingWindow implements WorkbenchSessionActions.Window {
        private final Path feature;
        private final List<String> calls = new ArrayList<>();
        private WorkbenchSaveResult save = WorkbenchSaveResult.cancelled("not called");

        private RecordingWindow(Path feature) {
            this.feature = feature;
        }

        @Override
        public void beginCommand() {
        }

        @Override
        public void endCommand() {
        }

        @Override
        public void openResolved(WorkbenchSessionActions.Opened opened) {
            calls.add("open-scenario");
        }

        @Override
        public void selectExample(String selector) {
            calls.add("example");
        }

        @Override
        public void playFromStart() {
            calls.add("play");
        }

        @Override
        public void playFromHere(String stepId) {
            calls.add("from-here");
        }

        @Override
        public void pause() {
            calls.add("pause");
        }

        @Override
        public void stopPlayback() {
            calls.add("stop");
        }

        @Override
        public void executeStep(String text) {
            calls.add("execute-step");
        }

        @Override
        public void insertStep(String text) {
            calls.add("insert-step");
        }

        @Override
        public void updateStep(String text) {
            calls.add("update-step");
        }

        @Override
        public void diagnosticRun(String runId) {
            calls.add("diagnostic-run");
        }

        @Override
        public WorkbenchSaveResult presentSave() {
            calls.add("save");
            save = WorkbenchSaveResult.written(feature, "Buy");
            return save;
        }

        @Override
        public void syncProject() {
            calls.add("session-sync");
        }

        @Override
        public void refreshStatus() {
            calls.add("refresh");
        }

        @Override
        public void startWorker() {
            calls.add("worker-start");
        }

        @Override
        public void restartWorker() {
            calls.add("worker-restart");
        }

        @Override
        public void stopWorker() {
            calls.add("worker-stop");
        }
    }
}
