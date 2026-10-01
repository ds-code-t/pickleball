package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunWindowTest {
    @TempDir
    Path project;

    @Test
    void openingARunLoadsItsRecordAndDoesNotStartATest() throws Exception {
        Path run = writeRun("alpha", "ALPHA-RECORD", "alpha.log", "alpha-report.txt", "alpha.properties");
        Map<String, String> before = snapshot(run);

        WindowDriver.Decision decision = WindowDriver.show(project, "alpha", "agent-1", true);

        assertEquals("alpha", decision.loaded().runId());
        assertTrue(decision.loaded().recordText().contains("ALPHA-RECORD"));
        assertTrue(decision.loaded().logs().stream().anyMatch(path -> path.toString().contains("alpha.log")));
        assertTrue(decision.loaded().reports().stream().anyMatch(path -> path.toString().contains("alpha-report.txt")));
        assertTrue(decision.loaded().config().stream().anyMatch(path -> path.toString().contains("alpha.properties")));
        assertTrue(decision.driving());
        assertFalse(decision.readOnly());
        assertEquals("agent-1", decision.state().driverAgent());
        assertEquals("alpha", decision.state().liveRunId());
        assertEquals(before, snapshot(run));
        assertFalse(Files.exists(run.resolve("session").resolve("cli-session.json")));
    }

    @Test
    void switchingRunsShowsTheOtherFilesAndDoesNotWriteTheFirstRun() throws Exception {
        Path first = writeRun("alpha", "ALPHA-RECORD", "alpha.log", "alpha-report.txt", "alpha.properties");
        Path second = writeRun("beta", "BETA-RECORD", "beta.log", "beta-report.txt", "beta.properties");
        WindowDriver.show(project, "alpha", "agent-1", true);
        Map<String, String> before = snapshot(first);

        WindowDriver.Decision decision = WindowDriver.show(project, "beta", null, false);

        assertEquals("beta", decision.loaded().runId());
        assertTrue(decision.loaded().recordText().contains("BETA-RECORD"));
        assertTrue(decision.loaded().logs().stream().anyMatch(path -> path.getFileName().toString().equals("beta.log")));
        assertTrue(decision.loaded().reports().stream().anyMatch(path -> path.getFileName().toString().equals("beta-report.txt")));
        assertTrue(decision.loaded().config().stream().anyMatch(path -> path.getFileName().toString().equals("beta.properties")));
        assertEquals("alpha", decision.state().liveRunId());
        assertEquals("beta", decision.state().viewedRunId());
        assertTrue(decision.readOnly());
        assertFalse(decision.driving());
        assertEquals(before, snapshot(first));
        assertTrue(Files.isRegularFile(second.resolve("record.json")));
    }

    @Test
    void closingTheWindowLeavesTheRunDirectoryAndTheShortLog() throws Exception {
        Path run = writeRun("alpha", "ALPHA-RECORD", "alpha.log", "alpha-report.txt", "alpha.properties");
        Path log = PickleballLocalLayout.root(project).resolve("agent-log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "keep-this-short-log\n");
        WindowDriver.show(project, "alpha", "agent-1", true);
        Map<String, String> runBefore = snapshot(run);
        byte[] logBefore = Files.readAllBytes(log);

        WindowDriver.closeWindow(project);

        assertEquals(runBefore, snapshot(run));
        assertEquals(new String(logBefore), Files.readString(log));
        assertFalse(WindowDriver.read(project).open());
        assertTrue(WindowDriver.read(project).driverAgent() == null);
    }

    @Test
    void aSecondAgentDoesNotBecomeASecondDriver() {
        writeRun("alpha", "ALPHA-RECORD", "alpha.log", "alpha-report.txt", "alpha.properties");
        writeRun("beta", "BETA-RECORD", "beta.log", "beta-report.txt", "beta.properties");
        WindowDriver.show(project, "alpha", "agent-1", true);

        WindowDriver.Decision viewed = WindowDriver.show(project, "beta", "agent-2", true);

        assertFalse(viewed.driving());
        assertTrue(viewed.readOnly());
        assertEquals("agent-1", viewed.state().driverAgent());
        assertEquals("alpha", viewed.state().liveRunId());
        assertEquals("beta", viewed.state().viewedRunId());
        assertTrue(WindowDriver.refuseSecondDriver(project, "agent-2").isPresent());
        assertTrue(WindowDriver.refuseSecondDriver(project, "agent-2").get()
                .contains("does not become a second driver"));
        assertTrue(WindowDriver.refuseSecondDriver(project, "agent-1").isEmpty());
    }

    private Path writeRun(String id, String record, String logName, String reportName, String configName) {
        try {
            Path dir = PickleballLocalLayout.root(project).resolve("runs").resolve(id);
            Files.createDirectories(dir.resolve("session"));
            Files.createDirectories(dir.resolve("reports"));
            Files.createDirectories(dir.resolve("config"));
            Files.writeString(dir.resolve("record.json"), "{\"runId\":\"" + id + "\",\"note\":\"" + record + "\"}\n");
            Files.writeString(dir.resolve("session").resolve(logName), record + "-log\n");
            Files.writeString(dir.resolve("reports").resolve(reportName), record + "-report\n");
            Files.writeString(dir.resolve("config").resolve(configName), record + "-config\n");
            return dir;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        if (!Files.exists(root)) return files;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(path).toString(), HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return files;
    }
}
