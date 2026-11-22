package tools.dscode.common.agentcontrol;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.recording.StepGenerator;
import tools.dscode.common.agentfast.LiveChromeDriver;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.MatchNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Person clicks inside a content iframe become a step for that frame, not the iframe host. */
class IframeRecordingTest {
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
    void iframeClickNamesTheFrameAndTheInnerElement() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("type", "click");
        row.put("kind", "click");
        row.put("name", "Pay");
        row.put("text", "Pay");
        row.put("role", "tab");
        row.put("source", "human");
        row.put("id", "pay");
        row.put("frame", "billing");
        StepGenerator.Script script = StepGenerator.generate(List.of(row), Map.of());
        assertTrue(script.gherkin().contains("in the \"billing\" IFrame"), script.gherkin());
        assertTrue(script.gherkin().contains("\"Pay\""), script.gherkin());
        assertFalse(script.gherkin().contains("iframe host"), script.gherkin());
        String phrase = " in the \"billing\" IFrame, click the \"Pay\" Button.";
        QuoteParser quotes = new QuoteParser(phrase);
        String masked = quotes.restoreFromWithOuterBookend(quotes.masked(), GlobalConstants.BOOK_END);
        MatchNode parsed = DefinitionContext.getNodeDictionary().parse(masked);
        assertNotNull(parsed, phrase);
        assertNotNull(parsed.getChild("phrase"), phrase + " -> " + parsed);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void personClickInsideASameOriginIframeIsRecorded() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: no Chrome binary on this machine");
        Path profile = temp.resolve("profile");
        Files.createDirectories(profile);
        session = BrowserSession.launch("iframe-record", "about:blank", false, true, profile, List.of());
        LiveChromeDriver driver = new LiveChromeDriver(session);
        AgentDriverHooks.template("iframe-record");
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.template(null);
        Path page = temp.resolve("frames.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><body>
                <iframe id="billing" name="billing" srcdoc="<!DOCTYPE html><html><body><button id=pay role=tab>Pay</button><label for=acct>Account</label><input id=acct name=acct></body></html>"></iframe>
                </body></html>
                """);
        driver.get(page.toUri().toString());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("on", true);
        spec.put("status", "recording");
        spec.put("script", " ");
        spec.put("steps", List.of());
        String armed = PageSurfaces.runtimeCall("record", spec, true);
        assertNotNull(armed, "runtime did not install");
        ((JavascriptExecutor) driver).executeScript("""
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
        assertNotNull(sensed, "no sensed rows");
        assertTrue(sensed.contains("Pay"), sensed);
        assertTrue(sensed.contains("billing"), sensed);
        assertTrue(sensed.contains("tab") || sensed.contains("Tab"), sensed);
        assertTrue(sensed.contains("Ada"), sensed);
        assertFalse(sensed.contains("\"name\":\"billing\""), sensed);
    }
}
