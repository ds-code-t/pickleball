package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.PickleballLocalStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSwitchTest {
    @TempDir
    Path tempDir;

    @Test
    void useVersionRewritesThePointerAndLeavesRunsInvestigationsAndTheOtherTree() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "guide-two");
        writeComplete(pickleball, "2.1.0", "guide-two-one");
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow("2.0.0"));
        Files.createDirectories(pickleball.resolve("investigations/keep"));
        Files.writeString(pickleball.resolve("investigations/keep/investigation.json"), "{\"id\":\"keep\"}\n");
        Files.writeString(pickleball.resolve("history.log"), "keep history\n");
        Files.createDirectories(pickleball.resolve("posts"));
        Files.writeString(pickleball.resolve("posts/note.json"), "{\"text\":\"stay\"}\n");
        Files.createDirectories(pickleball.resolve("inbox/agent-a"));
        Files.writeString(pickleball.resolve("inbox/agent-a/note.json"), "{\"message\":\"stay\"}\n");

        AgentCoordination.Run running = AgentCoordination.begin(
                tempDir,
                AgentCoordination.Request.of("run-live", "agent-live", null, null, null, null, "discover")
        );
        Files.writeString(running.browserProfileDirectory().resolve("Cookies"), "live");
        String before = Files.readString(running.recordFile());
        assertTrue(before.contains("\"pickleballVersion\" : \"2.0.0\""), before);
        assertTrue(before.contains("RUNNING"));

        Output switched = run("use-version", "--version=2.1.0", tempDir.toString());
        assertEquals(0, switched.exitCode(), switched.stderr());
        assertEquals("2.1.0", PickleballLocalLayout.readCurrent(pickleball).orElseThrow().pickleballVersion());
        assertEquals("guide-two-one", Files.readString(pickleball.resolve("AGENT-GUIDE.md")).trim());
        assertEquals("guide-two\n", Files.readString(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md")));
        assertTrue(Files.readString(pickleball.resolve("open/pickleball-workbench.sh")).contains("open-2.1.0"));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.1.0/.last-used")));
        assertEquals(before, Files.readString(running.recordFile()));
        assertTrue(Files.isRegularFile(running.browserProfileDirectory().resolve("Cookies")));
        assertTrue(Files.isRegularFile(pickleball.resolve("investigations/keep/investigation.json")));
        assertTrue(Files.readString(pickleball.resolve("history.log")).contains("keep history"));
        assertTrue(Files.isRegularFile(pickleball.resolve("posts/note.json")));
        assertTrue(Files.isRegularFile(pickleball.resolve("inbox/agent-a/note.json")));
        assertEquals(0, AgentCoordination.sweepRuns(tempDir, Instant.now()).profilesRemoved());
        assertTrue(Files.isRegularFile(running.browserProfileDirectory().resolve("Cookies")));
    }

    @Test
    void useVersionToAMissingVersionDoesNotMoveThePointer() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "guide-two");
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow("2.0.0"));
        Output missing = run("use-version", "--version=9.9.9", tempDir.toString());
        assertEquals(1, missing.exitCode());
        assertTrue(missing.stderr().contains("export-guidance from Pickleball 9.9.9 is required"));
        assertEquals("2.0.0", PickleballLocalLayout.readCurrent(pickleball).orElseThrow().pickleballVersion());
    }

    @Test
    void gcVersionsKeepsTodayAndTheCurrentPointerAndRemovesAnUnusedTree() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "current-guide");
        writeComplete(pickleball, "1.9.0", "recent-guide");
        writeComplete(pickleball, "1.8.0", "live-guide");
        writeComplete(pickleball, "1.7.0", "expired-guide");
        writeComplete(pickleball, "1.6.0", "unmarked-guide");
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow("2.0.0"));
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        PickleballLocalLayout.writeLastUsed(pickleball.resolve("v/2.0.0"), now.minus(AgentCoordination.VERSION_UNUSED));
        PickleballLocalLayout.writeLastUsed(pickleball.resolve("v/1.9.0"), now);
        PickleballLocalLayout.writeLastUsed(pickleball.resolve("v/1.8.0"), now.minus(AgentCoordination.VERSION_UNUSED).minusSeconds(5));
        PickleballLocalLayout.writeLastUsed(pickleball.resolve("v/1.7.0"), now.minus(AgentCoordination.VERSION_UNUSED));
        Files.createDirectories(pickleball.resolve("v/1.8.0/workbench"));
        Files.writeString(pickleball.resolve("v/1.8.0/workbench/cli-session.json"), """
                {"pid": %d, "mode": "cli-session"}
                """.formatted(ProcessHandle.current().pid()));
        Files.createDirectories(pickleball.resolve("investigations/keep"));
        Files.writeString(pickleball.resolve("investigations/keep/report.html"), "<p>keep</p>\n");
        Files.createDirectories(pickleball.resolve("v/1.7.0/investigations/legacy"));
        Files.writeString(pickleball.resolve("v/1.7.0/investigations/legacy/investigation.json"), "{\"id\":\"legacy\"}\n");
        Files.writeString(pickleball.resolve("history.log"), "stay\n");
        Files.createDirectories(pickleball.resolve("runs/run-old"));
        Files.writeString(pickleball.resolve("runs/run-old/record.json"), "{\"runId\":\"run-old\"}\n");

        AgentCoordination.VersionSweep sweep = AgentCoordination.sweepVersions(tempDir, now);
        assertEquals(1, sweep.versionsRemoved());
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/1.9.0/AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/1.8.0/AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/1.6.0/AGENT-GUIDE.md")));
        assertFalse(Files.exists(pickleball.resolve("v/1.7.0/AGENT-GUIDE.md")));
        assertFalse(Files.exists(pickleball.resolve("v/1.7.0/workbench")));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/1.7.0/investigations/legacy/investigation.json")));
        assertTrue(Files.isRegularFile(pickleball.resolve("investigations/keep/report.html")));
        assertTrue(Files.isRegularFile(pickleball.resolve("runs/run-old/record.json")));
        assertTrue(Files.readString(pickleball.resolve("history.log")).contains("stay"));
        assertTrue(Files.readString(pickleball.resolve("history.log")).contains("gc-versions removed v/1.7.0"));
        assertEquals(0, AgentCoordination.sweepVersions(tempDir, now).versionsRemoved());
    }

    @Test
    void aRunningRunKeepsItsVersionAndASwitchBackRefreshesLastUsed() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "guide-two");
        writeComplete(pickleball, "2.1.0", "guide-two-one");
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow("2.0.0"));
        AgentCoordination.Run running = AgentCoordination.begin(
                tempDir,
                AgentCoordination.Request.of("run-pinned", "agent-pin", null, null, null, null, "discover")
        );
        assertEquals(0, run("use-version", "--version=2.1.0", tempDir.toString()).exitCode());
        Instant stale = Instant.now().minus(AgentCoordination.VERSION_UNUSED).minusSeconds(30);
        PickleballLocalLayout.writeLastUsed(pickleball.resolve("v/2.0.0"), stale);
        AgentCoordination.sweepVersions(tempDir, Instant.now());
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md")));
        assertTrue(Files.readString(running.recordFile()).contains("\"pickleballVersion\" : \"2.0.0\""));
        assertTrue(Files.readString(running.recordFile()).contains("RUNNING"));

        assertEquals(0, run("use-version", "--version=2.0.0", tempDir.toString()).exitCode());
        Instant used = PickleballLocalLayout.readLastUsed(pickleball.resolve("v/2.0.0")).orElseThrow();
        assertTrue(used.isAfter(stale));
        assertEquals(0, run("use-version", "--version=2.1.0", tempDir.toString()).exitCode());
        AgentCoordination.sweepVersions(tempDir, Instant.now());
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.0.0/AGENT-GUIDE.md")));
        assertTrue(Files.isRegularFile(pickleball.resolve("v/2.1.0/AGENT-GUIDE.md")));
    }

    @Test
    void missingCurrentTreeSaysToExportGuidanceAndNamesTheDeletingCommand() throws Exception {
        Path pickleball = tempDir.resolve(".pickleball");
        writeComplete(pickleball, "2.0.0", "guide-two");
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow("2.0.0"));
        deleteTree(pickleball.resolve("v/2.0.0"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        AgentCoordination.warnMissingCurrentTree(tempDir, new PrintStream(err, true, StandardCharsets.UTF_8));
        String missing = err.toString(StandardCharsets.UTF_8);
        assertTrue(missing.contains("v/2.0.0/ is missing"), missing);
        assertTrue(missing.contains("run export-guidance"), missing);
        assertFalse(missing.contains("deleted by"), missing);

        writeComplete(pickleball, "2.0.0", "guide-two");
        err.reset();
        AgentCoordination.warnMissingCurrentTree(tempDir, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));

        writeComplete(pickleball, "1.7.0", "old-guide");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        PickleballLocalLayout.writeLastUsed(pickleball.resolve("v/1.7.0"), now.minus(AgentCoordination.VERSION_UNUSED));
        AgentCoordination.sweepVersions(tempDir, now);
        assertFalse(Files.isRegularFile(pickleball.resolve("v/1.7.0/AGENT-GUIDE.md")));
        PickleballLocalLayout.writeCurrent(pickleball, PickleballLocalLayout.CurrentPointer.completeNow("1.7.0"));
        err.reset();
        AgentCoordination.warnMissingCurrentTree(tempDir, new PrintStream(err, true, StandardCharsets.UTF_8));
        String named = err.toString(StandardCharsets.UTF_8);
        assertTrue(named.contains("v/1.7.0/ is missing"), named);
        assertTrue(named.contains("deleted by gc-versions"), named);
        assertTrue(named.contains("run export-guidance"), named);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void writeComplete(Path pickleball, String version, String marker) throws Exception {
        Path root = pickleball.resolve("v").resolve(version);
        Files.createDirectories(root.resolve("open"));
        Files.createDirectories(root.resolve("workbench/controller"));
        Files.writeString(root.resolve("AGENT-GUIDE.md"), marker + "\n");
        Files.writeString(root.resolve("GUIDANCE-MANIFEST.json"), """
                {
                  "pickleballVersion": "%s",
                  "files": ["AGENT-GUIDE.md"]
                }
                """.formatted(version));
        Files.writeString(root.resolve("open/pickleball-workbench.sh"), "#!/bin/sh\n# open-" + version + "\n");
        Files.writeString(root.resolve("workbench/controller/keep.jar"), "jar");
    }

    private Output run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Output(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }
}
