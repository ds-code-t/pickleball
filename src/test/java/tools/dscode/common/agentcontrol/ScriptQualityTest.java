package tools.dscode.common.agentcontrol;

import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.agentfast.LiveChromeDriver;
import tools.dscode.common.agentfast.PushChannel;
import tools.dscode.common.mappings.QuoteParser;
import tools.dscode.common.recording.InteractionLog;
import tools.dscode.common.recording.RecordControl;
import tools.dscode.common.recording.StepGenerator;
import tools.dscode.common.treeparsing.DefinitionContext;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Roles, accessible names, one form-fill step, and non-ASCII parameter names. */
class ScriptQualityTest {
    @TempDir
    Path temp;

    private BrowserSession session;

    @BeforeEach
    void resetPushChannel() throws Exception {
        var reset = PushChannel.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @AfterEach
    void closeChrome() throws Exception {
        resetPushChannel();
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
    void tabRoleInjectFormFillAndUnicodeStayAccurate() throws Exception {
        Capture started = run("record", temp.toString(), "--start", "--session=qual");
        assertEquals(0, started.code, started.out + started.err);

        Capture injected = run("record", temp.toString(), "--inject", "--kind=click", "--query=Billing", "--role=tab");
        assertEquals(0, injected.code, injected.out + injected.err);

        Map<String, String> named = new LinkedHashMap<>();
        named.put("type", "click");
        named.put("kind", "click");
        named.put("name", "Account");
        named.put("text", "Account");
        named.put("role", "textbox");
        named.put("value", "1001");
        named.put("source", "human");
        named.put("id", "acct");
        named.put("frame", "top");
        InteractionLog.accept(temp, named);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", List.of(
                Map.of("field", "Account Number", "kind", "text", "value", "1001"),
                Map.of("field", "City", "kind", "text", "value", "豊田"),
                Map.of("field", "Plan", "kind", "select", "value", "Basic")
        ));
        RecordControl.noteAgent(temp, "qual", "form-fill", Map.of(
                "fields", "Account Number=1001|City=豊田|Plan=Basic"
        ), body);
        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("type", "input");
        duplicate.put("kind", "input");
        duplicate.put("name", "Account Number");
        duplicate.put("role", "textbox");
        duplicate.put("value", "1001");
        duplicate.put("source", "human");
        duplicate.put("id", "account-number");
        duplicate.put("frame", "top");
        InteractionLog.accept(temp, duplicate);

        Map<String, String> cafe = new LinkedHashMap<>();
        cafe.put("type", "input");
        cafe.put("kind", "input");
        cafe.put("name", "名前");
        cafe.put("text", "名前");
        cafe.put("role", "textbox");
        cafe.put("value", "Café");
        cafe.put("source", "human");
        cafe.put("id", "jp");
        cafe.put("frame", "top");
        InteractionLog.accept(temp, cafe);

        Capture script = run("record", temp.toString(), "--script");
        assertEquals(0, script.code, script.out + script.err);
        assertTrue(script.out.contains("click the \\\"Billing\\\" Tab"), script.out);
        assertFalse(script.out.contains("Billing\\\" Button"), script.out);
        assertTrue(script.out.contains("click the \\\"Account\\\" Textbox"), script.out);
        assertFalse(script.out.contains("click the \\\"1001\\\""), script.out);
        assertTrue(script.out.contains("豊田"), script.out);
        assertTrue(script.out.contains("名前"), script.out);
        assertTrue(script.out.contains("<名前>") || script.out.contains("\\u540d\\u524d"), script.out);
        assertFalse(script.out.contains("<value>"), script.out);
        assertTrue(script.out.contains("\"count\":4") || script.out.contains("\"count\": 4"), script.out);
        assertTrue(script.out.contains("enter \\\"<accountNumber>\\\" in the \\\"Account Number\\\" Textbox, enter \\\"<city>\\\" in the \\\"City\\\" Textbox, and select \\\"<plan>\\\" in the \\\"Plan\\\" Dropdown"), script.out);
        parse(" click the \"Billing\" Tab.");
        parse(" enter \"<accountNumber>\" in the \"Account Number\" Textbox, enter \"<city>\" in the \"City\" Textbox, and select \"<plan>\" in the \"Plan\" Dropdown.");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void aClickUsesTheAccessibleNameNotTheFieldValue() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: no Chrome binary on this machine");
        Path profile = temp.resolve("profile");
        Files.createDirectories(profile);
        session = BrowserSession.launch("script-quality", "about:blank", false, true, profile, List.of());
        LiveChromeDriver driver = new LiveChromeDriver(session);
        AgentDriverHooks.template("script-quality");
        AgentDriverHooks.ready(driver);
        AgentDriverHooks.template(null);
        Path page = temp.resolve("named.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><body>
                <label for="acct">Account</label>
                <input id="acct" name="acct" value="1001">
                <button id="pay" role="tab">Pay</button>
                </body></html>
                """);
        driver.get(page.toUri().toString());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("on", true);
        spec.put("status", "recording");
        spec.put("script", " ");
        spec.put("steps", List.of());
        assertNotNull(PageSurfaces.runtimeCall("record", spec, true));
        ((JavascriptExecutor) driver).executeScript("""
                document.getElementById('acct').click();
                document.getElementById('pay').click();
                """);
        Thread.sleep(200);
        String sensed = PageSurfaces.runtimeCall("sensed", null, false);
        assertNotNull(sensed);
        assertTrue(sensed.contains("\"name\":\"Account\""), sensed);
        assertTrue(sensed.contains("\"name\":\"Pay\""), sensed);
        assertTrue(sensed.contains("\"role\":\"tab\""), sensed);
        assertFalse(sensed.contains("\"name\":\"1001\""), sensed);
    }

    private static void parse(String phrase) {
        QuoteParser quotes = new QuoteParser(phrase);
        String masked = quotes.restoreFromWithOuterBookend(quotes.masked(), GlobalConstants.BOOK_END);
        MatchNode parsed = DefinitionContext.getNodeDictionary().parse(masked);
        assertNotNull(parsed, phrase);
        assertNotNull(parsed.getChild("phrase"), phrase + " -> " + parsed);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
