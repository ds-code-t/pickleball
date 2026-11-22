package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.session.BrowserSession;
import org.openqa.selenium.JavascriptExecutor;
import tools.dscode.common.agentfast.LiveChromeDriver;
import tools.dscode.common.recording.InteractionLog;
import tools.dscode.common.recording.RecordingSession;
import tools.dscode.common.recording.StepGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Clicks and types inside a fixture iframe and checks the recorded script. */
public final class RecordSelftest {
    private RecordSelftest() {
    }

    public static Map<String, Object> run(Path project) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "selftest");
        body.put("component", "record");
        if (BrowserSession.findChrome() == null) {
            body.put("ok", false);
            body.put("skipped", true);
            body.put("sentence", "selftest record found no Chrome binary.");
            return body;
        }
        BrowserSession session = null;
        try {
            Path root = project == null ? Path.of("") : project;
            Path profile = Files.createTempDirectory(root, "selftest-record-");
            session = BrowserSession.launch("selftest-record", "about:blank", false, true, profile, List.of());
            LiveChromeDriver driver = new LiveChromeDriver(session);
            AgentDriverHooks.template("selftest-record");
            AgentDriverHooks.ready(driver);
            AgentDriverHooks.template(null);
            Path page = profile.resolve("frames.html");
            Files.writeString(page, """
                    <!DOCTYPE html>
                    <html><body>
                    <iframe id="billing" name="billing" srcdoc="<!DOCTYPE html><html><body><button id=pay role=tab>Pay</button><label for=acct>Account</label><input id=acct name=acct></body></html>"></iframe>
                    </body></html>
                    """);
            driver.get(page.toUri().toString());
            RecordingSession.start(root, "selftest-record");
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("on", true);
            spec.put("status", "recording");
            spec.put("script", " ");
            spec.put("steps", List.of());
            String armed = PageSurfaces.runtimeCall("record", spec, true);
            if (armed == null) {
                body.put("ok", false);
                body.put("sentence", "selftest record did not install the page runtime.");
                return body;
            }
            ((JavascriptExecutor) driver).executeScript("""
                    var pause = document.querySelector('[data-pkb-record] button');
                    if (pause) pause.click();
                    var frame = document.getElementById('billing');
                    var doc = frame.contentDocument;
                    doc.getElementById('pay').click();
                    var field = doc.getElementById('acct');
                    field.focus();
                    field.value = 'Ada';
                    field.dispatchEvent(new Event('input', {bubbles: true}));
                    field.dispatchEvent(new Event('change', {bubbles: true}));
                    """);
            Thread.sleep(700);
            String sensed = PageSurfaces.runtimeCall("sensed", null, false);
            List<Map<String, String>> rows = rows(sensed);
            StepGenerator.Script script = StepGenerator.generate(rows, Map.of());
            String gherkin = script.gherkin() == null ? "" : script.gherkin();
            boolean frame = gherkin.contains("in the \"billing\" IFrame");
            boolean pay = gherkin.contains("\"Pay\"") && gherkin.contains("Tab");
            boolean typed = gherkin.contains("Ada") || script.parameters().stream().anyMatch(p -> "Ada".equals(String.valueOf(p.get("example"))));
            boolean quiet = !gherkin.contains("Pause") && !gherkin.contains("hover") && !gherkin.contains("Accept");
            boolean streamed = InteractionLog.streamLines(root).stream().anyMatch(row -> {
                String name = String.valueOf(row.get("name"));
                return name.contains("Pay") || "Ada".equals(row.get("value"));
            });
            boolean ok = frame && pay && typed && quiet && streamed;
            body.put("ok", ok);
            body.put("script", gherkin);
            body.put("streamed", streamed);
            body.put("sentence", ok ? "selftest record passed" : "selftest record did not match the iframe script");
            if (!ok) body.put("error", "expected the Pay tab and Ada inside billing, with no own-UI noise, on the stream");
            return body;
        } catch (RuntimeException | java.io.IOException | InterruptedException failure) {
            body.put("ok", false);
            body.put("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            body.put("sentence", "selftest record failed");
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return body;
        } finally {
            AgentDriverHooks.template(null);
            try {
                AgentDriverHooks.quit();
            } catch (RuntimeException ignored) {
                // The session close still runs.
            }
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                    // Already closed.
                }
            }
            PageSurfaces.resetRuntimeForTest();
        }
    }

    private static List<Map<String, String>> rows(String sensed) throws java.io.IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        if (sensed == null || sensed.isBlank() || "null".equals(sensed)) return rows;
        JsonNode node = AgentStore.JSON.readTree(sensed);
        if (!node.isArray()) return rows;
        for (JsonNode item : node) {
            Map<String, String> row = new LinkedHashMap<>();
            item.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isContainerNode()) {
                    row.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
            rows.add(row);
        }
        return rows;
    }
}
