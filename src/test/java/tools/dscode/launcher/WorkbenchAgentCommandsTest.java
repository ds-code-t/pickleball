package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchAgentCommandsTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearCoordination() {
        AgentCoordination.clearCurrent();
    }

    @Test
    void hintPrintsLadderRunVarsAndNextDiscover() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=chrome\n");

        String previousParallel = System.getProperty("pkb_parallel");
        String previousRunVarsParallel = System.getProperty("pkb_runvars.pkb_parallel");
        System.setProperty("pkb_parallel", "80");
        System.setProperty("pkb_runvars.pkb_parallel", "80");
        try {
            Output output = run("hint", tempDir.toString());

            assertEquals(0, output.exitCode());
            assertTrue(output.stdout().contains("pkb_browser=CHROME_HEADLESS"));
            assertTrue(output.stdout().contains("pkb_reportretention=failed"));
            assertTrue(output.stdout().contains("NEXT: run discover"));
            assertTrue(output.stdout().contains("Dry-run resolve"));
            assertTrue(output.stdout().contains("launcher JVM"));
            assertTrue(output.stdout().contains("not the Discover worker"));
            assertTrue(output.stdout().contains("Do not treat resolve-runvars as the environment Discover will use"));
            assertTrue(output.stdout().contains("run record after Discover"));
            assertFalse(output.stdout().contains("pkb_runvarssealed"));
            assertTrue(output.stdout().contains("pkb_overriderunvars") || output.stdout().contains("sealed="));
            assertTrue(output.stdout().contains("provenance="));
            assertFalse(output.stdout().contains("MUST"));
            assertFalse(output.stdout().contains("pkb_parallel=80"));
        } finally {
            restoreProperty("pkb_parallel", previousParallel);
            restoreProperty("pkb_runvars.pkb_parallel", previousRunVarsParallel);
        }
    }

    @Test
    void resolveRunVarsPrintsDryRunWithoutStartingTests() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=chrome\n");

        Output output = run("resolve-runvars", tempDir.toString());

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("Dry-run resolve"));
        assertTrue(output.stdout().contains("launcher JVM"));
        assertTrue(output.stdout().contains("not the Discover worker"));
        assertTrue(output.stdout().contains("Do not treat resolve-runvars as the environment Discover will use"));
        assertTrue(output.stdout().contains("run record after Discover"));
        assertFalse(output.stdout().contains("pkb_runvarssealed"));
        assertTrue(output.stdout().contains("sealed="));
        assertTrue(output.stdout().contains("pkb_overriderunvars"));
        assertTrue(output.stdout().contains("provenance="));
        assertFalse(output.stdout().contains("NEXT: run discover"));
    }

    @Test
    void agentGuideSaysResolveRunVarsIsNotTheDiscoverEnvironment() throws Exception {
        String line = "Do not treat resolve-runvars as the environment Discover will use.";
        for (String path : List.of(
                "docs/consumer-agent-guide.md",
                "src/main/resources/META-INF/pickleball/guidance/AGENT-GUIDE.md",
                "src/main/resources/META-INF/pickleball/guidance/docs/consumer-agent-guide.md"
        )) {
            String text = Files.readString(Path.of(path));
            assertTrue(text.contains(line), path);
            assertTrue(text.contains(
                    "The Workbench GUI is only a lightweight head over state and controls that already exist under the hood."
            ), path);
            assertFalse(text.contains("pkb_runvarssealed"), path);
            assertTrue(text.contains("pkb_overriderunvars"), path);
            assertTrue(text.contains("Never supply `pkb_run_profile` as input")
                    || text.contains("Never supply pkb_run_profile as input"), path);
        }
    }

    @Test
    void exportGuidanceUsesJdkLocalStore() throws Exception {
        Path outputDir = tempDir.resolve("guidance");
        Output output = run("export-guidance", outputDir.toString());
        assertEquals(0, output.exitCode());
        assertTrue(Files.isRegularFile(outputDir.resolve("AGENT-GUIDE.md")));
        assertTrue(output.stdout().contains("NEXT: follow AGENT-GUIDE"));
        assertTrue(output.stdout().contains("Workbench discover"));
    }

    @Test
    void discoverWrapsMavenAndRecordsSnapshot() throws Exception {
        writeProjectWrapper();
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke"},
                System.out,
                System.err,
                (project, command, out, err) -> {
                    captured.add(command);
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-1",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=4, pkb_reportingmode=diagnostic, pkb_tags=@smoke",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 1;
                }
        );

        assertEquals(1, exit);
        assertEquals(1, captured.size());
        assertTrue(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        assertTrue(captured.getFirst().stream().anyMatch(item ->
                item.startsWith("-Dpkb_runvars=") && item.contains("pkb_reportretention=failed")));
        assertTrue(captured.getFirst().contains("-Dpkb_run_purpose=workbench-discover"));
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.read(tempDir);
        assertTrue(snapshot.hasRunVars());
        assertTrue(snapshot.runProfile().contains("pkb_browser=CHROME_HEADLESS"));
    }

    @Test
    void discoverNextIsConfirmNotIsolateMavenExec() throws Exception {
        writeProjectWrapper();
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                System.err,
                (project, command, out, err) -> {
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-next",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=4, pkb_reportingmode=diagnostic, pkb_tags=@smoke",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );

        assertEquals(0, exit);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("NEXT: confirm"));
        assertTrue(text.contains("isolate"));
        assertTrue(text.contains("execute-step"));
        assertTrue(text.contains("open-scenario"));
        assertTrue(text.contains("--example"));
        assertTrue(text.contains("Do not open the GUI for your own testing"));
        assertTrue(text.contains("While testing for yourself, stay headless"));
        assertFalse(text.contains("Do not start the GUI"));
        assertFalse(text.contains("only controls"));
    }

    @Test
    void discoverRetentionAllWritesAll() throws Exception {
        writeProjectWrapper();
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke", "--retention=all"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                System.err,
                (project, command, out, err) -> {
                    captured.add(command);
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-all",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=4, pkb_reportingmode=diagnostic, pkb_reportretention=all, pkb_tags=@smoke",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );

        assertEquals(0, exit);
        String printed = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("pkb_reportretention=all"));
        assertFalse(printed.contains("pkb_reportretention=failed"));
        assertTrue(captured.getFirst().stream().anyMatch(item ->
                item.startsWith("-Dpkb_runvars=") && item.contains("pkb_reportretention=all")));
    }

    @Test
    void confirmRequiresDiscoverSnapshot() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"confirm", tempDir.toString()},
                System.out,
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(1, exit);
        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("No prior Discover snapshot"));
        assertFalse(errors.toLowerCase().contains("register"));
    }

    @Test
    void shortLogNoteInboxAndFinishUseTheProjectBoard() throws Exception {
        Output note = run("note", tempDir.toString(), "--agent=agent-board", "--text=looking at row 2");
        assertEquals(0, note.exitCode(), note.stderr());
        Output log = run("short-log", tempDir.toString());
        assertEquals(0, log.exitCode(), log.stderr());
        assertTrue(log.stdout().contains("agent-board"));
        assertTrue(log.stdout().contains("looking at row 2"));

        Output write = run(
                "inbox", tempDir.toString(), "--write", "--to=agent-other",
                "--from=agent-board", "--text=window is taken"
        );
        assertEquals(0, write.exitCode(), write.stderr());
        Output hidden = run("inbox", tempDir.toString(), "--list", "--agent=agent-board");
        assertEquals(0, hidden.exitCode(), hidden.stderr());
        assertTrue(hidden.stdout().contains("(no inbox notes)"));
        Output listed = run("inbox", tempDir.toString(), "--list", "--agent=agent-other");
        assertTrue(listed.stdout().contains("window is taken"));
        Output taken = run("inbox", tempDir.toString(), "--take", "--agent=agent-other");
        assertTrue(taken.stdout().contains("window is taken"));
        Output empty = run("inbox", tempDir.toString(), "--list", "--agent=agent-other");
        assertTrue(empty.stdout().contains("(no inbox notes)"));

        Output discover = run(
                "finish", tempDir.toString(), "--run-id=missing-run", "--learned=no such run"
        );
        assertEquals(1, discover.exitCode());

        AgentCoordination.begin(tempDir, AgentCoordination.Request.of(
                "run-finish", "agent-board", null, null, null, null, "discover"
        ));
        Output finished = run(
                "finish", tempDir.toString(), "--run-id=run-finish", "--learned=the empty row fails"
        );
        assertEquals(0, finished.exitCode(), finished.stderr());
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-finish").resolve("record.json")
        );
        assertTrue(record.contains("the empty row fails"));
        assertTrue(record.contains("STOPPED"));
        String board = Files.readString(AgentCoordination.logFile(tempDir));
        assertTrue(board.contains("\tstart\t"));
        assertTrue(board.contains("\tstop\t"));
        assertTrue(board.contains("record.json"));
        assertTrue(Files.isDirectory(AgentCoordination.runDirectory(tempDir, "run-finish")));
    }

    @Test
    void boardCommandsDoNotConsumeAPostAndSweepOnlyExpiredRows() throws Exception {
        Output presence = run("presence", tempDir.toString(), "--touch", "--agent=agent-board", "--run-id=run-board");
        assertEquals(0, presence.exitCode(), presence.stderr());
        assertTrue(Files.isRegularFile(tempDir.resolve(".pickleball/presence/agent-board.json")));

        Output post = run(
                "post", tempDir.toString(), "--write", "--to=all", "--from=agent-board",
                "--text=I have the window", "--ttl=1h"
        );
        assertEquals(0, post.exitCode(), post.stderr());
        Output listed = run("post", tempDir.toString(), "--list");
        assertTrue(listed.stdout().contains("I have the window"));
        Output taken = run("inbox", tempDir.toString(), "--take", "--agent=agent-board");
        assertTrue(taken.stdout().contains("(no inbox notes)"));
        assertTrue(run("post", tempDir.toString(), "--list").stdout().contains("I have the window"));

        Output history = run("history", tempDir.toString(), "--append", "--text=2.1.14 files fix branch commit merge run-board");
        assertEquals(0, history.exitCode(), history.stderr());
        assertTrue(run("history", tempDir.toString(), "--tail").stdout().contains("run-board"));

        Output sweep = run("gc-runs", tempDir.toString());
        assertEquals(0, sweep.exitCode(), sweep.stderr());
        assertTrue(Files.isRegularFile(tempDir.resolve(".pickleball/presence/agent-board.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve(".pickleball/history.log")));
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

    private void writeProjectWrapper() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = tempDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private record Output(int exitCode, String stdout, String stderr) { }
}
