package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
