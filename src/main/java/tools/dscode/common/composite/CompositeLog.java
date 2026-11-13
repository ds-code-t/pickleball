package tools.dscode.common.composite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.dscode.common.reporting.diagnostic.DiagnosticRuntime;
import tools.dscode.common.reporting.logging.LogForwarder;

/**
 * CLI-first log for the DOM engine adapter. Off unless
 * {@code pkb_composite_log} names an entity ({@code location}, {@code adapter},
 * {@code session}, {@code element}, {@code fallback}, {@code batch}, {@code state},
 * {@code position}, {@code geometry}, {@code query}, {@code exposure})
 * or {@code all}.
 * {@code query} writes {@code INFO query category} for dictionary XPath and
 * {@code INFO query engine} plus {@code TRACE query forward selector=} for an
 * engine pipeline or expression. {@code exposure} writes the lower-level act.
 * {@code geometry} writes {@code TRACE geometry request} when a query asks for
 * the five-space view, and {@code INFO geometry real} or {@code INFO geometry fallback}.
 * {@code adapter} writes the same choice as {@code INFO adapter geometry real}
 * or {@code INFO adapter geometry fallback}.
 * {@code batch} writes one TRACE line per describe:
 * {@code TRACE batch mode=<composite|selenium-batch|selenium-legacy> selector=<by> nodes=<count> durationMs=<n>}.
 * There is no per-element line. {@code position} writes
 * {@code TRACE position apply size=<n> position=<p> selection=<s> kept=<k> durationMs=<ms>}
 * and {@code TRACE position ordinal}. Files are date-stamped and capped.
 * An {@code auto} fallback is one line per run and reason
 * ({@code pkb_composite_fallback_log}: {@code warn} by default, {@code info}, or {@code off}).
 */
public final class CompositeLog {
    public static final String KEY = "pkb_composite_log";
    public static final String DIR_KEY = "pkb_composite_log_dir";
    /** {@code warn} (default), {@code info}, or {@code off}. */
    public static final String FALLBACK_KEY = "pkb_composite_fallback_log";
    public static final long CAP_BYTES = 1_048_576L;

    private static final Set<String> FALLBACK_REASONS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean OVERRIDE = new AtomicBoolean();
    private static final List<String> LINES = new CopyOnWriteArrayList<>();
    private static volatile String lastFallback;
    private static volatile String lastOverride;
    private static volatile String lastError;

    private CompositeLog() {
    }

    public static void resetForTests() {
        FALLBACK_REASONS.clear();
        OVERRIDE.set(false);
        LINES.clear();
        lastFallback = null;
        lastOverride = null;
        lastError = null;
    }

    public static String lastFallbackForTests() {
        return lastFallback;
    }

    public static String lastOverrideForTests() {
        return lastOverride;
    }

    public static List<String> linesForTests() {
        return List.copyOf(LINES);
    }

    public static String lastErrorForTests() {
        return lastError;
    }

    public static boolean enabled(String entity) {
        String raw = CompositeMode.setting(KEY);
        if (raw == null) return false;
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("all") || value.equals("true") || value.equals("on")) return true;
        if (value.equals("off") || value.equals("false") || value.equals("none")) return false;
        Set<String> names = Set.of(value.split("[,\\s]+"));
        return names.contains(entity.toLowerCase(Locale.ROOT));
    }

    public static void info(String entity, String message) {
        line(entity, "INFO", message);
    }

    public static void trace(String entity, String message) {
        line(entity, "TRACE", message);
    }

    public static void debug(String entity, String message) {
        line(entity, "DEBUG", message);
    }

    /**
     * One line per run and reason. {@code pkb_composite=on} still prints
     * {@code error:} and {@code fix:}. {@code auto} prints one line at the
     * {@code pkb_composite_fallback_log} level (default WARN) with the reason,
     * the engine path or {@code none}, and the fix. {@code off} for that log
     * prints nothing. {@code pkb_composite=off} stays at DEBUG.
     */
    public static void fallbackOnce(String error, String fix) {
        fallbackOnce("fallback", error, fix, null);
    }

    public static void fallbackOnce(String reason, String error, String fix, String engine) {
        String key = reason == null || reason.isBlank() ? "fallback" : reason.trim();
        String engineText = engine == null || engine.isBlank() ? "none" : engine;
        String block = "error: " + error + "\nfix: " + fix + "\nengine: " + engineText + "\nreason: " + key;
        CompositeMode mode = CompositeMode.current();
        if (mode.disabled()) {
            debug("fallback", block);
            return;
        }
        if (!FALLBACK_REASONS.add(key)) return;
        lastFallback = block;
        write(block, true);
        if (mode.required()) {
            emit("error: " + error);
            emit("fix: " + fix);
            pickleball("ERROR", "error: " + error + " fix: " + fix + " engine: " + engineText + " reason: " + key);
            diagnostic("ERROR", key, error, fix, engineText);
            return;
        }
        String level = fallbackLevel();
        if ("off".equals(level)) return;
        String label = "info".equals(level) ? "INFO" : "WARN";
        String line = label + " composite.fallback reason=" + key
                + " engine=" + engineText
                + " error: " + error
                + " fix: " + fix;
        emit(line);
        pickleball(label, line);
        diagnostic(label, key, error, fix, engineText);
    }

    /**
     * One INFO line per run naming the source key of an explicit engine override.
     * Printed even when {@code pkb_composite_log} is off.
     */
    public static void overrideOnce(String sourceKey, String path) {
        String key = sourceKey == null || sourceKey.isBlank() ? "explicit" : sourceKey;
        String line = "INFO composite.engine override source=" + key + " path=" + path;
        lastOverride = line;
        if (!OVERRIDE.compareAndSet(false, true)) return;
        emit(line);
        pickleball("INFO", line);
    }

    /**
     * Always printed. Desktop click and type use this so a missing upstream
     * verb is not hidden behind {@code pkb_composite_log}.
     */
    public static void always(String error, String fix) {
        String block = "error: " + error + "\nfix: " + fix;
        lastError = block;
        System.err.println("error: " + error);
        System.err.println("fix: " + fix);
        write(block, true);
    }

    public static void errorFix(String entity, String error, String fix) {
        if (!enabled(entity) && !CompositeMode.current().required()) return;
        System.err.println("error: " + error);
        System.err.println("fix: " + fix);
        write("error: " + error + "\nfix: " + fix, true);
    }

    private static String fallbackLevel() {
        String raw = CompositeMode.setting(FALLBACK_KEY);
        if (raw == null) return "warn";
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("off") || value.equals("false") || value.equals("none")) return "off";
        if (value.equals("info")) return "info";
        return "warn";
    }

    private static void emit(String line) {
        LINES.add(line);
        System.err.println(line);
    }

    private static void pickleball(String level, String line) {
        try {
            switch (level) {
                case "INFO" -> LogForwarder.logInfo(line);
                case "ERROR" -> LogForwarder.logError(line);
                case "DEBUG" -> LogForwarder.logDebug(line);
                default -> LogForwarder.logWarn(line);
            }
        } catch (RuntimeException ignored) {
            // A log sink must not hide the element-path choice.
        }
    }

    private static void diagnostic(String level, String reason, String error, String fix, String engine) {
        try {
            DiagnosticRuntime.recordRunEvent("composite.fallback", Map.of(
                    "level", level,
                    "reason", reason,
                    "engine", engine,
                    "error", error,
                    "fix", fix
            ));
        } catch (RuntimeException ignored) {
            // Diagnostics are evidence, not the element path.
        }
    }

    private static void line(String entity, String level, String message) {
        if (!enabled(entity)) return;
        String text = Instant.now() + " " + level + " " + entity + " " + message;
        System.err.println(text);
        write(text, false);
    }

    private static void write(String text, boolean force) {
        if (!force && CompositeMode.setting(KEY) == null && CompositeMode.setting(DIR_KEY) == null) return;
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            byte[] bytes = (text.endsWith("\n") ? text : text + "\n").getBytes(StandardCharsets.UTF_8);
            long size = Files.isRegularFile(file) ? Files.size(file) : 0L;
            if (size + bytes.length > CAP_BYTES) {
                Path rolled = file.resolveSibling(file.getFileName() + ".1");
                if (Files.isRegularFile(rolled) && Files.size(rolled) > CAP_BYTES) return;
                file = rolled;
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging must not fail the run.
        }
    }

    static Path file() {
        String dir = CompositeMode.setting(DIR_KEY);
        Path root = dir != null
                ? Path.of(dir)
                : Path.of(System.getProperty("java.io.tmpdir"), "pickleball-composite-logs");
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        return root.resolve("composite-" + day + ".log");
    }
}
