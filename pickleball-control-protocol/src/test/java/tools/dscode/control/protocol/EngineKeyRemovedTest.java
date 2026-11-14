package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code pkb_composite_engine} is no longer a selector. */
class EngineKeyRemovedTest {
    @Test
    void unsetDoesNothing() {
        String previous = System.getProperty(JavaEngineLauncher.ENGINE_KEY);
        String runvars = System.getProperty("pkb_runvars." + JavaEngineLauncher.ENGINE_KEY);
        try {
            System.clearProperty(JavaEngineLauncher.ENGINE_KEY);
            System.clearProperty("pkb_runvars." + JavaEngineLauncher.ENGINE_KEY);
            JavaEngineLauncher.checkEngineKey(Map.of());
            JavaEngineLauncher.checkEngineKey(Map.of(JavaEngineLauncher.ENGINE_ENV, "  ", JavaEngineLauncher.ENGINE_KEY, ""));
        } finally {
            restore(JavaEngineLauncher.ENGINE_KEY, previous);
            restore("pkb_runvars." + JavaEngineLauncher.ENGINE_KEY, runvars);
        }
    }

    @Test
    void javaWarnsOnce() {
        PrintStream previous = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            JavaEngineLauncher.checkEngineKey(Map.of(JavaEngineLauncher.ENGINE_ENV, "java"));
            JavaEngineLauncher.checkEngineKey(Map.of(JavaEngineLauncher.ENGINE_KEY, "JAVA"));
        } finally {
            System.setErr(previous);
        }
        String text = captured.toString();
        String sentence = JavaEngineLauncher.ENGINE_KEY_WARN;
        int count = 0;
        for (int from = 0; from <= text.length(); ) {
            int at = text.indexOf(sentence, from);
            if (at < 0) break;
            count++;
            from = at + sentence.length();
        }
        assertTrue(count <= 1, text);
        assertEquals(sentence, "pkb_composite_engine is no longer used; the Java control engine is the only engine");
        if (count == 1) {
            assertTrue(text.contains("WARN " + sentence), text);
        }
    }

    @Test
    void rustAndAnyOtherValueFail() {
        IllegalStateException rust = assertThrows(IllegalStateException.class, () ->
                JavaEngineLauncher.checkEngineKey(Map.of(JavaEngineLauncher.ENGINE_ENV, "rust")));
        assertTrue(rust.getMessage().contains(
                "pkb_composite_engine=rust is not supported in 3.0.0: the Java control engine is the only engine"),
                rust.getMessage());
        assertTrue(rust.getMessage().contains("pkb_composite_bin"), rust.getMessage());
        IllegalStateException other = assertThrows(IllegalStateException.class, () ->
                JavaEngineLauncher.checkEngineKey(Map.of(JavaEngineLauncher.ENGINE_KEY, "other")));
        assertTrue(other.getMessage().contains("pkb_composite_engine=other is not supported in 3.0.0"), other.getMessage());
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
