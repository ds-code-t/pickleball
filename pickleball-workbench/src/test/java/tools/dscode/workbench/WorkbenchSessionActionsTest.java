package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.WindowDriver;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;
import tools.dscode.workbench.player.WorkbenchSaveResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    @Test
    void showRunLoadsTheRecordAndASecondAgentDoesNotDrive() throws Exception {
        Path alpha = writeRun("alpha", "ALPHA-RECORD");
        writeRun("beta", "BETA-RECORD");
        Path shortLog = PickleballLocalLayout.root(project).resolve("agent-log");
        Files.createDirectories(shortLog.getParent());
        Files.writeString(shortLog, "keep-this-short-log\n");
        try (WorkbenchController controller = new WorkbenchController(project)) {
            WorkbenchSessionActions actions = new WorkbenchSessionActions(controller);
            RecordingWindow window = new RecordingWindow(alpha);
            actions.installWindow(window);
            Map<String, String> before = snapshot(alpha);

            Map<String, Object> shown = actions.showRun("alpha", "agent-1", true);
            assertEquals(Boolean.FALSE, shown.get("startedTest"));
            assertTrue(String.valueOf(shown.get("record")).contains("ALPHA-RECORD"));
            assertEquals("alpha", shown.get("liveRun"));
            assertEquals("agent-1", shown.get("driver"));
            assertTrue(window.calls.contains("show-run:alpha"), window.calls.toString());

            Map<String, Object> other = actions.showRun("beta", null, false);
            assertEquals(Boolean.FALSE, other.get("startedTest"));
            assertTrue(String.valueOf(other.get("record")).contains("BETA-RECORD"));
            assertEquals("alpha", other.get("liveRun"));
            assertEquals(Boolean.TRUE, other.get("readOnly"));
            assertEquals(before, snapshot(alpha));
            assertTrue(window.calls.contains("show-run:beta"), window.calls.toString());

            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    () -> actions.dispatch("play", Map.of("agent", "agent-2"))
            );
            assertTrue(refused.getMessage().contains("does not become a second driver"));
            assertFalse(window.calls.contains("play"));
            assertEquals("agent-1", WindowDriver.read(project).driverAgent());

            byte[] logBefore = Files.readAllBytes(shortLog);
            Map<String, String> runBefore = snapshot(alpha);
            actions.closeWindow();
            assertTrue(window.calls.contains("close-window"));
            assertEquals(runBefore, snapshot(alpha));
            assertEquals(new String(logBefore), Files.readString(shortLog));
            assertFalse(WindowDriver.read(project).open());
        }
    }

    private Path writeRun(String id, String marker) throws IOException {
        Path run = PickleballLocalLayout.root(project).resolve("runs").resolve(id);
        Files.createDirectories(run.resolve("reports"));
        Files.createDirectories(run.resolve("config"));
        Files.writeString(run.resolve("record.json"), "{\"runId\":\"" + id + "\",\"marker\":\"" + marker + "\"}\n");
        Files.writeString(run.resolve(id + ".log"), marker + "\n");
        Files.writeString(run.resolve("reports").resolve(id + "-report.txt"), marker + "\n");
        Files.writeString(run.resolve("config").resolve(id + ".properties"), "name=" + marker + "\n");
        return run;
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(path).toString(), HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return files;
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

        @Override
        public void showRun(WindowDriver.Decision decision) {
            calls.add("show-run:" + decision.loaded().runId());
        }

        @Override
        public void closeWindow() {
            calls.add("close-window");
        }
    }
}
