package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase 7: form-fill resolves every key before it writes, and uses each control's own path.
 * Kills "form-fill trusts every field".
 */
class FormFillKindTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void formFillUsesEachControlAndWritesNothingWhenAKeyIsMissing() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("form.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><head><title>Form</title></head><body>
                <form>
                <label for="email">Email</label>
                <input id="email" name="email" type="email" value="keep">
                <label for="plan">Plan</label>
                <select id="plan" name="plan">
                  <option value="">Pick</option>
                  <option value="pro">Pro</option>
                  <option value="basic">Basic</option>
                </select>
                <label for="news">News</label>
                <input id="news" name="news" type="checkbox">
                <label for="city">City</label>
                <input id="city" name="city">
                <label for="code">Code</label>
                <input id="code" name="code" oninput="this.value=this.value.toUpperCase()">
                </form>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome-headless", "--run-id=form-kind");
        assertEquals(0, started.code, started.err + started.out);

        Capture missed = run(
                "form-fill", "--target=browser:auto",
                "--fields=email=ada@b.c|nope=gone|plan=Pro", "--run-id=form-kind");
        assertEquals(1, missed.code, missed.err + missed.out);
        JsonNode missing = JSON.readTree(missed.out);
        assertEquals("not found", missing.path("error").asText(), missed.out);
        assertEquals("nope", missing.path("missing").get(0).asText(), missed.out);
        assertTrue(missing.path("fields").isArray(), missed.out);
        assertEquals("keep", eval("document.getElementById('email').value", "form-kind"));
        assertEquals("", eval("document.getElementById('plan').value", "form-kind"));
        assertEquals("false", eval("document.getElementById('news').checked", "form-kind"));

        Capture filled = run(
                "form-fill", "--target=browser:auto",
                "--fields=email=ada@b.c|plan=Pro|news=true|City=Austin|code=ab",
                "--run-id=form-kind");
        assertEquals(0, filled.code, filled.err + filled.out);
        JsonNode body = JSON.readTree(filled.out);
        assertTrue(body.path("applied").asBoolean(), filled.out);
        assertEquals("ada@b.c", field(body, "email").path("value").asText(), filled.out);
        assertEquals("select", field(body, "plan").path("kind").asText(), filled.out);
        assertEquals("pro", field(body, "plan").path("value").asText(), filled.out);
        assertEquals("checkbox", field(body, "news").path("kind").asText(), filled.out);
        assertEquals("true", field(body, "news").path("value").asText(), filled.out);
        assertEquals("Austin", field(body, "City").path("value").asText(), filled.out);
        assertEquals("AB", field(body, "code").path("value").asText(), filled.out);

        assertEquals("ada@b.c", eval("document.getElementById('email').value", "form-kind"));
        assertEquals("pro", eval("document.getElementById('plan').value", "form-kind"));
        assertEquals("true", eval("document.getElementById('news').checked", "form-kind"));
        assertEquals("on", eval("document.getElementById('news').value", "form-kind"));
        assertEquals("Austin", eval("document.getElementById('city').value", "form-kind"));
        assertEquals("AB", eval("document.getElementById('code').value", "form-kind"));
    }

    private static JsonNode field(JsonNode body, String name) {
        for (JsonNode row : body.path("fields")) {
            if (name.equals(row.path("field").asText())) return row;
        }
        return body;
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

    private record Capture(int code, String out, String err) {
    }
}
