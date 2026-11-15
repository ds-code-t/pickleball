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
