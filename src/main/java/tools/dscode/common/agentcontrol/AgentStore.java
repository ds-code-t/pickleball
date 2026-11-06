package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
        Path key = file.toAbsolutePath().normalize();
        synchronized (key.toString().intern()) {
            long size = Files.isRegularFile(file) ? Files.size(file) : 0L;
            if (size > 0 && size + bytes.length > CAP_BYTES) {
                int index = 1;
                Path rolled;
                do {
                    rolled = file.resolveSibling(file.getFileName().toString() + "." + index);
                    index++;
                } while (Files.exists(rolled));
                Files.move(file, rolled);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
    }

    static List<Path> group(Path file) throws IOException {
        List<Path> files = new ArrayList<>();
        Path parent = file.getParent();
        String name = file.getFileName().toString();
        if (parent != null && Files.isDirectory(parent)) {
            try (var stream = Files.list(parent)) {
                stream.filter(path -> rolledName(name, path.getFileName().toString()))
                        .sorted((left, right) -> Integer.compare(rollIndex(name, left), rollIndex(name, right)))
                        .forEach(files::add);
            }
        }
        if (Files.isRegularFile(file)) files.add(file);
        return files;
    }

    static List<String> readGroup(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path part : group(file)) lines.addAll(readLines(part));
        return lines;
    }

    private static boolean rolledName(String name, String candidate) {
        if (!candidate.startsWith(name + ".")) return false;
        String suffix = candidate.substring(name.length() + 1);
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    private static int rollIndex(String name, Path path) {
        String suffix = path.getFileName().toString().substring(name.length() + 1);
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException failure) {
            return 0;
        }
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
