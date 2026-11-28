package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 14: perf --report --run-id=agent found no spill when the host was started by helper. */
class PerfReportHostRunTest {
    @TempDir
    Path project;

    @Test
    void theReportReadsTheHostRunsSpillAndSaysSo() throws Exception {
        Path proto = project.resolve(".pickleball/proto");
        Files.createDirectories(proto);
        Files.writeString(proto.resolve("host.json"), "{\"port\":1,\"pid\":1,\"run\":\"helper\"}");
        Path runs = project.resolve(".pickleball/runs/helper");
        Files.createDirectories(runs);
        Files.writeString(runs.resolve("browser-events-" + LocalDate.now() + ".ndjson"),
                "{\"kind\":\"net\",\"subkind\":\"finished\",\"url\":\"https://example.test/a\",\"durationMs\":42,\"run\":\"helper\"}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ProtoVerbs.run(new String[] {"perf", "--report", "--run-id=agent"}, project,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8) + text);
        assertTrue(text.contains("\"spillRun\":\"helper\""), text);
        assertTrue(Files.isRegularFile(project.resolve(".pickleball/runs/agent/workbench/browser.html")));
    }

    @Test
    void noSpillAnywhereStillFails() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ProtoVerbs.run(new String[] {"perf", "--report", "--run-id=agent"}, project,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertTrue(code != 0);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("no browser-events spill"), err.toString(StandardCharsets.UTF_8));
    }
}
