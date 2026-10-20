package tools.dscode.launcher;

import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Health wait for {@code isolate} / {@code session-start}.
 * The limit is the Workbench worker start timeout, resolved the same way.
 * {@code open-window} does not use this type.
 */
final class SessionHealthWait {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    static final Duration PROGRESS_EVERY = Duration.ofSeconds(12);
    static final String START_TIMEOUT_PROPERTY = "pkb_workbench_worker_start_timeout";
    static final String START_TIMEOUT_ENV = "PKB_WORKBENCH_WORKER_START_TIMEOUT";
    static final String START_TIMEOUT_RUNVAR = "pkb_runvars.pkb_workbench_worker_start_timeout";
    static final String GENERIC_STAGE = "starting / Maven resolve or feature index";
    static final String PROGRESS_FILE = "startup-progress.json";
    static final int TAIL_LINES = 20;

    private static final Pattern STAGE_LINE = Pattern.compile("progress stage=(\\S+)");
    private static final int TAIL_BYTES = 256 * 1024;

    /** Test-only. {@code null} uses {@link #PROGRESS_EVERY}. Cleared by the test that sets it. */
    static volatile Duration progressEveryOverride;

    private SessionHealthWait() {
    }

    enum Kind {
        HEALTHY, DIED, TIMED_OUT
    }

    record Outcome(Kind kind, String stage, long elapsedMs) {
    }

    static Duration timeout(Map<String, String> properties) {
        return timeout(properties, System::getProperty, System::getenv);
    }

    /**
     * Same order as the Workbench worker {@code startTimeout}:
     * system property, environment, dotted {@code pkb_runvars} property,
     * compact {@code pkb_runvars}, then the same two keys on {@code properties}.
     * Blank, non-numeric, or not greater than zero stays {@link #DEFAULT_TIMEOUT}.
     */
    static Duration timeout(
            Map<String, String> properties,
            Function<String, String> property,
            Function<String, String> env
    ) {
        Map<String, String> values = properties == null ? Map.of() : properties;
        Function<String, String> readProperty = property == null ? key -> null : property;
        Function<String, String> readEnv = env == null ? key -> null : env;
        String raw = firstNonBlank(
                readProperty.apply(START_TIMEOUT_PROPERTY),
                readEnv.apply(START_TIMEOUT_ENV),
                readProperty.apply(START_TIMEOUT_RUNVAR),
                compactValue(readProperty.apply("pkb_runvars"), START_TIMEOUT_PROPERTY),
                values.get(START_TIMEOUT_PROPERTY),
                compactValue(values.get("pkb_runvars"), START_TIMEOUT_PROPERTY)
        );
        if (raw == null || raw.isBlank()) return DEFAULT_TIMEOUT;
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) return DEFAULT_TIMEOUT;
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException failure) {
            return DEFAULT_TIMEOUT;
        }
    }

    static Duration progressEvery() {
        Duration override = progressEveryOverride;
        if (override == null || override.isZero() || override.isNegative()) return PROGRESS_EVERY;
        return override;
    }

    static Outcome waitFor(
            BooleanSupplier healthy,
            BooleanSupplier alive,
            Path project,
            Path sessionLog,
            PrintStream out,
            PrintStream err,
            Duration timeout,
            Duration progressEvery,
            Duration poll
    ) {
        Duration limit = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        Duration every = progressEvery == null || progressEvery.isZero() || progressEvery.isNegative()
                ? PROGRESS_EVERY
                : progressEvery;
        Duration pause = poll == null || poll.isNegative() || poll.isZero() ? Duration.ofMillis(200) : poll;
        long started = System.nanoTime();
        long deadline = started + limit.toNanos();
        long nextProgress = started + every.toNanos();
        StageTimeline timeline = new StageTimeline();
        while (System.nanoTime() < deadline) {
            if (healthy != null && healthy.getAsBoolean()) {
                return new Outcome(Kind.HEALTHY, timeline.current(), elapsedMs(started));
            }
            if (alive != null && !alive.getAsBoolean()) {
                if (healthy != null && healthy.getAsBoolean()) {
                    return new Outcome(Kind.HEALTHY, timeline.current(), elapsedMs(started));
                }
                return new Outcome(Kind.DIED, timeline.current(), elapsedMs(started));
            }
            String stage = currentStage(project, sessionLog);
            if (timeline.observe(stage, elapsedMs(started))) {
                writeTimeline(sessionLog, timeline);
            }
            long now = System.nanoTime();
            if (now >= nextProgress) {
                if (out != null) {
                    out.println(progressLine(Duration.ofNanos(Math.max(0, now - started)), timeline.current()));
                }
                nextProgress = now + every.toNanos();
            }
            if (System.nanoTime() >= deadline) break;
            sleep(pause);
        }
        if (healthy != null && healthy.getAsBoolean()) {
            return new Outcome(Kind.HEALTHY, timeline.current(), elapsedMs(started));
        }
        if (timeline.isEmpty()) {
            timeline.observe(currentStage(project, sessionLog), elapsedMs(started));
        }
        long elapsed = elapsedMs(started);
        writeTimeline(sessionLog, timeline);
        if (err != null) {
            err.println(timeoutMessage(limit, elapsed, timeline, project, sessionLog));
        }
        return new Outcome(Kind.TIMED_OUT, timeline.current(), elapsed);
    }

    static String progressLine(Duration elapsed, String stage) {
        long millis = elapsed == null ? 0 : Math.max(0, elapsed.toMillis());
        String where = stage == null || stage.isBlank() ? GENERIC_STAGE : stage;
        return "session-start progress elapsed=" + millis + "ms stage=" + where;
    }

    static String currentStage(Path project, Path sessionLog) {
        StageHit hit = latestStage(logCandidates(project, sessionLog));
        return hit == null ? GENERIC_STAGE : hit.stage;
    }

    static String timeoutMessage(
            Duration limit,
            long elapsedMs,
            StageTimeline timeline,
            Path project,
            Path sessionLog
    ) {
        StageTimeline marks = timeline == null ? new StageTimeline() : timeline;
        String stage = marks.current();
        long shown = Math.max(0, elapsedMs);
        long seconds = limit == null ? DEFAULT_TIMEOUT.toSeconds() : limit.getSeconds();
        String budget = limit == null || limit.toMillis() >= 1000
                ? seconds + "s"
                : Math.max(0, limit.toMillis()) + "ms";
        StageHit hit = latestStage(logCandidates(project, sessionLog));
        Path relevant = hit != null && hit.file != null ? hit.file : sessionLog;
        StringBuilder text = new StringBuilder();
        text.append("Workbench session did not become healthy within ")
                .append(budget)
                .append(" (elapsed=")
                .append(shown)
                .append("ms stage=")
                .append(stage)
                .append(").")
                .append(System.lineSeparator());
        text.append("stages: ").append(marks.durations(shown)).append(System.lineSeparator());
        text.append("last-log:").append(System.lineSeparator());
        List<String> lines = tailLines(relevant, TAIL_LINES);
        if (lines.isEmpty()) {
            text.append("(no log output)").append(System.lineSeparator());
        } else {
            for (String line : lines) {
                text.append(line).append(System.lineSeparator());
            }
        }
        text.append("Raise the limit with -D")
                .append(START_TIMEOUT_PROPERTY)
                .append("=N, the environment variable ")
                .append(START_TIMEOUT_ENV)
                .append(", or a pkb_runvars member ")
                .append(START_TIMEOUT_PROPERTY)
                .append(".")
                .append(System.lineSeparator());
        text.append("See ").append(sessionLog == null ? relevant : sessionLog);
        return text.toString();
    }

    static List<Path> logCandidates(Path project, Path sessionLog) {
        List<Path> files = new ArrayList<>();
        if (sessionLog != null) files.add(sessionLog);
        if (project == null) return files;
        Path logs;
        try {
            logs = PickleballLocalLayout.workbenchStateRoot(project).resolve("logs");
        } catch (RuntimeException failure) {
            return files;
        }
        if (!Files.isDirectory(logs)) return files;
        try (var stream = Files.list(logs)) {
            for (Path log : stream.toList()) {
                if (!Files.isRegularFile(log)) continue;
                String name = log.getFileName().toString();
                if (name.endsWith(".err.log") || name.startsWith("worker-")) files.add(log);
            }
        } catch (IOException ignored) {
            // A missing logs directory still leaves the session log.
        }
        return files;
    }

    static StageHit latestStage(List<Path> files) {
        if (files == null || files.isEmpty()) return null;
        StageHit best = null;
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) continue;
            String stage = lastStage(file);
            if (stage == null) continue;
            long modified = lastModified(file);
            if (best == null || modified > best.modifiedMillis
                    || (modified == best.modifiedMillis && file.toString().compareTo(best.file.toString()) > 0)) {
                best = new StageHit(stage, file, modified);
            }
        }
        return best;
    }

    static List<String> tailLines(Path file, int max) {
        String text = tailText(file);
        if (text.isBlank()) return List.of();
        String[] parts = text.split("\\R", -1);
        int end = parts.length;
        if (end > 0 && parts[end - 1].isEmpty()) end--;
        int start = Math.max(0, end - Math.max(1, max));
        List<String> lines = new ArrayList<>();
        for (int i = start; i < end; i++) lines.add(parts[i]);
        return lines;
    }

    private static String lastStage(Path file) {
        String text = tailText(file);
        if (text.isEmpty()) return null;
        Matcher matcher = STAGE_LINE.matcher(text);
        String stage = null;
        while (matcher.find()) stage = matcher.group(1);
        return stage;
    }

    private static String tailText(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "";
        try {
            long size = Files.size(file);
            if (size <= 0) return "";
            int take = (int) Math.min(size, TAIL_BYTES);
            byte[] buf = new byte[take];
            try (var channel = Files.newByteChannel(file)) {
                channel.position(size - take);
                int read = 0;
                while (read < take) {
                    int n = channel.read(ByteBuffer.wrap(buf, read, take - read));
                    if (n < 0) break;
                    read += n;
                }
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            return "";
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException failure) {
            return Long.MIN_VALUE;
        }
    }

    static void writeTimeline(Path sessionLog, StageTimeline timeline) {
        if (sessionLog == null || timeline == null || timeline.isEmpty()) return;
        Path parent = sessionLog.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
            Files.writeString(
                    parent.resolve(PROGRESS_FILE),
                    timeline.toJson(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE
            );
        } catch (IOException ignored) {
            // A progress file must not hide the startup error.
        }
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1, duration.toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Workbench session.");
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String compactValue(String compact, String key) {
        if (compact == null || compact.isBlank() || key == null) return null;
        for (String part : compact.split(",")) {
            String token = part.trim();
            int equals = token.indexOf('=');
            if (equals <= 0) continue;
            if (key.equals(token.substring(0, equals).trim())) {
                return token.substring(equals + 1).trim();
            }
        }
        return null;
    }

    record StageHit(String stage, Path file, long modifiedMillis) {
    }

    static final class StageTimeline {
        private final List<Mark> marks = new ArrayList<>();

        boolean observe(String stage, long elapsedMs) {
            String name = stage == null || stage.isBlank() ? GENERIC_STAGE : stage;
            if (!marks.isEmpty() && marks.get(marks.size() - 1).stage.equals(name)) return false;
            marks.add(new Mark(name, Math.max(0, elapsedMs)));
            return true;
        }

        boolean isEmpty() {
            return marks.isEmpty();
        }

        String current() {
            if (marks.isEmpty()) return GENERIC_STAGE;
            return marks.get(marks.size() - 1).stage;
        }

        String durations(long elapsedMs) {
            if (marks.isEmpty()) return GENERIC_STAGE + "=" + Math.max(0, elapsedMs) + "ms";
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < marks.size(); i++) {
                long end = i + 1 < marks.size() ? marks.get(i + 1).enteredMs : Math.max(marks.get(i).enteredMs, elapsedMs);
                long duration = Math.max(0, end - marks.get(i).enteredMs);
                if (!text.isEmpty()) text.append("; ");
                text.append(marks.get(i).stage).append("=").append(duration).append("ms");
            }
            return text.toString();
        }

        String toJson() {
            StringBuilder text = new StringBuilder();
            text.append("{\"stages\":[");
            for (int i = 0; i < marks.size(); i++) {
                if (i > 0) text.append(",");
                text.append("{\"stage\":\"")
                        .append(jsonEscape(marks.get(i).stage))
                        .append("\",\"enteredElapsedMs\":")
                        .append(marks.get(i).enteredMs)
                        .append("}");
            }
            text.append("]}");
            return text.toString();
        }

        private static String jsonEscape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private record Mark(String stage, long enteredMs) {
        }
    }
}
