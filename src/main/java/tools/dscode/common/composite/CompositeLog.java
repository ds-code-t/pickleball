package tools.dscode.common.composite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CLI-first log for the dom-composite adapter. Off unless
 * {@code pkb_composite_log} names an entity ({@code location}, {@code adapter},
 * {@code session}, {@code element}, {@code fallback}) or {@code all}.
 * Files are date-stamped and capped. The fallback line is recorded once per run.
 */
public final class CompositeLog {
    public static final String KEY = "pkb_composite_log";
    public static final String DIR_KEY = "pkb_composite_log_dir";
    public static final long CAP_BYTES = 1_048_576L;

    private static final AtomicBoolean FALLBACK = new AtomicBoolean();
    private static volatile String lastFallback;
    private static volatile String lastError;

    private CompositeLog() {
    }

    public static void resetForTests() {
        FALLBACK.set(false);
        lastFallback = null;
        lastError = null;
    }

    public static String lastFallbackForTests() {
        return lastFallback;
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

    public static void debug(String entity, String message) {
        line(entity, "DEBUG", message);
    }

    /**
     * One fallback per run. The line is kept for tests and written to the log
     * file. It is printed on the CLI when {@code fallback} logging is on, or
     * when {@code pkb_composite=on}.
     */
    public static void fallbackOnce(String error, String fix) {
        if (!FALLBACK.compareAndSet(false, true)) return;
        String block = "error: " + error + "\nfix: " + fix;
        lastFallback = block;
        write(block, true);
        if (enabled("fallback") || CompositeMode.current().required()) {
            System.err.println("error: " + error);
            System.err.println("fix: " + fix);
        }
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
