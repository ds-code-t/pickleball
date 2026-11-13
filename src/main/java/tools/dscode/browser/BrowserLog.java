package tools.dscode.browser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Toggleable, per-entity, date-stamped, size-capped log for the browser facade.
 * Off unless {@code pkb_browser_log} or {@code PKB_BROWSER_LOG} names an entity
 * ({@code facade}, {@code selector}, {@code selenium}, {@code cdp}, {@code bidi},
 * {@code playwright}, {@code fallback}) or {@code all}.
 * A protocol fallback is one WARN per run and reason even when the entity log is off,
 * unless {@code pkb_browser_fallback_log} is {@code off}. {@code info} lowers that line to INFO.
 * One file rolls at 1 MiB.
 */
public final class BrowserLog {
    public static final String KEY = "pkb_browser_log";
    public static final String DIR_KEY = "pkb_browser_log_dir";
    public static final String FALLBACK_KEY = "pkb_browser_fallback_log";
    public static final long CAP_BYTES = 1_048_576L;

    private static final Object LOCK = new Object();
    private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();
    private static final List<String> LINES = new CopyOnWriteArrayList<>();
    private static volatile long capBytes = CAP_BYTES;

    private BrowserLog() {
    }

    public static void resetForTests() {
        ONCE.clear();
        LINES.clear();
        capBytes = CAP_BYTES;
    }

    public static void testingCap(long bytes) {
        capBytes = bytes < 64 ? 64 : bytes;
    }

    public static List<String> linesForTests() {
        return List.copyOf(LINES);
    }

    public static boolean enabled(String entity) {
        String raw = setting(KEY, "PKB_BROWSER_LOG");
        if (raw == null || raw.isBlank()) return false;
        String value = raw.toLowerCase(Locale.ROOT).trim();
        if (value.equals("all") || value.equals("true") || value.equals("on")) return true;
        if (value.equals("off") || value.equals("false") || value.equals("none")) return false;
        for (String name : value.split("[,\\s]+")) {
            if (name.equals(entity == null ? "" : entity.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static void info(String entity, String message) {
        line(entity, "INFO", message);
    }

    public static void warn(String entity, String message) {
        line(entity, "WARN", message);
    }

    /**
     * One line per process and reason. Default level is WARN.
     * A second call with the same reason does nothing.
     */
    public static void warnOnce(String reason, String message) {
        String key = reason == null || reason.isBlank() ? "fallback" : reason.trim();
        if (!ONCE.add(key)) return;
        String level = fallbackLevel();
        if ("off".equals(level)) return;
        String label = "info".equals(level) ? "INFO" : "WARN";
        String text = label + " browser.fallback reason=" + key + " " + (message == null ? "" : message);
        LINES.add(text);
        System.err.println(text);
        write(text);
    }

    public static Path directory() {
        String raw = setting(DIR_KEY, "PKB_BROWSER_LOG_DIR");
        if (raw == null || raw.isBlank()) {
            return Path.of(System.getProperty("java.io.tmpdir", "."), "pickleball-browser-logs");
        }
        return Path.of(raw);
    }

    private static void line(String entity, String level, String message) {
        if (!enabled(entity)) return;
        String text = Instant.now() + " " + level + " " + entity + " "
                + (message == null ? "" : message.replace('\n', ' '));
        LINES.add(text);
        write(text);
    }

    private static void write(String text) {
        String line = text.endsWith("\n") ? text : text + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            try {
                Path dir = directory();
                Files.createDirectories(dir);
                String day = LocalDate.now(ZoneOffset.UTC).toString();
                Path file = dir.resolve("browser-" + day + ".log");
                if (Files.isRegularFile(file) && Files.size(file) + bytes.length > capBytes) {
                    Path rolled = dir.resolve("browser-" + day + ".1.log");
                    Files.move(file, rolled, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Logging must not fail the call.
            }
        }
    }

    private static String fallbackLevel() {
        String raw = setting(FALLBACK_KEY, "PKB_BROWSER_FALLBACK_LOG");
        if (raw == null || raw.isBlank()) return "warn";
        String value = raw.toLowerCase(Locale.ROOT).trim();
        if (value.equals("off") || value.equals("false") || value.equals("none")) return "off";
        if (value.equals("info")) return "info";
        return "warn";
    }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) return value;
        return System.getenv(env);
    }
}
