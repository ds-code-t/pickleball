package tools.dscode.engine.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code .pickleball/spine/warm.json} records the warm JVM pid and start time.
 */
public final class WarmProcessFile {
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");
    private static final Pattern STARTED = Pattern.compile("\"started-at\"\\s*:\\s*\"([^\"]*)\"");

    private WarmProcessFile() {
    }

    public static Path directory(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        return root.resolve(".pickleball").resolve("spine");
    }

    public static Path file(Path project) {
        return directory(project).resolve("warm.json");
    }

    public static void write(Path project, long pid, Instant startedAt) throws IOException {
        Path file = file(project);
        Files.createDirectories(file.getParent());
        String json = "{\"pid\":" + pid + ",\"started-at\":\"" + startedAt + "\"}\n";
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    public static Optional<Record> read(Path project) {
        Path file = file(project);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Matcher pid = PID.matcher(json);
            if (!pid.find()) return Optional.empty();
            Matcher started = STARTED.matcher(json);
            String at = started.find() ? started.group(1) : "";
            return Optional.of(new Record(Long.parseLong(pid.group(1)), at));
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    public static boolean alive(Path project) {
        return read(project).filter(record -> processAlive(record.pid())).isPresent();
    }

    public static boolean processAlive(long pid) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static void delete(Path project) {
        try {
            Files.deleteIfExists(file(project));
        } catch (IOException ignored) {
            // The next start overwrites a file that is still locked.
        }
    }

    public record Record(long pid, String startedAt) {
    }
}
