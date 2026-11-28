package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HCR teardown: {@code live-stop} (all forms, both launchers) exited 2 {@code unknown verb live-stop} although help
 * lists it and S0 teardown needs it. Both launchers run verbs through {@link VerbDispatch}.
 */
class LiveStopVerbTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(project);
    }

    private record Capture(int code, String out, String err) {
    }

    private Capture dispatch(String... argv) {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("project", project.toString());
        String[] withProject = new String[argv.length + 1];
        System.arraycopy(argv, 0, withProject, 0, argv.length);
        withProject[argv.length] = project.toString();
        flags.put(VerbArgs.ARGV, VerbArgs.encode(withProject));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerbDispatch.run(argv[0], flags, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void newSession() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(new String[]{"spine", "session", "new", project.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void liveStopIsAKnownVerbWithNothingRunning() throws Exception {
        newSession();
        for (String[] form : new String[][]{{"live-stop"}, {"live-stop", "--all"}}) {
            Capture stopped = dispatch(form);
            assertFalse(stopped.err().contains("unknown verb"), String.join(" ", form) + ": " + stopped.err());
            assertEquals(0, stopped.code(), stopped.out() + stopped.err());
            assertTrue(stopped.out().contains("stopped="), stopped.out());
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void liveStopStopsTheStartedBrowser() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        newSession();
        Path page = project.resolve("p.html");
        Files.writeString(page, "<!doctype html><title>stop</title><button>Go</button>");
        Capture started = dispatch("start", "--url=" + page.toUri(), "--browser=chrome-headless");
        assertEquals(0, started.code(), started.out() + started.err());
        Capture stopped = dispatch("live-stop");
        assertEquals(0, stopped.code(), stopped.out() + stopped.err());
        assertTrue(stopped.out().contains("stopped=true"), stopped.out() + stopped.err());
        Capture again = dispatch("live-stop");
        assertTrue(again.out().contains("stopped=false"), "nothing left to stop: " + again.out());
    }
}
