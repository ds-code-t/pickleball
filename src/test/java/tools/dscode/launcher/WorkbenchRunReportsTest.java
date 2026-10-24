package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchRunReportsTest {
    @TempDir
    Path tempDir;

    @Test
    void scenarioSummaryKeepsCoreFilesAndAddsFailureDetail() throws Exception {
        Path scenario = tempDir.resolve("scenarios").resolve("s1");
        Files.createDirectories(scenario);
        String events = """
                {"type":"scenario_start","identity":{"scenarioName":"Buy milk"}}
                {"type":"step","keyword":"Then","text":"the button shows","status":"FAILED","durationMillis":12,"errorMessage":"final XPath: //button[@id='go']\\nraw match count: 3\\nvisible count: 1\\nrejection: covered by overlay\\nexpected: <on> but was: <off>"}
                {"type":"failure","failures":[{"message":"final XPath: //button[@id='go']\\nraw match count: 3\\nvisible count: 1\\nrejection: covered by overlay\\nexpected: <on> but was: <off>","finalXPath":"//button[@id='go']","rawMatchCount":3,"visibleCount":1,"rejectionReasons":["covered by overlay"],"actual":"off","expected":"on"}]}
                {"type":"scenario_end","outcome":"FAILED"}
                """;
        Path eventsFile = scenario.resolve("events.jsonl");
        Files.writeString(eventsFile, events);
        String trace = """
                {"step":"Then the button shows","type":"log","text":"looking"}
                {"step":"Then the button shows","type":"log","text":"still"}
                {"step":"And the title","type":"log","text":"done"}
                """;
        Path traceFile = scenario.resolve("trace.jsonl");
        Files.writeString(traceFile, trace);
        byte[] eventsBefore = Files.readAllBytes(eventsFile);
        byte[] traceBefore = Files.readAllBytes(traceFile);
        Files.writeString(scenario.resolve("summary.json"), "{\"outcome\":\"FAILED\",\"core\":true}\n");

        Path written = WorkbenchRunReports.writeScenario(scenario);
        assertEquals(eventsBefore.length, Files.readAllBytes(eventsFile).length);
        assertEquals(new String(eventsBefore, StandardCharsets.UTF_8), Files.readString(eventsFile));
        assertEquals(new String(traceBefore, StandardCharsets.UTF_8), Files.readString(traceFile));
        assertTrue(Files.readString(scenario.resolve("summary.json")).contains("\"core\":true"));

        String summary = Files.readString(written);
        assertTrue(summary.contains("\"scenario\" : \"Buy milk\""), summary);
        assertTrue(summary.contains("\"step\" : \"Then the button shows\""), summary);
        assertTrue(summary.contains("\"status\" : \"FAILED\""), summary);
        assertTrue(summary.contains("\"assertionResult\" : \"actual=off expected=on\""), summary);
        assertTrue(summary.contains("\"durationMillis\" : 12"), summary);
        assertTrue(summary.contains("\"finalXPath\" : \"//button[@id='go']\""), summary);
        assertTrue(summary.contains("\"rawMatchCount\" : 3"), summary);
        assertTrue(summary.contains("\"visibleCount\" : 1"), summary);
        assertTrue(summary.contains("covered by overlay"), summary);

        String index = Files.readString(scenario.resolve("workbench").resolve("trace-index.json"));
        assertTrue(index.contains("\"step\" : \"Then the button shows\""), index);
        assertTrue(index.contains("\"startByte\" : 0"), index);
        assertTrue(index.contains("\"step\" : \"And the title\""), index);
        int titleStart = trace.indexOf("{\"step\":\"And the title\"");
        assertTrue(index.contains("\"startByte\" : " + titleStart), index);
        assertTrue(index.contains("\"endByte\" : " + trace.getBytes(StandardCharsets.UTF_8).length), index);
    }

    @Test
    void missingCoreSummaryIsWrittenWithTheSameFields() throws Exception {
        Path scenario = tempDir.resolve("plain");
        Files.createDirectories(scenario);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"scenario_start","identity":{"scenarioName":"Plain"}}
                {"type":"step","keyword":"Given","text":"one","status":"PASSED","durationMillis":4}
                {"type":"scenario_end","outcome":"PASSED"}
                """);
        WorkbenchRunReports.writeScenario(scenario);
        String summary = Files.readString(scenario.resolve("summary.json"));
        assertTrue(summary.contains("\"scenario\" : \"Plain\""), summary);
        assertTrue(summary.contains("\"assertionResult\" : \"passed\""), summary);
        assertTrue(summary.contains("\"durationMillis\" : 4"), summary);
        assertFalse(summary.contains("finalXPath"));
    }

    @Test
    void consoleIsSplitPerScenarioInsideTheRunFolder() throws Exception {
        Path run = tempDir.resolve("run-1");
        List<Path> written = WorkbenchRunReports.saveConsole(
                run,
                List.of("Pend case NEW UI", "Other"),
                """
                Scenario: Other
                other-line
                Scenario: Pend case NEW UI
                pending-line
                """
        );
        assertEquals(2, written.size());
        String pending = Files.readString(run.resolve("console").resolve("Pend_case_NEW_UI.txt"));
        String other = Files.readString(run.resolve("console").resolve("Other.txt"));
        assertTrue(pending.contains("pending-line"), pending);
        assertFalse(pending.contains("other-line"), pending);
        assertTrue(other.contains("other-line"), other);
        assertFalse(other.contains("pending-line"), other);
    }

    @Test
    void fullScanSummaryUsesTheRunIndexThenTheConsole() throws Exception {
        Path project = tempDir.resolve("Business Exception");
        Files.createDirectories(project);
        String runId = "run-full";
        Path index = AgentCoordination.runDirectory(project, runId)
                .resolve("diagnostic").resolve(runId).resolve("run-index.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, """
                {
                  "runId": "run-full",
                  "outcome": "PASSED",
                  "counts": { "total": 1, "passed": 1, "failed": 0 },
                  "steps": { "executed": 3, "passed": 3 }
                }
                """);
        ByteArrayOutputStream indexedOut = new ByteArrayOutputStream();
        int indexedSteps = WorkbenchRunReports.publish(
                project, runId, List.of(), "1 Scenarios\n9 Steps\n", "PASSED",
                new PrintStream(indexedOut, true, StandardCharsets.UTF_8)
        );
        assertEquals(3, indexedSteps);
        assertTrue(indexedOut.toString(StandardCharsets.UTF_8).contains("summary status=PASSED scenarios=1 steps=3"));

        ByteArrayOutputStream consoleOut = new ByteArrayOutputStream();
        int consoleSteps = WorkbenchRunReports.publish(
                project,
                "run-console",
                List.of(),
                """
                some log
                1 Scenarios (1 passed)
                2 Steps (2 passed)
                """,
                "PASSED",
                new PrintStream(consoleOut, true, StandardCharsets.UTF_8)
        );
        assertEquals(2, consoleSteps);
        assertTrue(consoleOut.toString(StandardCharsets.UTF_8).contains("summary status=PASSED scenarios=1 steps=2"));
    }

    @Test
    void prunedScenarioStillWritesRunSummaryAndNamedConsole() throws Exception {
        Path project = tempDir.resolve("Program Files");
        String runId = "check-1023";
        Path run = AgentCoordination.runDirectory(project, runId);
        Path passed = run.resolve("diagnostic").resolve(runId).resolve("scenarios").resolve("exec-pass");
        Path failed = run.resolve("diagnostic").resolve(runId).resolve("scenarios").resolve("exec-fail");
        Files.createDirectories(passed);
        Files.createDirectories(failed);
        String core = """
                {"outcome":"PASSED","durationMillis":1200,"identity":{"scenarioName":"Pend case NEW UI"},"steps":{"executed":2,"passed":2}}
                """;
        Path coreFile = passed.resolve("summary.json");
        Files.writeString(coreFile, core);
        byte[] coreBefore = Files.readAllBytes(coreFile);
        Files.writeString(failed.resolve("events.jsonl"), """
                {"type":"scenario_start","identity":{"scenarioName":"C:\\\\Users\\\\Dan She'll\\\\page"}}
                {"type":"step","keyword":"Then","text":"the file is saved","status":"FAILED","durationMillis":40,"errorMessage":"final XPath: //a\\nexpected: <yes> but was: <no>"}
                {"type":"scenario_end","outcome":"FAILED"}
                """);
        Files.writeString(failed.resolve("trace.jsonl"), """
                {"step":"Then the file is saved","type":"log","text":"C:/Program Files/Pickleball/body.xml"}
                """);
        String savedName = "C:\\Users\\Dan She'll\\page";
        String console = """
                Scenario: Pend case NEW UI # C:/Program Files/Pickleball/features/Page Response.feature:12
                INFO: saved C:\\Users\\Dan She'll\\reports\\body.html
                Scenario: C:\\Users\\Dan She'll\\page # C:/Program Files/Pickleball/features/save.feature:4
                expected: <yes> but was: <no>
                """;

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int steps = WorkbenchRunReports.publish(
                project,
                runId,
                List.of(hit("Pend case NEW UI"), hit(savedName)),
                console,
                "FAILED",
                new PrintStream(sink, true, StandardCharsets.UTF_8)
        );

        assertTrue(steps >= 1, "steps=" + steps);
        assertArrayEquals(coreBefore, Files.readAllBytes(coreFile));
        String passedSummary = Files.readString(passed.resolve("workbench").resolve("summary.json"));
        assertTrue(passedSummary.contains("\"scenario\" : \"Pend case NEW UI\""), passedSummary);
        assertTrue(passedSummary.contains("\"status\" : \"PASSED\""), passedSummary);
        assertTrue(passedSummary.contains("\"durationMillis\" : 1200"), passedSummary);

        Path runSummary = run.resolve("workbench").resolve("summary.json");
        assertTrue(Files.isRegularFile(runSummary), "missing " + runSummary);
        String summary = Files.readString(runSummary);
        assertTrue(Files.size(runSummary) > 0);
        assertTrue(summary.contains("Pend case NEW UI"), summary);
        assertTrue(summary.contains("1200"), summary);
        assertTrue(summary.contains("Then the file is saved"), summary);
        assertTrue(summary.contains("actual=no expected=yes"), summary);
        assertTrue(Files.isRegularFile(run.resolve("workbench").resolve("trace-index.json")));
        String traceIndex = Files.readString(run.resolve("workbench").resolve("trace-index.json"));
        assertTrue(traceIndex.contains("Then the file is saved"), traceIndex);

        Path consoleDir = run.resolve("console");
        String pending = Files.readString(consoleDir.resolve("Pend_case_NEW_UI.txt"));
        assertTrue(pending.contains("Page Response.feature"), pending);
        assertTrue(pending.contains("Dan She'll"), pending);
        assertFalse(pending.contains("but was"), pending);
        String saved = Files.readString(consoleDir.resolve("C_Users_Dan_She_ll_page.txt"));
        assertTrue(saved.contains("C:/Program Files/Pickleball/features/save.feature:4"), saved);
        assertTrue(saved.contains("but was"), saved);
        assertFalse(Files.exists(consoleDir.resolve("unassigned.txt")));
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("summary status=FAILED"));
    }

    @Test
    void markedLinesSplitWithoutScenarioHeadersIncludingParallelNames() throws Exception {
        Path project = tempDir.resolve("Program Files");
        String runId = "check-console-tap";
        String pending = "Pend";
        String pendingCase = "Pend case";
        String savedName = "C:\\Users\\Dan She'll\\page";
        String console = ""
                + mark(pending) + "alpha from Pend\n"
                + mark(pendingCase) + "beta from Pend case only\n"
                + mark(pending) + "gamma stays with Pend even if it mentions Pend case\n"
                + mark(savedName) + "delta saved under C:\\Users\\Dan She'll\\page\n";

        WorkbenchRunReports.publish(
                project,
                runId,
                List.of(hit(pending), hit(pendingCase), hit(savedName)),
                console,
                "PASSED",
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );

        Path consoleDir = AgentCoordination.runDirectory(project, runId).resolve("console");
        String pendFile = Files.readString(consoleDir.resolve("Pend.txt"));
        String caseFile = Files.readString(consoleDir.resolve("Pend_case.txt"));
        String savedFile = Files.readString(consoleDir.resolve("C_Users_Dan_She_ll_page.txt"));
        assertTrue(pendFile.contains("alpha from Pend"), pendFile);
        assertTrue(pendFile.contains("gamma stays with Pend"), pendFile);
        assertFalse(pendFile.contains("beta from Pend case only"), pendFile);
        assertTrue(caseFile.contains("beta from Pend case only"), caseFile);
        assertFalse(caseFile.contains("alpha from Pend"), caseFile);
        assertFalse(caseFile.contains("gamma stays with Pend"), caseFile);
        assertTrue(savedFile.contains("delta saved"), savedFile);
        assertTrue(savedFile.contains("C:\\Users\\Dan She'll\\page"), savedFile);
        assertFalse(pendFile.isBlank());
        assertFalse(caseFile.isBlank());
        assertFalse(savedFile.isBlank());
        Path unassigned = consoleDir.resolve("unassigned.txt");
        if (Files.exists(unassigned)) {
            assertTrue(Files.readString(unassigned).isBlank(), Files.readString(unassigned));
        }
    }

    @Test
    void consoleTapMarksOnlyTheWorkbenchThreadAndLeavesOtherPrintsAlone() throws Exception {
        String previous = System.getProperty("pkb_run_purpose");
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            System.setProperty("pkb_run_purpose", "workbench-discover");
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            PrintStream raw = new PrintStream(sink, true, StandardCharsets.UTF_8);
            PrintStream wrapped = WorkbenchConsoleTap.wrap(raw);
            wrapped.print("plain");
            assertEquals("plain", sink.toString(StandardCharsets.UTF_8));

            CountDownLatch started = new CountDownLatch(2);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> pend = pool.submit(() -> printAs(wrapped, "Pend", "P-line\n", started));
                Future<?> longer = pool.submit(() -> printAs(wrapped, "Pend case", "C-line\n", started));
                pend.get(10, TimeUnit.SECONDS);
                longer.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            String text = sink.toString(StandardCharsets.UTF_8);
            assertTrue(text.startsWith("plain"), text);
            assertTrue(text.contains(mark("Pend") + "P-line"), text);
            assertTrue(text.contains(mark("Pend case") + "C-line"), text);
            assertFalse(text.contains(mark("Pend") + "C-line"), text);
            assertTrue(WorkbenchConsoleTap.active());
            assertFalse(WorkbenchConsoleTap.active(null));

            System.clearProperty("pkb_run_purpose");
            String before = sink.toString(StandardCharsets.UTF_8);
            WorkbenchConsoleTap.enter("Pend case");
            wrapped.print("quiet");
            WorkbenchConsoleTap.leave();
            assertEquals(before + "quiet", sink.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
            if (previous == null) System.clearProperty("pkb_run_purpose");
            else System.setProperty("pkb_run_purpose", previous);
        }
    }

    private static void printAs(PrintStream wrapped, String name, String line, CountDownLatch started) {
        WorkbenchConsoleTap.enter(name);
        try {
            started.countDown();
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("threads did not start");
            }
            wrapped.print(line);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        } finally {
            WorkbenchConsoleTap.leave();
        }
    }

    private static String mark(String name) {
        return "# pkb-console " + URLEncoder.encode(name, StandardCharsets.UTF_8) + " ";
    }

    private static WorkbenchFeatureIndex.Hit hit(String scenarioName) {
        return new WorkbenchFeatureIndex.Hit(
                "C:/Program Files/Pickleball/features/save.feature",
                "Save",
                scenarioName,
                1,
                20,
                0,
                4,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
