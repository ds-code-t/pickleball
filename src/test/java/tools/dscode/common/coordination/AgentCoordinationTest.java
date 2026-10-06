package tools.dscode.common.coordination;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeOptions;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.common.reporting.diagnostic.DiagnosticRuntime;
import tools.dscode.common.reporting.diagnostic.ReportRetentionPolicy;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.simplehtml.SimpleHtmlReportConverter;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.testengine.PKB_props;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCoordinationTest {
    @TempDir
    Path project;

    @AfterEach
    void clearCoordination() {
        AgentCoordination.clearCurrent();
        clearLaunchSignals();
        ReportRetentionPolicy.clearThreadOverride();
        DiagnosticRuntime.DIAGNOSTIC_MODE = false;
    }

    @Test
    void twoRunsGetPrivateDirectoriesAndDoNotWriteEachOthersFiles() throws Exception {
        AgentCoordination.Run first = AgentCoordination.begin(project, request("run-a", "agent-a"));
        AgentCoordination.Run second = AgentCoordination.begin(project, request("run-b", "agent-b"));
        assertNotEquals(first.dataDirectory(), second.dataDirectory());
        assertNotEquals(first.browserProfileDirectory(), second.browserProfileDirectory());
        assertNotEquals(first.sessionStateFile(), second.sessionStateFile());

        Files.writeString(first.reportsDirectory().resolve("out.txt"), "alpha");
        Files.writeString(first.scratchDirectory().resolve("note.txt"), "scratch-a");
        Files.writeString(first.browserProfileDirectory().resolve("profile.txt"), "profile-a");
        Files.writeString(second.reportsDirectory().resolve("out.txt"), "beta");
        Files.writeString(second.sessionStateFile(), "session-b");

        assertEquals("alpha", Files.readString(first.reportsDirectory().resolve("out.txt")));
        assertEquals("beta", Files.readString(second.reportsDirectory().resolve("out.txt")));
        assertFalse(Files.exists(second.scratchDirectory().resolve("note.txt")));
        assertFalse(Files.exists(first.sessionStateFile()));
        assertEquals("session-b", Files.readString(second.sessionStateFile()));
        assertEquals("profile-a", Files.readString(first.browserProfileDirectory().resolve("profile.txt")));
        assertTrue(Files.isRegularFile(first.recordFile()));
        assertTrue(Files.isRegularFile(second.recordFile()));
    }

    @Test
    void omittedRunIdIsGeneratedAndAPassedInIdIsKept() {
        StringBuilder printed = new StringBuilder();
        AgentCoordination.Run generated = AgentCoordination.begin(
                project,
                AgentCoordination.Request.of(null, null, null, null, null, null, "discover"),
                Instant.parse("2026-10-01T15:04:05Z"),
                printed
        );
        assertFalse(generated.runId().isBlank());
        assertTrue(generated.runId().startsWith("20261001-150405000Z-"));
        assertTrue(generated.agentId().startsWith("agent-"));
        assertTrue(printed.toString().contains("agent-id=" + generated.agentId()));
        assertTrue(printed.toString().contains("run-id=" + generated.runId()));

        AgentCoordination.Run kept = AgentCoordination.begin(
                project,
                AgentCoordination.Request.of("kept-run-9", "agent-kept", "wave", 3, "Dan", "check the row", "confirm"),
                Instant.parse("2026-10-01T15:05:00Z"),
                new StringBuilder()
        );
        assertEquals("kept-run-9", kept.runId());
        assertEquals("agent-kept", kept.agentId());
        assertEquals("wave", kept.group());
        assertEquals(3, kept.sequence());
        assertEquals("Dan", kept.who());
        assertEquals("check the row", kept.why());
        assertTrue(PKB_props.isRunMetadataKey("pkb_run_id"));
        assertTrue(PKB_props.isRunMetadataKey("pkb_agent_id"));
        assertFalse(PKB_props.isRunVariableKey("pkb_run_id"));
    }

    @Test
    void shortLogAppendsStartAndStopWithTheRunPath() throws Exception {
        AgentCoordination.Run run = AgentCoordination.begin(project, request("run-log", "agent-log"));
        AgentCoordination.finish(project, run.runId(), "the empty row fails", "PASSED");
        List<String> lines = AgentCoordination.readLog(project);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\tstart\t"));
        assertTrue(lines.get(1).contains("\tstop\t"));
        assertTrue(lines.get(0).contains(run.recordFile().toString()));
        assertTrue(lines.get(1).contains(run.recordFile().toString()));
        assertTrue(lines.get(1).contains("the empty row fails"));
        String record = Files.readString(run.recordFile());
        assertTrue(record.contains("\"learned\" : \"the empty row fails\"") || record.contains("\"learned\":\"the empty row fails\""));
        assertTrue(record.contains("PASSED"));
        assertFalse(record.contains("\"stoppedAt\" : null") && record.contains("\"status\" : \"RUNNING\""));
    }

    @Test
    void linesOlderThanThreeDaysAreRemovedAndNewerLinesStay() throws Exception {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        Instant exactly = now.minus(Duration.ofDays(3));
        Instant older = exactly.minusMillis(1);
        AgentCoordination.appendLog(project, older, "agent-a", "old-run", "note", "gone", null);
        AgentCoordination.appendLog(project, exactly, "agent-a", "edge-run", "note", "stay-edge", null);
        AgentCoordination.appendLog(project, now.minus(Duration.ofHours(1)), "agent-a", "recent-run", "note", "stay-recent", null);
        AgentCoordination.appendLog(project, now, "agent-a", "fresh-run", "note", "stay-now", null);

        String log = Files.readString(AgentCoordination.logFile(project), StandardCharsets.UTF_8);
        assertFalse(log.contains("old-run"));
        assertFalse(log.contains("gone"));
        assertTrue(log.contains("edge-run"));
        assertTrue(log.contains("stay-edge"));
        assertTrue(log.contains("recent-run"));
        assertTrue(log.contains("fresh-run"));
    }

    @Test
    void twoAppendsDoNotNeedALockAndDoNotCorruptLines() throws Exception {
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> left = pool.submit(() -> appendMany("agent-left", perThread, ready, go));
            Future<?> right = pool.submit(() -> appendMany("agent-right", perThread, ready, go));
            ready.await();
            go.countDown();
            left.get();
            right.get();
        } finally {
            pool.shutdownNow();
        }
        List<String> lines = AgentCoordination.readLog(project);
        assertEquals(perThread * 2, lines.size());
        int leftCount = 0;
        int rightCount = 0;
        for (String line : lines) {
            String[] fields = line.split("\t", -1);
            assertEquals(6, fields.length, line);
            assertFalse(line.contains("\n"));
            if ("agent-left".equals(fields[1])) leftCount++;
            if ("agent-right".equals(fields[1])) rightCount++;
        }
        assertEquals(perThread, leftCount);
        assertEquals(perThread, rightCount);
    }

    @Test
    void inboxIsAddressedAndTakingDeletesTheNote() throws Exception {
        AgentCoordination.writeInbox(project, "agent-a", "agent-writer", "for a only", null);
        AgentCoordination.writeInbox(project, "agent-b", "agent-writer", "for b only", null);
        Path any = AgentCoordination.writeInbox(project, "any", "agent-writer", "for anyone", null);

        List<AgentCoordination.InboxNote> forA = AgentCoordination.listInbox(project, "agent-a");
        List<AgentCoordination.InboxNote> forB = AgentCoordination.listInbox(project, "agent-b");
        assertEquals(1, forA.size());
        assertEquals("for a only", forA.getFirst().message());
        assertEquals(1, forB.size());
        assertEquals("for b only", forB.getFirst().message());
        assertTrue(AgentCoordination.listInbox(project, "agent-c").isEmpty());
        assertTrue(any.toString().contains("posts"));
        assertTrue(Files.isRegularFile(any));

        List<AgentCoordination.InboxNote> taken = AgentCoordination.takeInbox(project, "agent-a");
        assertEquals(1, taken.size());
        assertEquals("for a only", taken.getFirst().message());
        assertFalse(Files.exists(taken.getFirst().file()));
        assertTrue(Files.isRegularFile(any));
        assertTrue(AgentCoordination.listInbox(project, "agent-a").isEmpty());
        assertEquals(1, AgentCoordination.listInbox(project, "agent-b").size());
        AgentCoordination.takeInbox(project, "agent-b");
        assertTrue(AgentCoordination.listInbox(project, "agent-b").isEmpty());
        assertTrue(Files.isRegularFile(any));
        assertEquals(1, AgentCoordination.listPosts(project, Instant.now()).size());
    }

    @Test
    void adoptingAnExistingRunDoesNotWriteASecondStartLine() throws Exception {
        AgentCoordination.begin(project, request("run-adopt", "agent-adopt"));
        AgentCoordination.begin(project, request("run-adopt", "agent-adopt"));
        long starts = AgentCoordination.readLog(project).stream().filter(line -> line.contains("\tstart\t")).count();
        assertEquals(1, starts);
    }

    @Test
    void unsafeIdsAndTheSharedInboxNameAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AgentCoordination.begin(project, request("../escape", "agent-a")));
        assertThrows(IllegalArgumentException.class, () -> AgentCoordination.begin(project, request("run-ok", "any")));
    }

    @Test
    void localBrowserProfileUsesTheCurrentRunAndDoesNotReplaceAnExistingDirectory() throws Exception {
        ChromeOptions idle = new ChromeOptions();
        DriverConstruction.applyRunBrowserProfile(idle);
        assertFalse(argumentText(idle).contains("user-data-dir"));

        AgentCoordination.Run run = AgentCoordination.begin(project, request("run-browser", "agent-browser"));
        ChromeOptions local = new ChromeOptions();
        DriverConstruction.applyRunBrowserProfile(local);
        Path workerProfile = AgentCoordination.currentBrowserProfile();
        assertTrue(workerProfile.startsWith(run.browserProfileDirectory()));
        assertNotEquals(run.browserProfileDirectory(), workerProfile);
        assertTrue(argumentText(local).contains("--user-data-dir=" + workerProfile.toAbsolutePath().normalize()));

        ChromeOptions preset = new ChromeOptions();
        preset.addArguments("--user-data-dir=/tmp/already-mine");
        DriverConstruction.applyRunBrowserProfile(preset);
        String text = argumentText(preset);
        assertTrue(text.contains("--user-data-dir=/tmp/already-mine"));
        assertFalse(text.contains(run.browserProfileDirectory().toString()));
        assertEquals(1, text.split("user-data-dir", -1).length - 1);
    }

    @Test
    void normalTestCreatesNoRunDirectoryAndSetsNoBrowserProfile() {
        clearLaunchSignals();
        assertNull(AgentCoordination.openConsumerRun(project, new StringBuilder()));
        assertFalse(Files.exists(project.resolve(".pickleball/runs")));
        ChromeOptions chrome = new ChromeOptions();
        EdgeOptions edge = new EdgeOptions();
        DriverConstruction.applyRunBrowserProfile(chrome);
        DriverConstruction.applyRunBrowserProfile(edge);
        assertFalse(argumentText(chrome).contains("user-data-dir"));
        assertFalse(argumentText(edge).contains("user-data-dir"));
        assertEquals(Path.of("reports", "cucumber-report.html"), AgentCoordination.reportHtmlOrDefault());
        assertTrue(AgentCoordination.htmlEnabled("compositeReport"));
        assertTrue(AgentCoordination.htmlEnabled("scenarioReport"));
    }

    @Test
    void parallelLocalChromeWorkersGetSeparateProfilesAndRemoteStaysUntouched() throws Exception {
        AgentCoordination.Run run = AgentCoordination.begin(project, request("run-workers", "agent-workers"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<String> profiles = new ArrayList<>();
        try {
            Future<?> left = pool.submit(() -> captureProfile(profiles, ready, go));
            Future<?> right = pool.submit(() -> captureProfile(profiles, ready, go));
            ready.await();
            go.countDown();
            left.get();
            right.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, profiles.size());
        assertNotEquals(profiles.get(0), profiles.get(1));
        String root = run.browserProfileDirectory().toAbsolutePath().normalize().toString();
        for (String profile : profiles) {
            assertTrue(profile.contains("--user-data-dir=" + root));
            assertTrue(profile.contains("browser-profile"));
            assertFalse(profile.endsWith("browser-profile") || profile.endsWith("browser-profile\""));
        }

        ObjectNode empty = JsonNodeFactory.instance.objectNode();
        ChromeOptions remote = DriverConstruction.buildChromeOptions(empty, empty, false);
        assertFalse(argumentText(remote).contains("user-data-dir"));
    }

    @Test
    void agentAndWorkbenchRunsSuppressHtmlUntilAnExplicitTrue() throws Exception {
        ReportRetentionPolicy.configure("all");
        clearLaunchSignals();
        Path normalReport = project.resolve("reports").resolve("cucumber-report.html");
        recordScenario(normalReport, "Normal checkout");
        assertTrue(Files.isRegularFile(normalReport));
        assertTrue(htmlCount(project.resolve("reports")) >= 2);

        System.setProperty(PKB_props.PKB_RUN_ID, "run-html");
        System.setProperty(PKB_props.PKB_AGENT_ID, "agent-html");
        AgentCoordination.Run agent = AgentCoordination.openConsumerRun(project, new StringBuilder());
        assertTrue(Files.isDirectory(project.resolve(".pickleball").resolve("runs").resolve("run-html")));
        assertFalse(AgentCoordination.htmlEnabled("compositeReport"));
        assertFalse(AgentCoordination.htmlEnabled("scenarioReport"));
        DiagnosticRuntime.DIAGNOSTIC_MODE = true;
        Path agentReport = agent.reportsDirectory().resolve("cucumber-report.html");
        recordScenario(agentReport, "Agent checkout");
        assertFalse(Files.exists(agentReport));
        assertEquals(0, htmlCount(agent.reportsDirectory()));

        System.setProperty(PKB_props.PKB_COMPOSITE_REPORT, "true");
        System.setProperty(PKB_props.PKB_SCENARIO_REPORT, "true");
        assertTrue(AgentCoordination.htmlEnabled("compositeReport"));
        assertTrue(AgentCoordination.htmlEnabled("scenarioReport"));
        recordScenario(agentReport, "Agent checkout again");
        assertTrue(Files.isRegularFile(agentReport));
        assertTrue(htmlCount(agent.reportsDirectory()) >= 2);

        AgentCoordination.clearCurrent();
        clearLaunchSignals();
        System.setProperty(ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY, project.resolve("live").toString());
        AgentCoordination.Run workbench = AgentCoordination.openConsumerRun(project, new StringBuilder());
        assertFalse(AgentCoordination.htmlEnabled("compositeReport"));
        assertFalse(AgentCoordination.htmlEnabled("scenarioReport"));
        Path workbenchReport = workbench.reportsDirectory().resolve("cucumber-report.html");
        recordScenario(workbenchReport, "Workbench checkout");
        assertFalse(Files.exists(workbenchReport));
    }

    @Test
    void prunedRunKeepsAnIndexStubWhileItsDirectoryRemains() throws Exception {
        Path runDir = AgentCoordination.runDirectory(project, "old-kept");
        Files.createDirectories(runDir);
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        AgentCoordination.appendLog(
                project,
                now.minus(Duration.ofDays(3)).minusMillis(1),
                "agent-old",
                "old-kept",
                "note",
                "aged-out",
                runDir.resolve("record.json")
        );
        AgentCoordination.appendLog(project, now, "agent-new", "fresh-run", "note", "stay", null);
        String log = Files.readString(AgentCoordination.logFile(project), StandardCharsets.UTF_8);
        assertTrue(log.contains("old-kept"));
        assertTrue(log.contains("\tindex\t"));
        assertFalse(log.contains("aged-out"));
        assertTrue(log.contains("fresh-run"));
        assertTrue(Files.isDirectory(runDir));
    }

    @Test
    void presenceSweepDeletesOnlyAnExpiredRow() throws Exception {
        AgentCoordination.touchPresence(project, "agent-fresh", "run-fresh", Instant.now());
        AgentCoordination.touchPresence(project, "agent-stale", "run-stale", Instant.now().minus(Duration.ofMinutes(16)));
        AgentCoordination.BoardPost post = AgentCoordination.writePost(
                project, "agent-fresh", "all", null, "still here", null, Instant.now()
        );
        AgentCoordination.appendHistory(project, "2.1.14 files fix branch commit merge run-fresh");
        AgentCoordination.Run run = AgentCoordination.begin(project, request("run-stale", "agent-stale"));
        Path cookies = run.browserProfileDirectory().resolve("worker").resolve("Cookies");
        Files.createDirectories(cookies.getParent());
        Files.writeString(cookies, "keep");

        int removed = AgentCoordination.sweepPresence(project, Instant.now());
        assertEquals(1, removed);
        assertFalse(Files.exists(project.resolve(".pickleball/presence/agent-stale.json")));
        assertTrue(Files.isRegularFile(project.resolve(".pickleball/presence/agent-fresh.json")));
        assertTrue(Files.isRegularFile(post.file()));
        assertTrue(Files.isRegularFile(AgentCoordination.historyFile(project)));
        assertTrue(Files.isRegularFile(run.browserProfileDirectory().resolve("worker").resolve("Cookies")));
        assertEquals(0, AgentCoordination.sweepPresence(project, Instant.now()));
    }

    @Test
    void unexpiredPostSurvivesListAndTakeAndExpiredDeleteIsIdempotent() throws Exception {
        AgentCoordination.BoardPost live = AgentCoordination.writePost(
                project, "agent-writer", "all", "run-post", "I have the window", Duration.ofHours(1), Instant.now()
        );
        AgentCoordination.BoardPost expired = AgentCoordination.writePost(
                project, "agent-writer", "agent-other", null, "old hint", Duration.ofHours(1),
                Instant.parse("2020-01-01T00:00:00Z")
        );
        assertEquals(1, AgentCoordination.listPosts(project, Instant.now()).size());
        assertEquals("I have the window", AgentCoordination.listPosts(project, Instant.now()).getFirst().text());
        assertTrue(Files.isRegularFile(live.file()));
        AgentCoordination.takeInbox(project, "agent-other");
        AgentCoordination.listInbox(project, "agent-other");
        assertTrue(Files.isRegularFile(live.file()));
        assertTrue(Files.isRegularFile(expired.file()));

        assertEquals(1, AgentCoordination.sweepPosts(project, Instant.now()));
        assertFalse(Files.exists(expired.file()));
        assertTrue(Files.isRegularFile(live.file()));
        assertEquals(0, AgentCoordination.sweepPosts(project, Instant.now()));
        assertEquals(0, AgentCoordination.sweepPosts(project, Instant.now()));
    }

    @Test
    void historyTrimAtTenMegabytesKeepsTheTail() throws Exception {
        Path file = AgentCoordination.historyFile(project);
        Files.createDirectories(file.getParent());
        StringBuilder body = new StringBuilder();
        body.append("HEAD-MARKER 0 ").append("x".repeat(80)).append('\n');
        String filler = "FILL " + "y".repeat(200) + "\n";
        while (body.length() <= AgentCoordination.HISTORY_MAX_BYTES + filler.length()) {
            body.append(filler);
        }
        Files.writeString(file, body.toString());
        String tail = "TAIL-MARKER version files fix branch commit merge run-z";
        AgentCoordination.appendHistory(project, tail);
        assertTrue(Files.size(file) <= AgentCoordination.HISTORY_MAX_BYTES);
        String text = Files.readString(file);
        assertTrue(text.contains("TAIL-MARKER"));
        assertFalse(text.contains("HEAD-MARKER 0 "));
        assertTrue(text.stripTrailing().endsWith(tail));
    }

    @Test
    void browserProfileStaysWhileRunningWhileSessionIsLiveOrWhileTheWindowHoldsTheRun() throws Exception {
        AgentCoordination.Run running = AgentCoordination.begin(project, request("run-live", "agent-live"));
        Files.createDirectories(running.browserProfileDirectory().resolve("worker"));
        Files.writeString(running.browserProfileDirectory().resolve("worker").resolve("Cookies"), "live");
        AgentCoordination.sweepRuns(project, Instant.now());
        assertTrue(Files.isRegularFile(running.browserProfileDirectory().resolve("worker").resolve("Cookies")));

        AgentCoordination.Run session = AgentCoordination.begin(project, request("run-session", "agent-session"));
        Files.createDirectories(session.browserProfileDirectory().resolve("worker"));
        Files.writeString(session.browserProfileDirectory().resolve("worker").resolve("Cookies"), "session");
        AgentCoordination.finish(project, session.runId(), null, "STOPPED", Instant.now().minus(Duration.ofHours(2)), null);
        Files.writeString(session.sessionStateFile(), "{\"pid\":1}\n");
        AgentCoordination.sweepRuns(project, Instant.now());
        assertTrue(Files.isRegularFile(session.browserProfileDirectory().resolve("worker").resolve("Cookies")));

        AgentCoordination.Run shown = AgentCoordination.begin(project, request("run-window", "agent-window"));
        Files.createDirectories(shown.browserProfileDirectory().resolve("worker"));
        Files.writeString(shown.browserProfileDirectory().resolve("worker").resolve("Cookies"), "window");
        AgentCoordination.finish(project, shown.runId(), null, "STOPPED", Instant.now().minus(Duration.ofHours(2)), null);
        tools.dscode.control.protocol.WindowDriver.show(project, shown.runId(), "agent-window", true);
        AgentCoordination.sweepRuns(project, Instant.now());
        assertTrue(Files.isRegularFile(shown.browserProfileDirectory().resolve("worker").resolve("Cookies")));
        assertTrue(Files.isDirectory(shown.dataDirectory()));
    }

    @Test
    void browserProfileMayBeDeletedOnlyAfterStopWhenNothingHoldsTheRun() throws Exception {
        AgentCoordination.Run run = AgentCoordination.begin(project, request("run-done", "agent-done"));
        Path cookies = run.browserProfileDirectory().resolve("worker").resolve("Cookies");
        Files.createDirectories(cookies.getParent());
        Files.writeString(cookies, "done");
        Files.writeString(run.dataDirectory().resolve("record-extra.txt"), "dense");
        AgentCoordination.finish(project, run.runId(), null, "STOPPED", Instant.now().minus(Duration.ofHours(1)), null);
        assertTrue(Files.isDirectory(run.dataDirectory()));
        assertTrue(Files.isRegularFile(cookies));
        assertTrue(Files.isRegularFile(AgentCoordination.logFile(project)));

        AgentCoordination.RunSweep sweep = AgentCoordination.sweepRuns(project, Instant.now());
        assertEquals(1, sweep.profilesRemoved());
        assertFalse(Files.exists(cookies));
        assertTrue(Files.isRegularFile(run.recordFile()));
        assertTrue(Files.isDirectory(run.dataDirectory()));
        assertEquals(0, AgentCoordination.sweepRuns(project, Instant.now()).profilesRemoved());
    }

    @Test
    void finishDoesNotDeleteTheRunDirectory() throws Exception {
        AgentCoordination.Run run = AgentCoordination.begin(project, request("run-finish-keeps", "agent-keep"));
        Path cookies = run.browserProfileDirectory().resolve("worker").resolve("Cookies");
        Files.createDirectories(cookies.getParent());
        Files.writeString(cookies, "stay");
        Files.writeString(run.sessionStateFile(), "{\"pid\":1}\n");
        AgentCoordination.finish(project, run.runId(), "learned the row", "STOPPED");
        assertTrue(Files.isDirectory(run.dataDirectory()));
        assertTrue(Files.isRegularFile(cookies));
        assertTrue(Files.isRegularFile(run.sessionStateFile()));
        assertTrue(Files.isRegularFile(run.recordFile()));
        String record = Files.readString(run.recordFile());
        assertTrue(record.contains("stoppedAt"));
        assertFalse(record.contains("\"stoppedAt\" : null"));
        assertTrue(Files.readString(AgentCoordination.logFile(project)).contains("\tstop\t"));
    }

    @Test
    void expiredPayloadLeavesTheRecordSparseIndexAndRunProfile() throws Exception {
        AgentCoordination.Run failed = AgentCoordination.begin(project, request("run-failed", "agent-gc"));
        Files.writeString(failed.reportsDirectory().resolve("shot.png"), "png");
        Files.writeString(failed.diagnosticDirectory().resolve("run-index.json"), "{\"runId\":\"run-failed\"}");
        Files.writeString(failed.diagnosticDirectory().resolve("events.jsonl"), "dense");
        Files.writeString(failed.dataDirectory().resolve("pkb_run_profile"), "pkb_browser=CHROME_HEADLESS");
        AgentCoordination.finish(project, failed.runId(), null, "FAILED", Instant.parse("2020-01-01T00:00:00Z"), null);

        AgentCoordination.Run old = AgentCoordination.begin(project, request("run-old", "agent-gc"));
        Files.writeString(old.reportsDirectory().resolve("shot.png"), "png");
        Files.writeString(old.scratchDirectory().resolve("tmp.txt"), "tmp");
        Files.writeString(old.diagnosticDirectory().resolve("run-index.json"), "{\"runId\":\"run-old\"}");
        Files.writeString(old.diagnosticDirectory().resolve("summary.json"), "{}");
        Files.writeString(old.diagnosticDirectory().resolve("events.jsonl"), "dense-events");
        Files.writeString(old.dataDirectory().resolve("pkb_run_profile"), "pkb_browser=EDGE");
        AgentCoordination.finish(project, old.runId(), null, "STOPPED", Instant.parse("2020-01-02T00:00:00Z"), null);

        AgentCoordination.sweepRuns(project, Instant.parse("2026-10-05T00:00:00Z"));
        AgentCoordination.sweepRuns(project, Instant.parse("2026-10-05T00:00:00Z"));
        assertTrue(Files.isRegularFile(failed.reportsDirectory().resolve("shot.png")));
        assertTrue(Files.isRegularFile(failed.recordFile()));
        assertFalse(Files.exists(old.reportsDirectory().resolve("shot.png")));
        assertFalse(Files.exists(old.scratchDirectory().resolve("tmp.txt")));
        assertFalse(Files.exists(old.diagnosticDirectory().resolve("events.jsonl")));
        assertTrue(Files.isRegularFile(old.recordFile()));
        assertTrue(Files.isRegularFile(old.diagnosticDirectory().resolve("run-index.json")));
        assertTrue(Files.isRegularFile(old.diagnosticDirectory().resolve("summary.json")));
        assertTrue(Files.isRegularFile(old.dataDirectory().resolve("pkb_run_profile")));
        assertTrue(Files.isDirectory(old.dataDirectory()));
    }

    private void captureProfile(List<String> profiles, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            go.await();
            ChromeOptions options = new ChromeOptions();
            DriverConstruction.applyRunBrowserProfile(options);
            synchronized (profiles) {
                profiles.add(argumentText(options));
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void recordScenario(Path compositeReport, String title) {
        SimpleHtmlReportConverter.resetRun();
        SimpleHtmlReportConverter converter = new SimpleHtmlReportConverter(compositeReport);
        Entry scope = Entry.of(title);
        converter.onStart(scope, scope);
        converter.onStop(scope, scope);
        converter.close();
        SimpleHtmlReportConverter.writeFinalReport(compositeReport);
    }

    private static long htmlCount(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".html")).count();
        }
    }

    private static void clearLaunchSignals() {
        System.clearProperty(PKB_props.PKB_RUN_ID);
        System.clearProperty(PKB_props.PKB_AGENT_ID);
        System.clearProperty(PKB_props.PKB_COMPOSITE_REPORT);
        System.clearProperty(PKB_props.PKB_SCENARIO_REPORT);
        System.clearProperty(ControlProtocol.WORKBENCH_TEST_OUTPUT_ROOT_PROPERTY);
    }

    private void appendMany(String agent, int count, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            go.await();
            for (int index = 0; index < count; index++) {
                AgentCoordination.appendLog(
                        project,
                        Instant.now(),
                        agent,
                        "run-" + agent,
                        "note",
                        "line-" + index,
                        project.resolve("record.json")
                );
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static AgentCoordination.Request request(String runId, String agentId) {
        return AgentCoordination.Request.of(runId, agentId, null, null, null, null, "test");
    }

    private static String argumentText(org.openqa.selenium.chromium.ChromiumOptions<?> options) {
        String chrome = argumentsFrom(options, "goog:chromeOptions");
        String edge = argumentsFrom(options, "ms:edgeOptions");
        return chrome.isEmpty() ? edge : chrome;
    }

    private static String argumentsFrom(org.openqa.selenium.chromium.ChromiumOptions<?> options, String capability) {
        Object raw = options.getCapability(capability);
        if (!(raw instanceof java.util.Map<?, ?> map)) return "";
        Object args = map.get("args");
        if (!(args instanceof List<?> list)) return "";
        List<String> text = new ArrayList<>();
        for (Object arg : list) {
            if (arg != null) text.add(arg.toString());
        }
        return String.join(" ", text);
    }
}
