package tools.dscode.common.agentfast;

import dev.domengine.session.BrowserSession;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.AgentDriverHooks;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Opens the in-page panel and samples idle CPU plus warm UI-op latency.
 * A missing Chrome binary is reported. A binary that fails to open throws.
 * Headed follows the display: headless only when the graphics environment is headless.
 */
public final class SelftestUi {
    private SelftestUi() {
    }

    public static Map<String, Object> cpu(Path project, String component) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "selftest");
        body.put("component", component == null || component.isBlank() ? "panel" : component);
        boolean headed = !GraphicsEnvironment.isHeadless();
        body.put("headed", headed);
        String chrome = BrowserSession.findChrome();
        if (chrome == null) {
            body.put("ok", false);
            body.put("skipped", true);
            body.put("error", "no Chrome binary");
            body.put("sentence", "selftest ui --cpu found no Chrome binary.");
            return body;
        }
        Path root = project == null ? Path.of("") : project;
        Path profile = root.resolve(".pickleball").resolve("selftest-profile");
        Files.createDirectories(profile);
        Path page = root.resolve(".pickleball").resolve("selftest-page.html");
        Files.writeString(page, "<!DOCTYPE html><html><body><button id=\"save\" data-pkb-handle=\"save\">Save</button></body></html>");
        BrowserSession session = BrowserSession.launch("selftest-ui", "about:blank", headed, true, profile, List.of());
        LiveChromeDriver driver = new LiveChromeDriver(session);
        try {
            body.put("pid", session.pid);
            AgentDriverHooks.template("selftest");
            AgentDriverHooks.ready(driver);
            AgentDriverHooks.template(null);
            driver.get(page.toUri().toString());
            AgentControl.Result opened = AgentControl.execute(
                    project, "selftest", "panel-open", Map.of("target", "page"), List.of(), "selftest"
            );
            if (opened.exit() != 0) {
                throw new IllegalStateException(String.valueOf(opened.body()));
            }
            long[] samples = new long[20];
            String placement = null;
            for (int index = 0; index < samples.length; index++) {
                boolean paint = index % 2 == 0;
                Map<String, String> args = paint
                        ? Map.of("handle", "save")
                        : Map.of("id", placement == null ? "" : placement);
                long started = System.nanoTime();
                AgentControl.Result painted = AgentControl.execute(
                        project, "selftest", paint ? "border" : "unborder",
                        args, List.of(), "selftest"
                );
                samples[index] = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                if (painted.exit() != 0) throw new IllegalStateException(String.valueOf(painted.body()));
                if (paint) placement = String.valueOf(painted.body().get("id"));
            }
            Arrays.sort(samples);
            long p95 = samples[18];
            Thread.sleep(500);
            // One 500 ms slice on a headed macOS runner can include a WindowServer burst.
            // The idle figure is the quietest of three slices. A spin fails every slice.
            CpuSample cursor = cpuSample(session.pid);
            if (!cursor.supported()) {
                body.put("ok", false);
                body.put("unsupported", true);
                body.put("p95Ms", p95);
                body.put("cores", 0);
                body.put("error", "selftest ui --cpu has no process CPU clock on this machine");
                body.put("sentence", "selftest ui --cpu cannot read CPU time here.");
                return body;
            }
            double quietest = Double.POSITIVE_INFINITY;
            long quietTicks = 0;
            List<Double> coreSamples = new ArrayList<>();
            for (int window = 0; window < 3; window++) {
                Thread.sleep(500);
                CpuSample next = cpuSample(session.pid);
                if (!next.supported()) {
                    body.put("ok", false);
                    body.put("unsupported", true);
                    body.put("p95Ms", p95);
                    body.put("cores", 0);
                    body.put("error", "selftest ui --cpu has no process CPU clock on this machine");
                    body.put("sentence", "selftest ui --cpu cannot read CPU time here.");
                    return body;
                }
                double sample = cursor.ticks()
                        ? (next.ticksValue() - cursor.ticksValue()) / 100.0 / 0.5
                        : (next.nanos() - cursor.nanos()) / 1_000_000_000.0 / 0.5;
                if (sample >= 0) {
                    coreSamples.add(sample);
                    if (sample < quietest) {
                        quietest = sample;
                        quietTicks = cursor.ticks() ? next.ticksValue() - cursor.ticksValue() : 0;
                    }
                }
                cursor = next;
            }
            double cores = quietest == Double.POSITIVE_INFINITY ? 1 : quietest;
            body.put("p95Ms", p95);
            body.put("cores", cores);
            body.put("coreSamples", coreSamples);
            body.put("idleWindowMs", 500);
            body.put("ticks", quietTicks);
            boolean ok = cores < 0.1 && p95 <= 150;
            body.put("ok", ok);
            body.put("sentence", "panel idle cores=" + cores + " samples=" + coreSamples + " p95Ms=" + p95);
            if (!ok) body.put("error", "selftest ui --cpu missed the idle or p95 target");
            if (!ok) body.put("error", "selftest ui --cpu missed the idle or p95 target");
            return body;
        } finally {
            AgentDriverHooks.template(null);
            try {
                AgentDriverHooks.quitSession(driver);
            } catch (RuntimeException ignored) {
                // Chrome close still runs.
            }
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The report is already filled in.
            }
            deleteTree(profile);
        }
    }

    /**
     * Paints pin, toast, checklist, and status on a real page and reads the text back.
     * The cpu bench stays {@link #cpu}. A missing Chrome binary is reported.
     */
    public static Map<String, Object> pages(Path project) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "selftest");
        body.put("component", "pages");
        String chrome = BrowserSession.findChrome();
        if (chrome == null) {
            body.put("ok", false);
            body.put("skipped", true);
            body.put("error", "no Chrome binary");
            body.put("sentence", "selftest ui --component=pages found no Chrome binary.");
            return body;
        }
        Path root = project == null ? Path.of("") : project;
        Path profile = root.resolve(".pickleball").resolve("selftest-pages-profile");
        Files.createDirectories(profile);
        Path page = root.resolve(".pickleball").resolve("selftest-pages.html");
        Files.writeString(page, "<!DOCTYPE html><html><head><title>Pages</title></head><body><p>Pages</p></body></html>");
        boolean headed = !GraphicsEnvironment.isHeadless();
        BrowserSession session = BrowserSession.launch("selftest-pages", "about:blank", headed, true, profile, List.of());
        LiveChromeDriver driver = new LiveChromeDriver(session);
        try {
            body.put("pid", session.pid);
            AgentDriverHooks.template("selftest-pages");
            AgentDriverHooks.ready(driver);
            AgentDriverHooks.template(null);
            driver.get(page.toUri().toString());
            String[][] cards = {
                    {"pin", "text", "Pinned note"},
                    {"toast", "text", "Toast note"},
                    {"checklist", "items", "Alpha|Beta"},
                    {"status", "text", "Waiting here"}
            };
            List<String> wanted = List.of("Pinned note", "Toast note", "Alpha", "Beta", "Waiting here");
            StringBuilder read = new StringBuilder();
            for (String[] card : cards) {
                AgentControl.Result placed = AgentControl.execute(
                        project, "selftest-pages", card[0], Map.of(card[1], card[2]), List.of(), "selftest-pages"
                );
                if (placed.exit() != 0) {
                    body.put("ok", false);
                    body.put("error", String.valueOf(placed.body()));
                    body.put("sentence", card[0] + " did not place");
                    return body;
                }
                Object raw = driver.executeScript("""
                        var text = document.body ? document.body.innerText : '';
                        var layers = document.querySelectorAll('pkb-layer');
                        for (var i = 0; i < layers.length; i++) {
                          if (layers[i].shadowRoot) text += '\\n' + layers[i].shadowRoot.textContent;
                        }
                        return text;
                        """);
                read.append('\n').append(raw == null ? "" : raw);
            }
            String pageText = read.toString();
            List<String> seen = new ArrayList<>();
            for (String token : wanted) {
                if (pageText.contains(token)) seen.add(token);
            }
            body.put("pageText", pageText);
            body.put("seen", seen);
            boolean ok = seen.size() == wanted.size();
            body.put("ok", ok);
            body.put("sentence", ok ? "pages read " + String.join(", ", seen) : "pages missed a card");
            if (!ok) body.put("error", "selftest ui --component=pages did not read every card");
            return body;
        } finally {
            AgentDriverHooks.template(null);
            try {
                AgentDriverHooks.quitSession(driver);
            } catch (RuntimeException ignored) {
                // Chrome close still runs.
            }
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // The report is already filled in.
            }
            deleteTree(profile);
        }
    }

    static CpuSample cpuSample(long pid, boolean procStat) {
        if (procStat) return CpuSample.ticks(cpuTicks(pid));
        Optional<Long> handle = cpuNanosFromHandle(pid);
        // JDK on macOS fills totalCpuDuration only for the current process.
        List<String> rows = handle.isPresent() ? List.of() : psRows();
        return cpuSample(pid, handle, rows);
    }

    /** Handle clock wins. An empty handle uses the ps rows, including child processes. */
    static CpuSample cpuSample(long pid, Optional<Long> handleNanos, List<String> psRows) {
        if (handleNanos != null && handleNanos.isPresent()) return CpuSample.nanos(handleNanos.get());
        return sumTree(pid, psRows).map(CpuSample::nanos).orElseGet(CpuSample::unsupported);
    }

    private static CpuSample cpuSample(long pid) {
        return cpuSample(pid, Files.isRegularFile(Path.of("/proc/" + pid + "/stat")));
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover file does not keep the profile as the session directory.
                }
            }
        } catch (IOException ignored) {
            // The next selftest recreates the directory.
        }
    }

    private static long cpuTicks(long pid) {
        long total = ticks(pid);
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) return total;
        for (ProcessHandle child : handle.descendants().toList()) total += ticks(child.pid());
        return total;
    }

    private static long ticks(long pid) {
        Path stat = Path.of("/proc/" + pid + "/stat");
        if (!Files.isRegularFile(stat)) return 0L;
        try {
            String text = Files.readString(stat);
            int end = text.lastIndexOf(')');
            if (end < 0 || end + 2 >= text.length()) return 0L;
            String[] parts = text.substring(end + 2).trim().split(" ");
            if (parts.length < 13) return 0L;
            return Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
        } catch (Exception failure) {
            return 0L;
        }
    }

    private static Optional<Long> cpuNanosFromHandle(long pid) {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null) return Optional.empty();
        long total = durationNanos(handle);
        boolean any = handle.info().totalCpuDuration().isPresent();
        for (ProcessHandle child : handle.descendants().toList()) {
            if (child.info().totalCpuDuration().isPresent()) any = true;
            total += durationNanos(child);
        }
        return any ? Optional.of(total) : Optional.empty();
    }

    /**
     * One {@code ps} snapshot. Missing {@code ps} yields an empty list, which the
     * caller reports as an unsupported clock rather than a crash.
     */
    private static List<String> psRows() {
        Process process;
        try {
            process = new ProcessBuilder("ps", "-ax", "-o", "pid=,ppid=,time=")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException failure) {
            return List.of();
        }
        try {
            String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) return List.of();
            List<String> rows = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) rows.add(line);
            }
            return rows;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Sum {@code time} for {@code root} and every process whose parent chain reaches it. */
    static Optional<Long> sumTree(long root, List<String> rows) {
        if (rows == null || rows.isEmpty()) return Optional.empty();
        Map<Long, Long> cpu = new java.util.HashMap<>();
        Map<Long, List<Long>> children = new java.util.HashMap<>();
        for (String line : rows) {
            String[] cols = line.trim().split("\\s+", 3);
            if (cols.length < 3) continue;
            long pid;
            long parent;
            long nanos;
            try {
                pid = Long.parseLong(cols[0]);
                parent = Long.parseLong(cols[1]);
                nanos = parsePsCpu(cols[2]);
            } catch (NumberFormatException failure) {
                continue;
            }
            if (nanos < 0) continue;
            cpu.put(pid, nanos);
            children.computeIfAbsent(parent, key -> new ArrayList<>()).add(pid);
        }
        if (!cpu.containsKey(root)) return Optional.empty();
        long total = 0L;
        ArrayList<Long> pending = new ArrayList<>();
        pending.add(root);
        for (int index = 0; index < pending.size(); index++) {
            long pid = pending.get(index);
            total += cpu.getOrDefault(pid, 0L);
            List<Long> kids = children.get(pid);
            if (kids != null) pending.addAll(kids);
        }
        return Optional.of(total);
    }

    /**
     * Linux {@code ps} prints {@code [dd-]hh:mm:ss}. macOS prints minutes and
     * seconds with centiseconds, as {@code 0:00.01} or {@code 224:07.09}.
     * Returns -1 when the text is not a CPU time.
     */
    static long parsePsCpu(String raw) {
        if (raw == null) return -1L;
        String text = raw.trim();
        if (text.isEmpty() || "-".equals(text)) return -1L;
        long days = 0L;
        int dash = text.indexOf('-');
        if (dash > 0 && text.indexOf(':') > dash) {
            try {
                days = Long.parseLong(text.substring(0, dash));
            } catch (NumberFormatException failure) {
                return -1L;
            }
            text = text.substring(dash + 1);
        }
        String[] parts = text.split(":");
        if (parts.length < 2 || parts.length > 3) return -1L;
        try {
            long nanos = parts.length == 2
                    ? clockNanos(0L, parts[0], parts[1])
                    : clockNanos(Long.parseLong(parts[0]), parts[1], parts[2]);
            return days * 86_400_000_000_000L + nanos;
        } catch (NumberFormatException failure) {
            return -1L;
        }
    }

    private static long clockNanos(long hours, String minutesText, String secondsText) {
        long minutes = Long.parseLong(minutesText);
        int dot = secondsText.indexOf('.');
        String whole = dot >= 0 ? secondsText.substring(0, dot) : secondsText;
        String fraction = dot >= 0 ? secondsText.substring(dot + 1) : "";
        if (whole.isEmpty()) whole = "0";
        if (!digits(whole) || !digits(fraction)) throw new NumberFormatException(secondsText);
        long nanos = Long.parseLong(whole) * 1_000_000_000L;
        if (!fraction.isEmpty()) {
            if (fraction.length() > 9) fraction = fraction.substring(0, 9);
            long frac = Long.parseLong(fraction);
            for (int pad = fraction.length(); pad < 9; pad++) frac *= 10L;
            nanos += frac;
        }
        return hours * 3_600_000_000_000L + minutes * 60_000_000_000L + nanos;
    }

    private static boolean digits(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static long durationNanos(ProcessHandle handle) {
        return handle.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
    }

    record CpuSample(boolean supported, boolean ticks, long ticksValue, long nanos) {
        static CpuSample ticks(long value) {
            return new CpuSample(true, true, value, 0L);
        }

        static CpuSample nanos(long value) {
            return new CpuSample(true, false, 0L, value);
        }

        static CpuSample unsupported() {
            return new CpuSample(false, false, 0L, 0L);
        }
    }
}
