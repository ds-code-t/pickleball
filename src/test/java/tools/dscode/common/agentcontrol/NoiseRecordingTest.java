package tools.dscode.common.agentcontrol;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import tools.dscode.common.agentfast.LiveChromeDriver;
import tools.dscode.common.recording.StepGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Own UI, hover, and focus stay out of a recording. Two edits of one field are one step. */
class NoiseRecordingTest {
    @TempDir
    Path temp;

    private BrowserSession session;

    @AfterEach
    void closeChrome() {
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
            session = null;
        }
        PageSurfaces.resetRuntimeForTest();
    }

    @Test
    void scriptDropsOwnUiHoverAndFocusAndMergesOneField() {
        Map<String, String> hover = row("hover", "Save order", "button", "");
        Map<String, String> focus = row("focus", "Note", "textbox", "");
        Map<String, String> panel = row("click", "Pause", "button", "");
        panel.put("own", "true");
        panel.put("id", "pkb-pause");
        Map<String, String> first = row("input", "Note", "textbox", "A");
        first.put("id", "note");
        Map<String, String> second = row("change", "Note", "textbox", "Ada");
        second.put("id", "note");
        Map<String, String> click = row("click", "Save order", "button", "");
        StepGenerator.Script script = StepGenerator.generate(List.of(hover, focus, panel, first, second, click), Map.of());
        String gherkin = script.gherkin();
        assertTrue(gherkin.contains("enter \"<note>\" in the \"Note\" Textbox"), gherkin);
        assertTrue(gherkin.contains("click the \"Save order\" Button"), gherkin);
        assertFalse(gherkin.contains("hover"), gherkin);
        assertFalse(gherkin.contains("focus"), gherkin);
        assertFalse(gherkin.contains("Pause"), gherkin);
        assertEqualsOneEnter(gherkin);
        assertTrue(script.parameters().stream().anyMatch(p -> "Ada".equals(String.valueOf(p.get("example")))), script.parameters().toString());
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void openPanelAndEditorRecordOnlyThePage() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: no Chrome binary on this machine");
        Path profile = temp.resolve("profile");
        Files.createDirectories(profile);
        session = BrowserSession.launch("noise-record", "about:blank", false, true, profile, List.of());
        LiveChromeDriver driver = new LiveChromeDriver(session);
        AgentDriverHooks.template("noise-record");
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.template(null);
        Path page = temp.resolve("page.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><body>
                <button id="save">Save order</button>
                <label for="note">Note</label>
                <input id="note" name="note">
                </body></html>
                """);
        driver.get(page.toUri().toString());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("on", true);
        spec.put("status", "recording");
        spec.put("script", " ");
        spec.put("steps", List.of());
        assertTrue(PageSurfaces.runtimeCall("record", spec, true) != null, "runtime did not install");
        PageSurfaces.runtimeCall("panelOpen", Map.of("title", "Agent"), false);
        ((JavascriptExecutor) driver).executeScript("""
                var pause = document.querySelector('[data-pkb-record] button');
                if (pause) pause.click();
                var layer = document.querySelector('pkb-layer');
                var chrome = layer && layer.shadowRoot ? layer.shadowRoot.querySelector('button') : null;
                if (chrome) chrome.click();
                var save = document.getElementById('save');
                save.dispatchEvent(new PointerEvent('pointerover', {bubbles: true}));
                save.click();
                var field = document.getElementById('note');
                field.focus();
                field.value = 'Ada';
                field.dispatchEvent(new Event('input', {bubbles: true}));
                field.dispatchEvent(new Event('change', {bubbles: true}));
                """);
        Thread.sleep(700);
        String sensed = PageSurfaces.runtimeCall("sensed", null, false);
        assertTrue(sensed != null && sensed.contains("Save order"), sensed);
        assertTrue(sensed.contains("Ada"), sensed);
        assertFalse(sensed.contains("\"kind\":\"hover\"") || sensed.contains("\"type\":\"hover\""), sensed);
        assertFalse(sensed.contains("\"kind\":\"focus\"") || sensed.contains("\"type\":\"focus\""), sensed);
        assertFalse(sensed.contains("Pause"), sensed);
        assertFalse(sensed.contains("Accept"), sensed);
        assertFalse(sensed.contains("Send"), sensed);
    }

    private static void assertEqualsOneEnter(String gherkin) {
        int enters = 0;
        int from = 0;
        while (true) {
            int at = gherkin.indexOf("enter ", from);
            if (at < 0) break;
            enters++;
            from = at + 6;
        }
        assertTrue(enters == 1, gherkin);
    }

    private static Map<String, String> row(String kind, String name, String role, String value) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("type", kind);
        row.put("kind", kind);
        row.put("name", name);
        row.put("text", name);
        row.put("role", role);
        row.put("value", value);
        row.put("source", "human");
        row.put("id", name.toLowerCase().replace(' ', '-'));
        row.put("frame", "top");
        return row;
    }
}
