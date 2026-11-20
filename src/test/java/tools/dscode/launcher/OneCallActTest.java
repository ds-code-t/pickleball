package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.composite.ElementOperations;
import tools.dscode.common.composite.ElementView;
import tools.dscode.common.composite.EngineQuery;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 7: act waits for its condition, form-fill reads values back, wait-for is wait. */
class OneCallActTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void actConditionReadsSaved() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("act.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Ready</title></head><body>
                <button id="save">Save</button>
                <script>
                document.getElementById('save').onclick = function () {
                  document.title = 'Saving';
                  setTimeout(function () { document.title = 'Saved'; }, 800);
                };
                </script>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=one-act");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertEquals("Ready", eval("document.title", "one-act"));

        Capture acted = run(
                "act", "--target=browser:auto", "--query=Save", "--kind=click",
                "--condition=title=Saved", "--timeout=5000", "--run-id=one-act");
        assertEquals(0, acted.code, acted.err + acted.out);
        JsonNode body = JSON.readTree(acted.out);
        assertTrue(body.path("applied").asBoolean(), acted.out);
        assertEquals("Saved", body.path("title").asText(), acted.out);
        assertEquals("title=Saved", body.path("condition").asText(), acted.out);
        assertEquals("Saved", eval("document.title", "one-act"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_command_form_fill() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("form.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Form</title></head><body>
                <input id="user" name="user">
                <input id="role" name="role">
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=one-form");
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture filled = run(
                "form-fill", "--target=browser:auto", "--fields=user=ada|role=admin", "--run-id=one-form");
        assertEquals(0, filled.code, filled.err + filled.out);
        JsonNode body = JSON.readTree(filled.out);
        assertTrue(body.path("applied").asBoolean(), filled.out);
        assertEquals("ada", body.path("fields").get(0).path("value").asText(), filled.out);
        assertEquals("admin", body.path("fields").get(1).path("value").asText(), filled.out);
        assertEquals("ada", eval("document.getElementById('user').value", "one-form"));
        assertEquals("admin", eval("document.getElementById('role').value", "one-form"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void covers_command_wait_for() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("wait.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Ready</title></head><body>Ready</body></html>");
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=one-wait");
        assertEquals(0, started.code, started.err + started.out);
        assertEquals("Ready", eval("document.title", "one-wait"));
        assertEquals("armed", eval("setTimeout(function(){document.title='Later'},800); 'armed'", "one-wait"));

        Capture waited = run(
                "wait-for", "--target=browser:auto", "--condition=title=Later", "--timeout=5000", "--run-id=one-wait");
        assertEquals(0, waited.code, waited.err + waited.out);
        JsonNode body = JSON.readTree(waited.out);
        assertEquals("wait", body.path("op").asText(), waited.out);
        assertEquals("Later", body.path("title").asText(), waited.out);
        assertEquals("Later", eval("document.title", "one-wait"));
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void snapshotTextQueryReadsSave() {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        assumeTrue(!CompositeRuntime.primary(), "skipped: composite is on; snapshot text= is the off path");
        ChromeDriver driver = open(BrowserSession.findChrome());
        try {
            driver.get("data:text/html,<title>Query</title><button>Save</button>");
            List<ElementView> views = ElementOperations.describe(driver, EngineQuery.of("text=Save"));
            ElementView button = null;
            int buttons = 0;
            for (ElementView view : views) {
                if ("button".equals(view.tag())) {
                    buttons++;
                    button = view;
                }
            }
            assertEquals(1, buttons, String.valueOf(views));
            assertEquals("Save", button.text());
            assertEquals(List.of(), ElementOperations.describe(driver, EngineQuery.of("text=Missing")));
        } finally {
            driver.quit();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void textQueryMatchesHiddenAndDisabledSave() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        assumeTrue(!CompositeRuntime.primary(), "skipped: composite is on; snapshot text= is the off path");
        Path page = temp.resolve("saves.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <button id="hid" hidden>Save</button>
                <button id="off" disabled style="width:80px;height:30px">Save</button>
                </body></html>
                """);
        ChromeDriver driver = open(BrowserSession.findChrome());
        try {
            driver.get(page.toUri().toString());
            List<ElementView> views = ElementOperations.describe(driver, EngineQuery.of("text=Save"));
            ElementView hidden = byId(views, "hid");
            ElementView disabled = byId(views, "off");
            assertEquals("Save", hidden.text());
            assertFalse(hidden.displayed(), String.valueOf(views));
            assertTrue(hidden.enabled(), String.valueOf(views));
            assertEquals("Save", disabled.text());
            assertTrue(disabled.displayed(), String.valueOf(views));
            assertFalse(disabled.enabled(), String.valueOf(views));
            assertTrue(disabled.width() > 0, String.valueOf(views));
        } finally {
            driver.quit();
        }
    }

    private static ElementView byId(List<ElementView> views, String id) {
        ElementView found = null;
        int count = 0;
        for (ElementView view : views) {
            if (id.equals(view.attribute("id"))) {
                count++;
                found = view;
            }
        }
        assertEquals(1, count, String.valueOf(views));
        return found;
    }

    private String eval(String expr, String runId) throws Exception {
        Capture title = run("eval", "--target=browser:auto", "--world=main", "--run-id=" + runId, "--expr=" + expr);
        assertEquals(0, title.code, title.err + title.out);
        return JSON.readTree(title.out).path("value").asText();
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

    private static ChromeDriver open(String binary) {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu");
        return new ChromeDriver(options);
    }

    private record Capture(int code, String out, String err) {
    }
}
