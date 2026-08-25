package tools.dscode.workbench.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation buffer for worker/Workbench log text already written to files
 * or returned by live execution. It does not invent log lines.
 */
public final class WorkerLogBuffer {
    public enum Level {
        TRACE,
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }

    public record Entry(long id, Level level, String raw, String message) {
        public Entry {
            Objects.requireNonNull(level, "level");
            raw = raw == null ? "" : raw;
            message = message == null ? raw : message;
        }
    }

    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "(?i)(?:\\[\\s*)?(TRACE|DEBUG|INFO|WARN(?:ING)?|ERROR|SEVERE|FATAL)(?:\\s*\\])?(?:\\s*[:\\-])?\\s*(.*)"
    );

    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1;
    private Level minimum = Level.INFO;

    public void setMinimum(Level minimum) {
        this.minimum = minimum == null ? Level.INFO : minimum;
    }

    public Level minimum() {
        return minimum;
    }

    public void clear() {
        entries.clear();
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public List<Entry> visible() {
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.level().ordinal() >= minimum.ordinal()) {
                visible.add(entry);
            }
        }
        return List.copyOf(visible);
    }

    public List<Entry> appendRaw(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<Entry> added = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) continue;
            Entry entry = parse(line);
            entries.add(entry);
            added.add(entry);
        }
        return List.copyOf(added);
    }

    public Entry parse(String line) {
        String raw = line == null ? "" : line;
        Matcher matcher = LEVEL_PATTERN.matcher(raw.strip());
        if (matcher.matches()) {
            return new Entry(nextId++, levelOf(matcher.group(1)), raw, matcher.group(2));
        }
        return new Entry(nextId++, inferUnmarked(raw), raw, raw);
    }

    public static Level parseFilter(String raw) {
        if (raw == null || raw.isBlank()) return Level.INFO;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("WARN".equals(normalized)) return Level.WARNING;
        return Level.valueOf(normalized);
    }

    private static Level levelOf(String token) {
        String normalized = token.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARN", "WARNING" -> Level.WARNING;
            case "ERROR", "SEVERE", "FATAL" -> Level.ERROR;
            default -> Level.INFO;
        };
    }

    /**
     * Unmarked worker output is treated as INFO so a scenario-run log remains
     * visible at the default filter without fabricating a level the source
     * did not print.
     */
    private static Level inferUnmarked(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("exception") || lower.contains("failed")) {
            return Level.ERROR;
        }
        if (lower.contains("warn")) return Level.WARNING;
        return Level.INFO;
    }
}
