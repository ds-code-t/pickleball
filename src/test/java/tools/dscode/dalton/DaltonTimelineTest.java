package tools.dscode.dalton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonTimelineTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void timelineIsOrderedAndCursorResumes() throws Exception {
        run("dalton", "history", "record", "sample-run", "--started=2020-01-01T00:00:00Z",
                "--status=PASSED", "--scenario=Pay", "--message=opened", "--link=Account#ada",
                "--project=" + project);
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 1,
                "the total is 10", 3, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        Capture all = run("dalton", "timeline", "--json", "--project=" + project);
        assertEquals(0, all.code, all.err);
        assertTrue(all.out.indexOf("\"command\"") < all.out.indexOf("\"events\""), all.out);
        JsonNode events = JSON.readTree(all.out).path("events");
        assertTrue(events.size() >= 2, all.out);
        Instant previous = null;
        for (JsonNode event : events) {
            Instant at = Instant.parse(event.path("at").asText());
            if (previous != null) assertFalse(at.isBefore(previous), events.toString());
            previous = at;
        }
        assertEquals("run", events.get(0).path("kind").asText());
        Capture window = run("dalton", "timeline", "--from=2020-01-01T00:00:00Z", "--to=2020-01-02T12:00:00Z",
                "--json", "--project=" + project);
        JsonNode windowEvents = JSON.readTree(window.out).path("events");
        assertTrue(windowEvents.size() >= 2, window.out);
        for (JsonNode event : windowEvents) {
            Instant at = Instant.parse(event.path("at").asText());
            assertFalse(at.isAfter(Instant.parse("2020-01-02T12:00:00Z")));
        }
        String cursor = JSON.readTree(all.out).path("cursor").asText();
        Capture resume = run("dalton", "timeline", "--cursor=" + cursor, "--json", "--limit=5", "--project=" + project);
        assertEquals(0, resume.code, resume.err);
        assertEquals(0, JSON.readTree(resume.out).path("count").asInt(), resume.out);

        Capture first = run("dalton", "timeline", "--limit=1", "--json", "--project=" + project);
        String firstCursor = JSON.readTree(first.out).path("cursor").asText();
        String firstId = JSON.readTree(first.out).path("events").get(0).path("id").asText();
        Capture rest = run("dalton", "timeline", "--cursor=" + firstCursor, "--json", "--project=" + project);
        for (JsonNode event : JSON.readTree(rest.out).path("events")) {
            assertFalse("run".equals(event.path("kind").asText()) && firstId.equals(event.path("id").asText()), rest.out);
        }
        Capture entity = run("dalton", "timeline", "--entity=Account#ada", "--json", "--project=" + project);
        assertTrue(entity.out.contains("\"entityId\":\"ada\""), entity.out);
    }

    @Test
    void beforeAndLatestPageTheNewestEvents() throws Exception {
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 1,
                "one", 3, "PASSED", "2020-01-01T00:00:00Z", "2020-01-01T00:00:01Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 2,
                "two", 4, "PASSED", "2020-01-02T00:00:00Z", "2020-01-02T00:00:01Z"));
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                "sample-run", "features/pay.feature|2", "Pay", null, null, 3,
                "three", 5, "PASSED", "2020-01-03T00:00:00Z", "2020-01-03T00:00:01Z"));
        Capture latest = run("dalton", "timeline", "--latest", "--limit=1", "--json", "--project=" + project);
        assertEquals(0, latest.code, latest.err);
        JsonNode latestJson = JSON.readTree(latest.out);
        assertEquals(1, latestJson.path("count").asInt(), latest.out);
        assertTrue(latestJson.path("offset").asInt() > 0, latest.out);
        assertEquals("three", latestJson.path("events").get(0).path("stepText").asText(), latest.out);
        assertFalse(latestJson.path("prev").asText().isBlank(), latest.out);
        String cursor = JSON.readTree(run("dalton", "timeline", "--json", "--project=" + project).out).path("cursor").asText();
        Capture before = run("dalton", "timeline", "--before=" + cursor, "--limit=1", "--json", "--project=" + project);
        assertEquals(0, before.code, before.err);
        JsonNode beforeEvents = JSON.readTree(before.out).path("events");
        assertEquals(1, beforeEvents.size(), before.out);
        assertEquals("two", beforeEvents.get(0).path("stepText").asText(), before.out);
        Capture bad = run("dalton", "timeline", "--cursor=not-a-cursor", "--project=" + project);
        assertEquals(1, bad.code, bad.out);
        assertTrue(bad.err.contains("--cursor is not a valid timeline cursor"), bad.err);
    }

    @Test
    void totalStaysTheFullCountOnEveryPage() throws Exception {
        for (int index = 1; index <= 5; index++) {
            DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                    "sample-run", "features/pay.feature|2", "Pay", null, null, index,
                    "step-" + index, index, "PASSED",
                    "2020-01-0" + index + "T00:00:00Z", "2020-01-0" + index + "T00:00:01Z"));
        }
        Capture all = run("dalton", "timeline", "--json", "--limit=50", "--project=" + project);
        int full = JSON.readTree(all.out).path("total").asInt();
        assertTrue(full > 4, all.out);
        String cursor = "";
        int pages = 0;
        int seen = 0;
        while (pages < 6) {
            Capture page = cursor.isBlank()
                    ? run("dalton", "timeline", "--limit=2", "--json", "--project=" + project)
                    : run("dalton", "timeline", "--cursor=" + cursor, "--limit=2", "--json", "--project=" + project);
            JsonNode json = JSON.readTree(page.out);
            assertEquals(full, json.path("total").asInt(), page.out);
            int count = json.path("count").asInt();
            seen += count;
            pages++;
            if (count < 2) break;
            cursor = json.path("cursor").asText();
            assertFalse(cursor.isBlank(), page.out);
        }
        assertTrue(pages > 2, "pages=" + pages);
        assertEquals(full, seen);

        String before = JSON.readTree(run("dalton", "timeline", "--latest", "--limit=2", "--json", "--project=" + project).out)
                .path("prev").asText();
        int backPages = 0;
        int backSeen = 0;
        while (backPages < 6 && before != null && !before.isBlank()) {
            Capture page = run("dalton", "timeline", "--before=" + before, "--limit=2", "--json", "--project=" + project);
            JsonNode json = JSON.readTree(page.out);
            assertEquals(full, json.path("total").asInt(), page.out);
            int count = json.path("count").asInt();
            backSeen += count;
            backPages++;
            if (count < 2) break;
            String next = json.path("prev").asText();
            if (next.equals(before)) break;
            before = next;
        }
        assertTrue(backPages > 2, "back=" + backPages + " seen=" + backSeen);
    }

    @Test
    void featureAndTagNarrowTheTimeline() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  @fast
                  Scenario: Pay
                    * the total is 10
                """);
        Files.writeString(project.resolve("features/other.feature"), """
                Feature: Other
                  Scenario: Other
                    * something else
                """);
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        int payLine;
        int otherLine;
        try (DaltonDatabase database = DaltonDatabase.open(project);
             java.sql.Statement statement = database.connection().createStatement()) {
            try (java.sql.ResultSet rows = statement.executeQuery(
                    "SELECT line FROM scenarios WHERE file_path = 'features/pay.feature'")) {
                assertTrue(rows.next());
                payLine = rows.getInt(1);
            }
            try (java.sql.ResultSet rows = statement.executeQuery(
                    "SELECT line FROM scenarios WHERE file_path = 'features/other.feature'")) {
                assertTrue(rows.next());
                otherLine = rows.getInt(1);
            }
        }
        for (String runId : new String[] {"run-a", "run-b"}) {
            String day = "run-a".equals(runId) ? "01" : "02";
            DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                    runId, "features/pay.feature|" + payLine, "Pay", null, null, 1,
                    "pay " + runId, payLine, "PASSED", "2020-01-" + day + "T00:00:00Z", "2020-01-" + day + "T00:00:01Z"));
            DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                    runId, "features/other.feature|" + otherLine, "Other", null, null, 1,
                    "other " + runId, otherLine, "PASSED", "2020-01-" + day + "T00:00:02Z", "2020-01-" + day + "T00:00:03Z"));
        }
        JsonNode feature = JSON.readTree(run("dalton", "timeline", "--feature=other.feature", "--json", "--project=" + project).out);
        assertEquals(4, feature.path("total").asInt(), feature.toString());
        for (JsonNode event : feature.path("events")) {
            assertFalse(event.path("scenarioKey").asText().startsWith("features/pay.feature"), feature.toString());
        }
        JsonNode tagged = JSON.readTree(run("dalton", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(4, tagged.path("total").asInt(), tagged.toString());
        for (JsonNode event : tagged.path("events")) {
            String key = event.path("scenarioKey").asText();
            if ("step".equals(event.path("kind").asText())) {
                assertTrue(key.startsWith("features/pay.feature"), tagged.toString());
                assertFalse(key.startsWith("features/other.feature"), tagged.toString());
            }
        }
    }

    @Test
    void tagSelectsTheTaggedScenarioNotItsFileSibling() throws Exception {
        Path feature = writePaySiblings();
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        int pay = lineContaining(feature, "Scenario: Pay");
        int refund = lineContaining(feature, "Scenario: Refund");
        record("run-a", "features/pay.feature|" + pay, "Pay", null, "pay step", "2020-01-01T00:00:00Z");
        record("run-a", "features/pay.feature|" + refund, "Refund", null, "refund step", "2020-01-01T00:00:02Z");

        JsonNode tagged = JSON.readTree(run("dalton", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(2, tagged.path("total").asInt(), tagged.toString());
        assertEquals(1, countKind(tagged.path("events"), "run"));
        assertFalse(tagged.toString().contains("features/pay.feature|" + refund), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + pay), tagged.toString());
    }

    @Test
    void tagSelectsOutlineExampleRowsNotTheUntaggedSibling() throws Exception {
        Path feature = writeOutlineAndRefund();
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        int examples = lineContaining(feature, "Examples:");
        int ada = lineContaining(feature, "| Ada |");
        int refund = lineContaining(feature, "Scenario: Refund");
        record("run-a", "features/pay.feature|" + examples, "Pay Ada", 1, "outline header", "2020-01-01T00:00:00Z");
        record("run-a", "features/pay.feature|" + ada, "Pay Bob", 2, "outline row", "2020-01-01T00:00:02Z");
        record("run-a", "features/pay.feature|" + refund, "Refund", null, "refund step", "2020-01-01T00:00:04Z");

        JsonNode tagged = JSON.readTree(run("dalton", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(3, tagged.path("total").asInt(), tagged.toString());
        assertEquals(1, countKind(tagged.path("events"), "run"));
        assertFalse(tagged.toString().contains("features/pay.feature|" + refund), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + examples), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + ada), tagged.toString());
    }

    @Test
    void tagSelectsOnlyTheTaggedExamplesBlock() throws Exception {
        Path feature = writeTaggedExamples();
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        int examples = lineContaining(feature, "Examples:");
        int ada = lineContaining(feature, "| Ada |");
        int bob = lineContaining(feature, "| Bob |");
        int refund = lineContaining(feature, "Scenario: Refund");
        record("run-a", "features/pay.feature|" + examples, "Pay header", 1, "outline header", "2020-01-01T00:00:00Z");
        record("run-a", "features/pay.feature|" + ada, "Pay Ada", 2, "ada row", "2020-01-01T00:00:02Z");
        record("run-a", "features/pay.feature|" + bob, "Pay Bob", 3, "bob row", "2020-01-01T00:00:04Z");
        record("run-a", "features/pay.feature|" + refund, "Refund", null, "refund step", "2020-01-01T00:00:06Z");

        JsonNode tagged = JSON.readTree(run("dalton", "timeline", "--tag=@extag", "--json", "--project=" + project).out);
        assertEquals(3, tagged.path("total").asInt(), tagged.toString());
        assertEquals(1, countKind(tagged.path("events"), "run"));
        assertTrue(tagged.toString().contains("features/pay.feature|" + examples), tagged.toString());
        assertTrue(tagged.toString().contains("features/pay.feature|" + ada), tagged.toString());
        assertFalse(tagged.toString().contains("features/pay.feature|" + bob), tagged.toString());
        assertFalse(tagged.toString().contains("features/pay.feature|" + refund), tagged.toString());

        JsonNode outline = JSON.readTree(run("dalton", "timeline", "--tag=@fast", "--json", "--project=" + project).out);
        assertEquals(4, outline.path("total").asInt(), outline.toString());
        assertEquals(1, countKind(outline.path("events"), "run"));
        assertTrue(outline.toString().contains("features/pay.feature|" + bob), outline.toString());
        assertFalse(outline.toString().contains("features/pay.feature|" + refund), outline.toString());
    }

    @Test
    void retainDropsRowsAndKeepsTheReport() throws Exception {
        Path shot = project.resolve("reports/diagnostic-runs/sample/shot.png");
        Files.createDirectories(shot.getParent());
        byte[] png = new byte[] {1, 2, 3, 4};
        Files.write(shot, png);
        run("dalton", "history", "record", "old-run", "--started=2020-01-01T00:00:00Z", "--project=" + project);
        DaltonArtifacts.link(project, "old-run", shot, null);
        Capture retained = run("dalton", "retain", "--days=1", "--max-bytes=1000000", "--project=" + project);
        assertEquals(0, retained.code, retained.err);
        assertTrue(retained.out.contains("retained runs="), retained.out);
        assertTrue(retained.out.contains("reports kept"), retained.out);
        assertEquals(png.length, Files.readAllBytes(shot).length);
        Capture after = run("dalton", "timeline", "--json", "--project=" + project);
        assertEquals(0, JSON.readTree(after.out).path("total").asInt(), after.out);
    }

    private Path writePaySiblings() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Path feature = project.resolve("features/pay.feature");
        Files.writeString(feature, """
                Feature: Pay
                  @fast
                  Scenario: Pay
                    * the total is 10
                  Scenario: Refund
                    * the total is 0
                """);
        return feature;
    }

    private Path writeOutlineAndRefund() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Path feature = project.resolve("features/pay.feature");
        Files.writeString(feature, """
                Feature: Pay
                  @fast
                  Scenario Outline: Pay <who>
                    * the total is 10
                    Examples:
                      | who |
                      | Ada |
                      | Bob |
                  Scenario: Refund
                    * the total is 0
                """);
        return feature;
    }

    private Path writeTaggedExamples() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Path feature = project.resolve("features/pay.feature");
        Files.writeString(feature, """
                Feature: Pay
                  @fast
                  Scenario Outline: Pay <who>
                    * the total is 10
                    @extag
                    Examples:
                      | who |
                      | Ada |
                    Examples:
                      | who |
                      | Bob |
                  Scenario: Refund
                    * the total is 0
                """);
        return feature;
    }

    private static int lineContaining(Path feature, String text) throws Exception {
        java.util.List<String> lines = Files.readAllLines(feature);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(text)) return index + 1;
        }
        throw new AssertionError(text + " not in " + feature);
    }

    private void record(String runId, String key, String name, Integer examplesRow, String text, String at) throws Exception {
        DaltonExecution.recordStep(project, new DaltonExecution.StepDraft(
                runId, key, name, null, examplesRow, 1, text, null, "PASSED", at, at));
    }

    private static int countKind(JsonNode events, String kind) {
        int count = 0;
        for (JsonNode event : events) if (kind.equals(event.path("kind").asText())) count++;
        return count;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
