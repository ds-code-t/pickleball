package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSessionCommandsTest {
    @TempDir
    Path project;

    @AfterEach
    void clearCoordination() {
        AgentCoordination.clearCurrent();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void isolateDoesNotBlockOnStdinWhenSessionAlreadyHealthy() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicBoolean started = new AtomicBoolean();
            Output output = run(
                    new String[]{"isolate", project.toString()},
                    launch -> {
                        started.set(true);
                        throw new AssertionError("should not start a second session");
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK SESSION already-running"));
            assertTrue(output.stdout.contains("Only one agent drives an open Workbench window"));
            assertTrue(output.stdout.contains("stays headless"));
            assertFalse(started.get());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void sessionStartDetachesAndDoesNotWaitForTheControllerProcess() throws Exception {
        AtomicReference<FakeSession> started = new AtomicReference<>();
        AtomicReference<Process> child = new AtomicReference<>();
        try {
            Output output = run(
                    new String[]{"session-start", project.toString(), "--example=1", "2", "5"},
                    launch -> {
                        assertEquals("1 2 5", launch.example());
                        FakeSession session = FakeSession.start(launch.project(), "cli-session", launch.sessionFile());
                        started.set(session);
                        Process process = startLongLivedChild();
                        child.set(process);
                        return process;
                    }
            );
            assertEquals(0, output.exitCode, () -> output.stderr + output.stdout);
            assertTrue(output.stdout.contains("ACK SESSION pid="));
            assertFalse(output.stdout.contains("Workbench isolate worker:"));
        } finally {
            Process process = child.get();
            if (process != null) process.destroyForcibly();
            FakeSession session = started.get();
            if (session != null) session.close();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void watchedWindowIsPreferredAndStopDoesNotKillIt() throws Exception {
        try (FakeSession cli = FakeSession.start(project);
             FakeSession ui = FakeSession.start(project, "ui-attach", PickleballLocalLayout.attachFile(project))) {
            AtomicReference<String> hit = new AtomicReference<>();
            ui.onCommand = body -> hit.set(body);
            Output play = run(
                    new String[]{"play", project.toString(), "--ack-only"},
                    launch -> {
                        throw new AssertionError("play must not start a session");
                    }
            );
            assertEquals(0, play.exitCode, play.stderr + play.stdout);
            assertTrue(play.stdout.contains("ACK "));
            assertTrue(hit.get() != null && hit.get().contains("\"op\":\"play\""));
            assertTrue(hit.get().contains(ui.url));

            Process child = startLongLivedChild();
            ui.setPid(child.pid());
            Output stop = run(
                    new String[]{"stop", project.toString()},
                    launch -> {
                        throw new AssertionError("stop must not start a session");
                    }
            );
            assertEquals(0, stop.exitCode, stop.stderr + stop.stdout);
            assertTrue(stop.stdout.contains("playback-stopped"));
            assertTrue(child.isAlive());
            child.destroyForcibly();
            assertTrue(cli.pidAlive());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void startDetachedClosesStdinAndTheChildStarts() throws Exception {
        Path log = project.resolve("session-smoke.log");
        Process process = WorkbenchSessionCommands.startDetached(project, List.of("sleep", "30"), log);
        try {
            assertTrue(process.isAlive());
            assertThrows(IOException.class, () -> {
                process.getOutputStream().write(new byte[]{'x'});
                process.getOutputStream().flush();
            });
        } finally {
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sessionStartLogsStartOnlyAfterTheSessionIsUp() throws Exception {
        AtomicReference<FakeSession> started = new AtomicReference<>();
        AtomicReference<Process> child = new AtomicReference<>();
        try {
            Output output = run(
                    new String[]{"session-start", project.toString(), "--run-id=up-run", "--agent=agent-up"},
                    launch -> {
                        FakeSession session = FakeSession.start(launch.project(), "cli-session", launch.sessionFile());
                        started.set(session);
                        Process process = startLongLivedChild();
                        child.set(process);
                        return process;
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            String log = Files.readString(AgentCoordination.logFile(project));
            assertEquals(1, log.lines().filter(line -> line.contains("\tstart\t")).count());
            assertTrue(log.contains("up-run"));
        } finally {
            Process process = child.get();
            if (process != null) process.destroyForcibly();
            FakeSession session = started.get();
            if (session != null) session.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sessionStartDoesNotLogStartWhenTheProcessDies() throws Exception {
        Process dead = exitedProcess();
        Output output = run(
                new String[]{"session-start", project.toString(), "--run-id=dead-run", "--agent=agent-dead"},
                launch -> dead
        );
        assertEquals(1, output.exitCode, output.stderr + output.stdout);
        Path log = AgentCoordination.logFile(project);
        String text = Files.isRegularFile(log) ? Files.readString(log) : "";
        assertFalse(text.contains("\tstart\t"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void statusNamesNeverStartedStillStartingCrashedAndMissingFile() throws Exception {
        Output never = run(
                new String[]{"status", project.toString()},
                launch -> {
                    throw new AssertionError("status must not start a session");
                }
        );
        assertEquals(1, never.exitCode);
        assertTrue(never.stderr.contains("No healthy Workbench session"));
        assertTrue(never.stderr.contains("(never-started)"));

        AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("boot", "agent", null, null, null, null, "isolate"),
                Instant.now(),
                null,
                false
        );
        Output starting = run(
                new String[]{"status", project.toString(), "--run-id=boot"},
                launch -> {
                    throw new AssertionError("status must not start a session");
                }
        );
        assertTrue(starting.stderr.contains("(still-starting)"), starting.stderr);

        AgentCoordination.finish(project, "boot", null, "STOPPED");
        Output missing = run(
                new String[]{"status", project.toString(), "--run-id=boot"},
                launch -> {
                    throw new AssertionError("status must not start a session");
                }
        );
        assertTrue(missing.stderr.contains("(missing-session-file)"), missing.stderr);

        Process done = exitedProcess();
        Path file = PickleballLocalLayout.cliSessionState(project);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"url":"http://127.0.0.1:9","token":"t","pid":%d,"mode":"cli-session"}
                """.formatted(done.pid()));
        Output crashed = run(
                new String[]{"status", project.toString()},
                launch -> {
                    throw new AssertionError("status must not start a session");
                }
        );
        assertTrue(crashed.stderr.contains("(crashed)"), crashed.stderr);
    }

    @Test
    void eventSpacingAndSleepTextDoNotRecordASleepGap() throws Exception {
        Instant now = Instant.parse("2026-07-20T12:00:10Z");
        var timing = WorkbenchSessionCommands.timingOf(
                new ObjectMapper().readTree("""
                        {"events":[
                          {"timestamp":"2026-07-20T12:00:00Z","stepText":"Given stay"},
                          {"timestamp":"2026-07-20T12:00:07Z","stepText":"And sleep 5"}
                        ]}
                        """),
                Instant.parse("2026-07-20T12:00:00Z"),
                now
        );
        assertEquals(10_000, timing.elapsedRunMs);
        assertEquals(3_000, timing.elapsedStepMs);
        String report = WorkbenchSessionCommands.formatReport(timing, List.of());
        assertTrue(report.contains("elapsed-run-ms=10000"));
        assertTrue(report.contains("sleep-gap=none"));
        assertFalse(report.contains("sleep-gap=true"));

        var close = WorkbenchSessionCommands.timingOf(
                new ObjectMapper().readTree("""
                        {"events":[
                          {"timestamp":"2026-07-20T12:00:00Z","stepText":"Given stay"},
                          {"timestamp":"2026-07-20T12:00:03Z","stepText":"And sleep 5"}
                        ]}
                        """),
                Instant.parse("2026-07-20T12:00:00Z"),
                Instant.parse("2026-07-20T12:00:03Z")
        );
        assertEquals(3_000, close.elapsedRunMs);
        assertTrue(WorkbenchSessionCommands.formatReport(close, List.of()).contains("sleep-gap=none"));

        String recorded = WorkbenchSessionCommands.formatSleepGaps(List.of(
                new tools.dscode.control.protocol.SleepGapMonitor.Gap(
                        Instant.parse("2026-07-20T12:00:45Z"), 45_000
                )
        ));
        assertEquals("sleep-gap=45000ms at 2026-07-20T12:00:45Z", recorded);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void eventsPrintElapsedAndNoSleepGapFromStepText() throws Exception {
        try (FakeSession session = FakeSession.start(project)) {
            session.eventsJson = """
                    {"events":[
                      {"timestamp":"2026-07-20T12:00:00Z","stepText":"Given stay"},
                      {"timestamp":"2026-07-20T12:00:03Z","stepText":"And sleep 5"}
                    ]}
                    """;
            Output output = run(
                    new String[]{"events", project.toString()},
                    launch -> {
                        throw new AssertionError("events must not start a session");
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stdout.contains("elapsed-run-ms="));
            assertTrue(output.stdout.contains("elapsed-step-ms="));
            assertTrue(output.stdout.contains("sleep-gap=none"), output.stdout);
            assertFalse(output.stdout.contains("sleep-gap=true"), output.stdout);

            session.healthJson = """
                    {"status":"ok","sleepGaps":[{"detectedAt":"2026-07-20T12:00:45Z","gapMs":45000}]}
                    """;
            Output stalled = run(
                    new String[]{"events", project.toString()},
                    launch -> {
                        throw new AssertionError("events must not start a session");
                    }
            );
            assertEquals(0, stalled.exitCode, stalled.stderr + stalled.stdout);
            assertTrue(stalled.stdout.contains("sleep-gap=45000ms at 2026-07-20T12:00:45Z"), stalled.stdout);
        }
    }

    @Test
    void sleepGapDocsMeanAMachineSleepNotAGherkinStep() throws Exception {
        String workbench = Files.readString(Path.of("docs/pickleball-workbench.md"));
        String guide = Files.readString(Path.of("docs/consumer-agent-guide.md"));
        for (String doc : List.of(workbench, guide)) {
            assertTrue(doc.contains("sleep-gap"), doc);
            assertTrue(doc.contains("machine sleep") || doc.contains("machine-sleep"), doc);
            assertTrue(doc.contains("not a Gherkin sleep step"), doc);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void executeStepTimeoutRecordsTimeout() throws Exception {
        AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("step-timeout", "agent", null, null, null, null, "execute-step")
        );
        Path sessionFile = AgentCoordination.runDirectory(project, "step-timeout")
                .resolve("session").resolve("cli-session.json");
        try (FakeSession ignored = FakeSession.start(project, "cli-session", sessionFile)) {
            Output output = run(
                    new String[]{
                            "execute-step", project.toString(), "--run-id=step-timeout",
                            "--text=Given stay", "--step-timeout=1"
                    },
                    launch -> {
                        throw new AssertionError("execute-step must not start a session");
                    }
            );
            assertEquals(1, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stderr.contains("TIMEOUT"));
            String record = Files.readString(
                    AgentCoordination.runDirectory(project, "step-timeout").resolve(AgentCoordination.RECORD_FILE)
            );
            assertTrue(record.contains("TIMEOUT"));
            assertFalse(record.contains("PASSED"));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void oneTransientHttpFailureDoesNotRecordTimeout() throws Exception {
        AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("transient-step", "agent", null, null, null, null, "execute-step")
        );
        Path sessionFile = AgentCoordination.runDirectory(project, "transient-step")
                .resolve("session").resolve("cli-session.json");
        try (FakeSession session = FakeSession.start(project, "cli-session", sessionFile)) {
            session.failGets = 1;
            session.succeedAfter = true;
            Output output = run(
                    new String[]{
                            "execute-step", project.toString(), "--run-id=transient-step",
                            "--text=Given stay"
                    },
                    launch -> {
                        throw new AssertionError("execute-step must not start a session");
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stdout.contains("SUCCESS"), output.stdout);
            assertFalse(output.stderr.contains("TIMEOUT"), output.stderr);
            String record = Files.readString(
                    AgentCoordination.runDirectory(project, "transient-step").resolve(AgentCoordination.RECORD_FILE)
            );
            assertFalse(record.contains("TIMEOUT"), record);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void runTimeoutIsMeasuredFromRunStart() throws Exception {
        AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("run-budget", "agent", null, null, null, null, "execute-step"),
                Instant.now().minusSeconds(30),
                null,
                false
        );
        Path sessionFile = AgentCoordination.runDirectory(project, "run-budget")
                .resolve("session").resolve("cli-session.json");
        try (FakeSession ignored = FakeSession.start(project, "cli-session", sessionFile)) {
            long started = System.nanoTime();
            Output output = run(
                    new String[]{
                            "execute-step", project.toString(), "--run-id=run-budget",
                            "--text=Given stay", "--run-timeout=5"
                    },
                    launch -> {
                        throw new AssertionError("execute-step must not start a session");
                    }
            );
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(1, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stderr.contains("TIMEOUT"), output.stderr);
            assertTrue(output.stderr.contains("still running"), output.stderr);
            assertTrue(output.stderr.contains("stop --force"), output.stderr);
            assertTrue(elapsedMs < 3_000, "run budget was not already spent: " + elapsedMs + "ms");
            String record = Files.readString(
                    AgentCoordination.runDirectory(project, "run-budget").resolve(AgentCoordination.RECORD_FILE)
            );
            assertTrue(record.contains("TIMEOUT"), record);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void stopForceKillsTheProcessTreeAndRecordsStopped() throws Exception {
        Path childFile = project.resolve("child.pid");
        Process parent = new ProcessBuilder(
                "bash", "-c", "sleep 120 & echo $! > '" + childFile.toAbsolutePath() + "'; wait"
        ).start();
        try {
            for (int attempt = 0; attempt < 50 && !Files.isRegularFile(childFile); attempt++) {
                Thread.sleep(20);
            }
            assertTrue(Files.isRegularFile(childFile), "child pid was not published");
            long child = Long.parseLong(Files.readString(childFile).trim());
            AgentCoordination.begin(
                    project,
                    AgentCoordination.Request.of("force-run", "agent", null, null, null, null, "stop"),
                    Instant.now(),
                    null,
                    false
            );
            Path sessionFile = AgentCoordination.runDirectory(project, "force-run")
                    .resolve("session").resolve("cli-session.json");
            try (FakeSession session = FakeSession.start(project, "cli-session", sessionFile)) {
                session.setPid(parent.pid());
                Output stop = run(
                        new String[]{"stop", project.toString(), "--run-id=force-run", "--force"},
                        launch -> {
                            throw new AssertionError("stop must not start a session");
                        }
                );
                assertEquals(0, stop.exitCode, stop.stderr + stop.stdout);
                assertTrue(stop.stdout.contains("killed"), stop.stdout);
                parent.waitFor(3, TimeUnit.SECONDS);
                assertFalse(parent.isAlive());
                assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
                String record = Files.readString(
                        AgentCoordination.runDirectory(project, "force-run").resolve(AgentCoordination.RECORD_FILE)
                );
                assertTrue(record.contains("STOPPED"), record);
            }
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void stopAfterIsPostedOnTheLiveCommand() throws Exception {
        try (FakeSession session = FakeSession.start(project)) {
            AtomicReference<String> hit = new AtomicReference<>();
            session.onCommand = hit::set;
            Output output = run(
                    new String[]{
                            "execute-step", project.toString(), "--text=Given stay",
                            "--stop-after=Pend case NEW UI", "--ack-only"
                    },
                    launch -> {
                        throw new AssertionError("execute-step must not start a session");
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            assertTrue(hit.get().contains("\"stopAfter\":\"Pend case NEW UI\""));
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void openScenarioPostsTheSharedCommandAndDoesNotStartASession() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicReference<String> hit = new AtomicReference<>();
            ignored.onCommand = hit::set;
            Output output = run(
                    new String[]{
                            "open-scenario", project.toString(),
                            "--feature=shop.feature", "--name=Buy", "--example=2.2", "--ack-only"
                    },
                    launch -> {
                        throw new AssertionError("open-scenario must not start a session");
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stdout.contains("ACK "));
            assertTrue(hit.get().contains("\"op\":\"open-scenario\""));
            assertTrue(hit.get().contains("shop.feature"));
            assertTrue(hit.get().contains("2.2"));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void eachSessionCommandPostsToTheOpenSessionAndDoesNotStartOne() throws Exception {
        String[][] commands = {
                {"example", "--example=2.2"},
                {"play"},
                {"from-here", "--from-here=4"},
                {"pause"},
                {"insert-step", "--text=When stay"},
                {"update-step", "--text=When stay"},
                {"diagnostic-run", "--run=run-42"},
                {"save"},
                {"refresh"},
                {"session-sync"},
                {"worker-start"},
                {"worker-restart"},
                {"worker-stop"}
        };
        try (FakeSession session = FakeSession.start(project)) {
            for (String[] command : commands) {
                AtomicReference<String> hit = new AtomicReference<>();
                session.onCommand = hit::set;
                List<String> args = new ArrayList<>();
                args.add(command[0]);
                args.add(project.toString());
                for (int index = 1; index < command.length; index++) args.add(command[index]);
                args.add("--ack-only");
                Output output = run(
                        args.toArray(String[]::new),
                        launch -> {
                            throw new AssertionError(command[0] + " must not start a session");
                        }
                );
                assertEquals(0, output.exitCode, command[0] + " " + output.stderr + output.stdout);
                assertTrue(output.stdout.contains("ACK "), command[0]);
                assertTrue(hit.get() != null && hit.get().contains("\"op\":\"" + command[0] + "\""), hit.get());
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void executeStepAckOnlyExitsWithoutWaitingForDone() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            Output output = run(
                    new String[]{"execute-step", project.toString(), "--text=Given stay", "--ack-only"},
                    launch -> {
                        throw new AssertionError("execute-step must not start a session process");
                    }
            );
            assertEquals(0, output.exitCode);
            assertTrue(output.stdout.contains("ACK "));
            assertFalse(output.stdout.contains("DONE "));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void twoHeadlessIsolatesDoNotShareASessionFile() throws Exception {
        List<Path> files = new ArrayList<>();
        List<FakeSession> sessions = new ArrayList<>();
        List<Process> children = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                Output output = run(
                        new String[]{"isolate", project.toString(), "--run-id=run-" + index, "--agent=agent-" + index},
                        launch -> {
                            files.add(launch.sessionFile());
                            assertTrue(launch.sessionFile().toString().contains("run-" + (files.size() - 1)));
                            FakeSession session = FakeSession.start(
                                    launch.project(), "cli-session", launch.sessionFile()
                            );
                            sessions.add(session);
                            Process process = startLongLivedChild();
                            children.add(process);
                            return process;
                        }
                );
                assertEquals(0, output.exitCode, output.stderr + output.stdout);
                assertTrue(output.stdout.contains("ACK SESSION pid="));
                assertFalse(output.stdout.contains("already-running"));
            }
            assertEquals(2, files.size());
            assertFalse(files.get(0).equals(files.get(1)));
        } finally {
            for (Process process : children) process.destroyForcibly();
            for (FakeSession session : sessions) session.close();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void runIdDoesNotFallThroughToAnOpenWindow() throws Exception {
        try (FakeSession ignored = FakeSession.start(project)) {
            AtomicBoolean started = new AtomicBoolean();
            Output output = run(
                    new String[]{"play", project.toString(), "--run-id=run-private", "--ack-only"},
                    launch -> {
                        started.set(true);
                        throw new AssertionError("a named run must not start or drive the open window");
                    }
            );
            assertEquals(1, output.exitCode, output.stderr + output.stdout);
            assertFalse(started.get());
            assertTrue(output.stderr.contains("No healthy Workbench session"));
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void openingAWindowOnARunIdLoadsTheRecordAndDoesNotStartATest() throws Exception {
        Path run = writeRun("alpha", "ALPHA-RECORD");
        Map<String, String> before = snapshot(run);
        AtomicBoolean sessionStarted = new AtomicBoolean();
        AtomicReference<FakeSession> ui = new AtomicReference<>();
        AtomicReference<Process> windowProcess = new AtomicReference<>();
        AtomicReference<String> posted = new AtomicReference<>();
        try {
            Output output = run(
                    new String[]{"open-window", project.toString(), "--run-id=alpha", "--agent=agent-1"},
                    launch -> {
                        sessionStarted.set(true);
                        throw new AssertionError("open-window must not start a test");
                    },
                    (root, runId, agentId) -> {
                        assertEquals("alpha", runId);
                        assertEquals("agent-1", agentId);
                        FakeSession session = FakeSession.start(
                                root, "ui-attach", PickleballLocalLayout.attachFile(root));
                        session.onCommand = posted::set;
                        ui.set(session);
                        Process process = startLongLivedChild();
                        windowProcess.set(process);
                        return process;
                    }
            );
            assertEquals(0, output.exitCode, output.stderr + output.stdout);
            assertTrue(output.stdout.contains("ALPHA-RECORD"), output.stdout);
            assertTrue(output.stdout.contains("does not start a test"), output.stdout);
            assertTrue(output.stdout.contains("ACK WINDOW opened"), output.stdout);
            assertFalse(sessionStarted.get());
            assertFalse(Files.exists(run.resolve("session").resolve("cli-session.json")));
            assertEquals(before, snapshot(run));
            assertTrue(posted.get() != null && posted.get().contains("\"op\":\"show-run\""), String.valueOf(posted.get()));
            assertEquals("alpha", tools.dscode.control.protocol.WindowDriver.read(project).liveRunId());
            assertEquals("agent-1", tools.dscode.control.protocol.WindowDriver.read(project).driverAgent());
        } finally {
            if (ui.get() != null) ui.get().close();
            if (windowProcess.get() != null) windowProcess.get().destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void switchingRunIdsShowsTheOtherRunAndDoesNotWriteTheFirst() throws Exception {
        Path first = writeRun("alpha", "ALPHA-RECORD");
        writeRun("beta", "BETA-RECORD");
        Map<String, String> before = snapshot(first);
        AtomicReference<FakeSession> ui = new AtomicReference<>();
        AtomicReference<Process> windowProcess = new AtomicReference<>();
        AtomicReference<String> posted = new AtomicReference<>();
        try {
            Output opened = run(
                    new String[]{"open-window", project.toString(), "--run-id=alpha", "--agent=agent-1"},
                    launch -> {
                        throw new AssertionError("open-window must not start a test");
                    },
                    (root, runId, agentId) -> {
                        FakeSession session = FakeSession.start(
                                root, "ui-attach", PickleballLocalLayout.attachFile(root));
                        session.onCommand = posted::set;
                        ui.set(session);
                        Process process = startLongLivedChild();
                        windowProcess.set(process);
                        return process;
                    }
            );
            assertEquals(0, opened.exitCode, opened.stderr + opened.stdout);
            before = snapshot(first);
            posted.set(null);
            Output shown = run(
                    new String[]{"show-run", project.toString(), "--run-id=beta", "--agent=agent-1"},
                    launch -> {
                        throw new AssertionError("show-run must not start a test");
                    }
            );
            assertEquals(0, shown.exitCode, shown.stderr + shown.stdout);
            assertTrue(shown.stdout.contains("BETA-RECORD"), shown.stdout);
            assertTrue(shown.stdout.contains("beta.log"), shown.stdout);
            assertTrue(shown.stdout.contains("does not start a test"), shown.stdout);
            assertEquals(before, snapshot(first));
            assertEquals("alpha", tools.dscode.control.protocol.WindowDriver.read(project).liveRunId());
            assertEquals("beta", tools.dscode.control.protocol.WindowDriver.read(project).viewedRunId());
            assertEquals("agent-1", tools.dscode.control.protocol.WindowDriver.read(project).driverAgent());
            assertTrue(posted.get() != null && posted.get().contains("beta"), String.valueOf(posted.get()));
        } finally {
            if (ui.get() != null) ui.get().close();
            if (windowProcess.get() != null) windowProcess.get().destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void closingTheWindowLeavesTheRunDirectoryAndTheShortLog() throws Exception {
        Path run = writeRun("alpha", "ALPHA-RECORD");
        Path shortLog = project.resolve(".pickleball").resolve("agent-log");
        Files.createDirectories(shortLog.getParent());
        Files.writeString(shortLog, "keep-this-short-log\n");
        Process headless = startLongLivedChild();
        AtomicReference<FakeSession> ui = new AtomicReference<>();
        AtomicReference<Process> windowProcess = new AtomicReference<>();
        try (FakeSession cli = FakeSession.start(project)) {
            cli.setPid(headless.pid());
            Output opened = run(
                    new String[]{"open-window", project.toString(), "--run-id=alpha", "--agent=agent-1"},
                    launch -> {
                        throw new AssertionError("open-window must not start a test");
                    },
                    (root, runId, agentId) -> {
                        FakeSession session = FakeSession.start(
                                root, "ui-attach", PickleballLocalLayout.attachFile(root));
                        ui.set(session);
                        Process process = startLongLivedChild();
                        windowProcess.set(process);
                        return process;
                    }
            );
            assertEquals(0, opened.exitCode, opened.stderr + opened.stdout);
            Map<String, String> runBefore = snapshot(run);
            byte[] logBefore = Files.readAllBytes(shortLog);
            Output closed = run(
                    new String[]{"close-window", project.toString()},
                    launch -> {
                        throw new AssertionError("close-window must not start a session");
                    }
            );
            assertEquals(0, closed.exitCode, closed.stderr + closed.stdout);
            assertTrue(closed.stdout.contains("ACK WINDOW closed"), closed.stdout);
            assertTrue(closed.stdout.contains("short log"), closed.stdout);
            assertEquals(runBefore, snapshot(run));
            assertEquals(new String(logBefore, StandardCharsets.UTF_8), Files.readString(shortLog));
            assertTrue(headless.isAlive());
            assertTrue(Files.isRegularFile(PickleballLocalLayout.cliSessionState(project)));
            assertFalse(tools.dscode.control.protocol.WindowDriver.read(project).open());
        } finally {
            headless.destroyForcibly();
            if (ui.get() != null) ui.get().close();
            if (windowProcess.get() != null) windowProcess.get().destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void aSecondAgentDoesNotBecomeASecondDriverOfTheLiveSession() throws Exception {
        writeRun("alpha", "ALPHA-RECORD");
        writeRun("beta", "BETA-RECORD");
        AtomicReference<FakeSession> ui = new AtomicReference<>();
        AtomicReference<Process> windowProcess = new AtomicReference<>();
        List<String> posted = new ArrayList<>();
        try {
            Output opened = run(
                    new String[]{"open-window", project.toString(), "--run-id=alpha", "--agent=agent-1"},
                    launch -> {
                        throw new AssertionError("open-window must not start a test");
                    },
                    (root, runId, agentId) -> {
                        FakeSession session = FakeSession.start(
                                root, "ui-attach", PickleballLocalLayout.attachFile(root));
                        session.onCommand = posted::add;
                        ui.set(session);
                        Process process = startLongLivedChild();
                        windowProcess.set(process);
                        return process;
                    }
            );
            assertEquals(0, opened.exitCode, opened.stderr + opened.stdout);
            posted.clear();
            Output other = run(
                    new String[]{"open-window", project.toString(), "--run-id=beta", "--agent=agent-2"},
                    launch -> {
                        throw new AssertionError("a second window must not be started");
                    }
            );
            assertEquals(0, other.exitCode, other.stderr + other.stdout);
            assertTrue(other.stdout.contains("BETA-RECORD"), other.stdout);
            assertTrue(other.stdout.contains("does not become a second driver"), other.stdout);
            assertEquals("alpha", tools.dscode.control.protocol.WindowDriver.read(project).liveRunId());
            assertEquals("agent-1", tools.dscode.control.protocol.WindowDriver.read(project).driverAgent());
            posted.clear();
            Output play = run(
                    new String[]{"play", project.toString(), "--agent=agent-2", "--ack-only"},
                    launch -> {
                        throw new AssertionError("a second agent must not start a session");
                    }
            );
            assertEquals(1, play.exitCode, play.stderr + play.stdout);
            assertTrue(play.stderr.contains("does not become a second driver"), play.stderr);
            assertTrue(posted.isEmpty(), posted.toString());
            assertEquals("agent-1", tools.dscode.control.protocol.WindowDriver.read(project).driverAgent());
        } finally {
            if (ui.get() != null) ui.get().close();
            if (windowProcess.get() != null) windowProcess.get().destroyForcibly();
        }
    }

    private Path writeRun(String id, String marker) throws IOException {
        Path run = project.resolve(".pickleball").resolve("runs").resolve(id);
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
        if (!Files.exists(root)) return files;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path path : paths) {
                files.put(root.relativize(path).toString(), HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private static Output run(
            String[] args,
            WorkbenchSessionCommands.DetachedStarter starter,
            WorkbenchSessionCommands.UiStarter uiStarter
    ) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                starter,
                uiStarter
        );
        return new Output(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Output run(String[] args, WorkbenchSessionCommands.DetachedStarter starter) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                starter
        );
        return new Output(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }

    /**
     * Placeholder controller process. Unix {@code sleep} is not on Windows PATH;
     * session-start must return without waiting for this child to exit.
     */
    private static Process startLongLivedChild() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", "ping", "-n", "31", "127.0.0.1").start();
        }
        return new ProcessBuilder("sleep", "30").start();
    }

    private static Process exitedProcess() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("true").start();
        process.waitFor(2, TimeUnit.SECONDS);
        return process;
    }

    private static final class FakeSession implements AutoCloseable {
        private final HttpServer http;
        private final Path stateFile;
        private final String url;
        private Process child;
        private volatile java.util.function.Consumer<String> onCommand;
        private volatile long pid = ProcessHandle.current().pid();
        private volatile String eventsJson = "{\"events\":[]}";
        private volatile String healthJson = "{\"status\":\"ok\"}";
        private volatile int failGets;
        private volatile boolean succeedAfter;

        private FakeSession(HttpServer http, Path stateFile, String url) {
            this.http = http;
            this.stateFile = stateFile;
            this.url = url;
        }

        static FakeSession start(Path project) throws IOException {
            return start(project, "cli-session", PickleballLocalLayout.cliSessionState(project));
        }

        static FakeSession start(Path project, String mode, Path stateFile) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeSession[] box = new FakeSession[1];
            http.createContext("/health", exchange -> {
                FakeSession current = box[0];
                String body = current == null || current.healthJson == null ? "{\"status\":\"ok\"}" : current.healthJson;
                write(exchange, 200, body);
            });
            http.createContext("/tools/workbench_events", exchange -> {
                FakeSession current = box[0];
                String body = current == null || current.eventsJson == null ? "{\"events\":[]}" : current.eventsJson;
                write(exchange, 200, body);
            });
            http.createContext("/commands", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                FakeSession session = box[0];
                if (session != null && session.onCommand != null) {
                    session.onCommand.accept(body + " " + session.url);
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    write(exchange, 200, "{\"ack\":true,\"id\":\"step-held\",\"status\":\"QUEUED\"}");
                    return;
                }
                FakeSession current = box[0];
                if (current != null && current.failGets > 0) {
                    current.failGets--;
                    write(exchange, 503, "{\"error\":\"unavailable\"}");
                    return;
                }
                if (current != null && current.succeedAfter) {
                    write(exchange, 200, "{\"id\":\"step-held\",\"status\":\"SUCCESS\"}");
                    return;
                }
                write(exchange, 200, "{\"id\":\"step-held\",\"status\":\"STILL_WORKING\"}");
            });
            http.start();
            Files.createDirectories(stateFile.getParent());
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            FakeSession session = new FakeSession(http, stateFile, url);
            box[0] = session;
            session.writeState(project, mode);
            return session;
        }

        void setPid(long pid) {
            this.pid = pid;
            try {
                String text = Files.readString(stateFile).replaceAll("\"pid\"\\s*:\\s*\\d+", "\"pid\": " + pid);
                Files.writeString(stateFile, text);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        boolean pidAlive() {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        private void writeState(Path project, String mode) throws IOException {
            Files.writeString(stateFile, """
                    {
                      "url": "%s",
                      "token": "test-token",
                      "pid": %d,
                      "project": "%s",
                      "mode": "%s"
                    }
                    """.formatted(url, pid, jsonEscape(project.toString()), mode));
        }

        @Override
        public void close() {
            http.stop(0);
            if (child != null) child.destroyForcibly();
            try {
                Files.deleteIfExists(stateFile);
            } catch (IOException ignored) {
            }
        }

        private static String jsonEscape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
