package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Run-scoped files under {@code .pickleball}. Date-stamped logs stay size-capped. */
final class AgentStore {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long CAP_BYTES = 1_048_576L;

    private AgentStore() {
    }

    static Path root(Path project, String runId) {
        Path base = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        if (runId == null || runId.isBlank()) {
            return base.resolve(".pickleball").resolve("agent");
        }
        return base.resolve(".pickleball").resolve("runs").resolve(runId).resolve("agent");
    }

    static Path ensure(Path dir) throws IOException {
        Files.createDirectories(dir);
        return dir;
    }

    static void appendCapped(Path file, String line) throws IOException {
        ensure(file.getParent());
        byte[] bytes = (line.endsWith("\n") ? line : line + "\n").getBytes(StandardCharsets.UTF_8);
        long size = Files.isRegularFile(file) ? Files.size(file) : 0L;
        if (size + bytes.length > CAP_BYTES) {
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            Path rolled = file.resolveSibling(file.getFileName() + "." + day + ".1");
            if (!Files.isRegularFile(rolled) || Files.size(rolled) + bytes.length <= CAP_BYTES) {
                file = rolled;
            } else {
                return;
            }
        }
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    static List<String> readLines(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) lines.add(line);
        }
        return lines;
    }

    static String now() {
        return Instant.now().toString();
    }
}
