package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.SurfaceAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens the in-page panel in real headless Chrome and dispatches {@code toggle}.
 * The old {@code raiseLayer} hid and showed its own popover, which fired
 * {@code toggle} again and spun the CPU. A binary that fails to open is a failure.
 */
class UiCpuLoopTest {
    @TempDir
    Path temp;

    private BrowserSession session;

    @AfterEach
    void close() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The assertion already ran.
            }
            session = null;
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void panelOpenDoesNotReenterOnToggle() throws Exception {
        String chrome = BrowserSession.findChrome();
        Assumptions.assumeTrue(chrome != null, "skipped: no Chrome binary on this machine");
        String runtime = SurfaceAssets.script("pkb-runtime");
        assertTrue(runtime.contains("raisingLayer"), "runtime is missing the re-entry guard");
        assertTrue(runtime.contains("event.target === layer"), "runtime still handles its own toggle");

        session = BrowserSession.launch("ui-cpu", "about:blank", false, true, temp.resolve("profile"), java.util.List.of());
        assertTrue(session.pid > 0, "chrome pid");
        eval(runtime);
        JsonNode opened = eval("window.__pkbUi.panelOpen({})");
        assertFalse(opened.has("exceptionDetails"), opened.toString());

        long started = System.nanoTime();
        JsonNode raises = eval("""
                (function () {
                  var before = window.__pkbUi.raiseCount();
                  document.dispatchEvent(new Event('toggle'));
                  document.dispatchEvent(new Event('toggle'));
                  document.dispatchEvent(new Event('toggle'));
                  return window.__pkbUi.raiseCount() - before;
                })()
                """);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertFalse(raises.has("exceptionDetails"), raises.toString());
        int delta = raises.path("result").path("value").asInt(-1);
        assertEquals(3, delta, "raiseCount delta=" + delta + " body=" + raises);
        assertTrue(elapsedMs < 5_000, "toggle dispatch took " + elapsedMs + "ms");

        long before = cpuTicks(session.pid);
        Thread.sleep(500);
        long spent = cpuTicks(session.pid) - before;
        double cores = spent / 100.0 / 0.5;
        assertTrue(cores < 0.5, "panel idle cpu cores=" + cores + " ticks=" + spent);
    }

    private JsonNode eval(String expression) {
        var params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = session.connection().call("Runtime.evaluate", params, Duration.ofSeconds(20));
        assertNotNull(result);
        return result;
    }

    /** Sum of utime+stime for the browser and its children, in clock ticks. */
    private static long cpuTicks(long pid) throws Exception {
        long total = 0;
        total += ticks(pid);
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) return total;
        for (ProcessHandle child : handle.descendants().toList()) total += ticks(child.pid());
        return total;
    }

    private static long ticks(long pid) throws Exception {
        Path stat = Path.of("/proc/" + pid + "/stat");
        if (!Files.isRegularFile(stat)) return 0;
        String text = Files.readString(stat);
        int end = text.lastIndexOf(')');
        if (end < 0 || end + 2 >= text.length()) return 0;
        String[] parts = text.substring(end + 2).trim().split(" ");
        if (parts.length < 13) return 0;
        return Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
    }
}
