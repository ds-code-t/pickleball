package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BiDi verbs stay off until the flag is on. Events stay the five Selenium subscriptions. */
class SeleniumBidiCommandTest {
    @AfterEach
    void reset() {
        System.clearProperty("pkb_selenium_bidi");
        SeleniumBidiCommands.resetForTests();
    }

    @Test
    void scriptExits2WhenTheFlagIsUnset() {
        System.clearProperty("pkb_selenium_bidi");
        Capture capture = run("bidi", "script", "--source=return 1");
        assertEquals(2, capture.code);
        assertTrue(capture.err.contains("error:"));
        assertTrue(capture.err.contains("fix:"));
    }

    @Test
    void scriptRecordsOneCallWhenTheFlagIsOn() {
        System.setProperty("pkb_selenium_bidi", "on");
        AtomicInteger calls = new AtomicInteger();
        SeleniumBidiCommands.useSessionForTests(new SeleniumBidiCommands.ScriptSession() {
            @Override
            public void script(String source) {
                calls.incrementAndGet();
                assertEquals("return 1", source);
            }

            @Override
            public boolean playwright() {
                return false;
            }
        });
        Capture capture = run("bidi", "script", "--source=return 1");
        assertEquals(0, capture.code, capture.err);
        assertEquals(1, calls.get());
    }

    @Test
    void eventNamesAreTheFiveSubscriptions() {
        assertEquals(List.of(
                "onConsoleEntry",
                "onJavaScriptException",
                "onBeforeRequestSent",
                "onResponseCompleted",
                "onFetchError"), SeleniumBidiEvents.eventNames());
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeleniumBidiCommands.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
