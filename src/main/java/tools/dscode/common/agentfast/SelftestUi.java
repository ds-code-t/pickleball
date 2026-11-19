package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import tools.dscode.browser.SurfaceAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens the in-page panel in headless Chrome and samples idle CPU.
 * A missing Chrome binary is reported. A binary that fails to open throws.
 */
public final class SelftestUi {
    private SelftestUi() {
    }

    public static Map<String, Object> cpu(Path project, String component) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "selftest");
        body.put("component", component == null || component.isBlank() ? "panel" : component);
        body.put("headed", false);
        String chrome = BrowserSession.findChrome();
        if (chrome == null) {
            body.put("ok", false);
            body.put("skipped", true);
            body.put("error", "no Chrome binary");
            body.put("sentence", "selftest ui --cpu found no Chrome binary.");
            return body;
        }
        Path profile = (project == null ? Path.of("") : project).resolve(".pickleball").resolve("selftest-profile");
        Files.createDirectories(profile);
        BrowserSession session = BrowserSession.launch("selftest-ui", "about:blank", false, true, profile, List.of());
        try {
            body.put("pid", session.pid);
            String runtime = SurfaceAssets.script("pkb-runtime");
            JsonNode installed = eval(session, runtime);
            if (installed.has("exceptionDetails")) {
                throw new IllegalStateException(installed.toString());
            }
            JsonNode opened = eval(session, "window.__pkbUi.panelOpen({})");
            if (opened.has("exceptionDetails")) {
                throw new IllegalStateException(opened.toString());
            }
            long[] samples = new long[20];
            for (int index = 0; index < samples.length; index++) {
                long started = System.nanoTime();
                JsonNode sample = eval(session, "window.__pkbUi.raiseCount()");
                samples[index] = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                if (sample.has("exceptionDetails")) throw new IllegalStateException(sample.toString());
            }
            Arrays.sort(samples);
            long p95 = samples[18];
            Thread.sleep(500);
            long before = cpuTicks(session.pid);
            Thread.sleep(500);
            long spent = cpuTicks(session.pid) - before;
            double cores = spent / 100.0 / 0.5;
            body.put("p95Ms", p95);
            body.put("cores", cores);
            body.put("idleWindowMs", 500);
            body.put("ticks", spent);
            boolean ok = cores < 0.1 && p95 <= 150;
            body.put("ok", ok);
            body.put("sentence", "panel idle cores=" + cores + " p95Ms=" + p95);
            if (!ok) body.put("error", "selftest ui --cpu missed the idle or p95 target");
            return body;
        } finally {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The report is already filled in.
            }
        }
    }

    private static JsonNode eval(BrowserSession session, String expression) {
        var params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = session.connection().call("Runtime.evaluate", params, Duration.ofSeconds(20));
        if (result == null) throw new IllegalStateException("Runtime.evaluate returned null");
        return result;
    }

    private static long cpuTicks(long pid) throws Exception {
        long total = ticks(pid);
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
