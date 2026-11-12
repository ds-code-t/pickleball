package dev.domengine.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Toggleable, per-entity, date-stamped, size-capped log.
 * Off unless {@code COMPOSITE_LOG} or {@code DOMENGINE_LOG} names an entity or {@code all}.
 * One file rolls at {@code COMPOSITE_LOG_CAP_BYTES} (default 1 MiB).
 * {@code COMPOSITE_LOG_MAX_BYTES} (default 4× the file cap, and never above the 10 MB history-style ceiling
 * unless raised) is a hard stop for this process.
 */
public final class EngineLog {
    public static final long DEFAULT_CAP_BYTES = 1024L * 1024L;
    public static final long TEN_MB = 10L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static volatile Config config = Config.from(System.getenv());
    private static final AtomicLong CORRELATION = new AtomicLong();
    private static String correlation = "h0";
    private static long writtenBytes;
    private static volatile long trimCeiling = TEN_MB;

    private EngineLog() {
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            config = Config.from(System.getenv());
            writtenBytes = 0;
            correlation = "h0";
            trimCeiling = TEN_MB;
        }
    }

    /** Lowers the front-trim ceiling for a unit test. Production stays at {@link #TEN_MB}. */
    public static void testingTrimCeiling(long bytes) {
        trimCeiling = bytes < 32 ? 32 : bytes;
    }

    public static void configure(Map<String, String> env) {
        synchronized (LOCK) {
            config = Config.from(env == null ? Map.of() : env);
            writtenBytes = 0;
        }
    }

    public static void nextCorrelation() {
        correlation = "h" + CORRELATION.incrementAndGet();
    }

    public static String correlation() {
        return correlation;
    }

    public static boolean enabled(String entity) {
        return config.allows(entity);
    }

    public static void info(String entity, String message) {
        write("INFO", entity, message);
    }

    public static void debug(String entity, String message) {
        write("DEBUG", entity, message);
    }

    public static void warn(String entity, String message) {
        write("WARN", entity, message);
    }

    public static void error(String entity, String message) {
        write("ERROR", entity, message);
    }

    public static void trace(String entity, String message) {
        write("TRACE", entity, message);
    }

    public static Path directory() {
        return config.directory;
    }

    private static void write(String level, String entity, String message) {
        Config current = config;
        if (!current.allows(entity)) return;
        if (!current.levelAllows(level)) return;
        String line = Instant.now().toString()
                + " " + level
                + " " + entity
                + " id=" + entity + "/" + correlation
                + " " + (message == null ? "" : message.replace('\n', ' '))
                + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            if (writtenBytes >= current.maxBytes) return;
            try {
                Files.createDirectories(current.directory);
                Path file = activeFile(current);
                if (Files.isRegularFile(file) && Files.size(file) + bytes.length > current.capBytes) {
                    roll(file);
                }
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writtenBytes += bytes.length;
                if (Files.size(file) > trimCeiling) {
                    trimFromFront(file, trimCeiling);
                }
                System.err.print(line);
            } catch (IOException ignored) {
                // Logging must not fail the command.
            }
        }
    }

    private static Path activeFile(Config current) {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        return current.directory.resolve("composite-" + day + ".log");
    }

    private static void roll(Path file) throws IOException {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        int n = 1;
        Path dest;
        do {
            dest = file.resolveSibling("composite-" + day + "." + n + ".log");
            n++;
        } while (Files.exists(dest));
        Files.move(file, dest);
    }

    /**
     * Trim from the front, the same way {@code history.log} keeps its tail.
     * Used when a single engine log crosses the 10 MB ceiling.
     */
    public static void trimFromFront(Path file, long maxBytes) throws IOException {
        if (file == null || !Files.isRegularFile(file) || Files.size(file) <= maxBytes) return;
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= maxBytes) return;
        int cut = bytes.length - (int) maxBytes;
        while (cut < bytes.length && bytes[cut] != '\n') cut++;
        if (cut < bytes.length) cut++;
        if (cut >= bytes.length) cut = bytes.length - (int) maxBytes;
        byte[] tail = new byte[bytes.length - cut];
        System.arraycopy(bytes, cut, tail, 0, tail.length);
        Path temp = file.resolveSibling(file.getFileName() + ".trim");
        Files.write(temp, tail);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private record Config(Set<String> entities, String level, Path directory, long capBytes, long maxBytes) {
        static Config from(Map<String, String> env) {
            String named = first(env, "COMPOSITE_LOG", "DOMENGINE_LOG");
            Set<String> entities = Set.of();
            if (named != null && !named.isBlank()) {
                entities = java.util.Arrays.stream(named.split(","))
                        .map(part -> part.trim().toLowerCase(Locale.ROOT))
                        .filter(part -> !part.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
            String level = first(env, "COMPOSITE_LOG_LEVEL", "DOMENGINE_LOG_LEVEL");
            if (level == null || level.isBlank()) level = "info";
            String dir = first(env, "COMPOSITE_LOG_DIR", "DOMENGINE_LOG_DIR");
            Path directory = Path.of(dir == null || dir.isBlank() ? "composite-logs" : dir);
            long cap = parse(first(env, "COMPOSITE_LOG_CAP_BYTES", "DOMENGINE_LOG_CAP_BYTES"), DEFAULT_CAP_BYTES);
            if (cap < 64) cap = 64;
            String maxRaw = first(env, "COMPOSITE_LOG_MAX_BYTES", "DOMENGINE_LOG_MAX_BYTES");
            long fallback = Math.min(cap * 4, TEN_MB);
            long max = maxRaw == null || maxRaw.isBlank() ? fallback : parse(maxRaw, fallback);
            if (max < cap && maxRaw != null && !maxRaw.isBlank()) max = cap;
            return new Config(entities, level.toLowerCase(Locale.ROOT), directory, cap, max);
        }

        boolean allows(String entity) {
            if (entities.isEmpty() || entity == null) return false;
            if (entities.contains("all")) return true;
            return entities.contains(entity.toLowerCase(Locale.ROOT));
        }

        boolean levelAllows(String level) {
            return rank(level) <= rank(this.level);
        }

        private static int rank(String level) {
            return switch (level == null ? "" : level.toLowerCase(Locale.ROOT)) {
                case "error" -> 0;
                case "warn" -> 1;
                case "info" -> 2;
                case "debug" -> 3;
                case "trace" -> 4;
                default -> 2;
            };
        }

        private static String first(Map<String, String> env, String... keys) {
            if (env == null) return null;
            for (String key : keys) {
                String value = env.get(key);
                if (value != null && !value.isBlank()) return value;
            }
            return null;
        }

        private static long parse(String raw, long fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
