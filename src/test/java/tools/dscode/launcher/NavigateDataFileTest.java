package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.coredefinitions.NavigationSteps;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: file, data, and about:blank resolve and load in Chrome. */
class NavigateDataFileTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fileDataAndBlankNavigate() throws Exception {
        assertEquals("file:/tmp/page.html", NavigationSteps.addressAfterConfigLookup("file:/tmp/page.html", null));
        assertEquals("data:text/html,Hi", NavigationSteps.addressAfterConfigLookup("data:text/html,Hi", null));
        assertEquals("about:blank", NavigationSteps.addressAfterConfigLookup("about:blank", null));
        assertEquals("about:blank?x=1", NavigationSteps.addressAfterConfigLookup("about:blank?x=1", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("data:", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("file:", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("not a url", null));
        assertNull(NavigationSteps.addressAfterConfigLookup("URL.missing", null));
        assertEquals(
                "https://from-config.example/home",
                NavigationSteps.addressAfterConfigLookup("file:/tmp/page.html", "https://from-config.example/home")
        );

        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("file.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>FilePage</title></head><body>File</body></html>");
        String fileUrl = page.toUri().toString();
        assertTrue(fileUrl.startsWith("file:"), fileUrl);
        assertEquals(fileUrl, NavigationSteps.addressAfterConfigLookup(fileUrl, null));
        openAndRead(fileUrl, "file-nav", "document.title", "FilePage");

        String dataUrl = "data:text/html,<title>DataPage</title>";
        assertEquals(dataUrl, NavigationSteps.addressAfterConfigLookup(dataUrl, null));
        openAndRead(dataUrl, "data-nav", "document.title", "DataPage");

        openAndRead("about:blank", "blank-nav", "location.href", "about:blank");
    }

    private void openAndRead(String url, String runId, String expr, String expected) throws Exception {
        Capture started = run("start", "--url=" + url, "--browser=chrome-headless", "--run-id=" + runId);
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertTrue(started.out.contains("url=" + url), started.out);
        Capture read = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, read.code, read.err + read.out);
        assertEquals(expected, JSON.readTree(read.out).path("value").asText(), read.out);
        ConsumerReadyCommands.stopQuiet(temp);
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
