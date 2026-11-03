package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolved test classpath keyed by {@link InputFingerprint#lockHash}.
 * One file, shared by the launcher and the controller. A miss falls back
 * to a full wrapper resolve.
 */
public final class ClasspathCache {
    public static final String FILE_NAME = "resolved-classpath.v1";

    private ClasspathCache() {
    }

    public record Hit(boolean hit, String hash, List<String> entries) {
        public Hit {
            hash = hash == null ? "" : hash;
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }

    public static Path file(Path stateRoot) {
        return stateRoot.resolve(FILE_NAME);
    }

    public static Hit load(Path stateRoot, String expectedHash) {
        if (stateRoot == null || expectedHash == null || expectedHash.isBlank()) {
            return new Hit(false, "", List.of());
        }
        Path path = file(stateRoot);
        if (!Files.isRegularFile(path)) return new Hit(false, "", List.of());
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return new Hit(false, "", List.of());
        }
        if (lines.size() < 2 || !"v1".equals(lines.get(0).trim())) return new Hit(false, "", List.of());
        String hash = lines.get(1).trim();
        if (!expectedHash.equals(hash)) return new Hit(false, hash, List.of());
        List<String> entries = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;
            if (!Files.exists(Path.of(line))) return new Hit(false, hash, List.of());
            entries.add(line);
        }
        if (entries.isEmpty()) return new Hit(false, hash, List.of());
        return new Hit(true, hash, entries);
    }

    /**
     * A discover cache isolate can reuse. A directory under {@code target/} or
     * {@code build/} is the compiled output. A live-worker list is not.
     */
    public static boolean hasProjectOutput(Path project, List<String> entries) {
        if (project == null || entries == null || entries.isEmpty()) return false;
        Path root = project.toAbsolutePath().normalize();
        Path target = root.resolve("target");
        Path build = root.resolve("build");
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            Path path;
            try {
                path = Path.of(entry.trim());
            } catch (RuntimeException failure) {
                continue;
            }
            if (!path.isAbsolute()) path = root.resolve(path);
            try {
                path = path.toAbsolutePath().normalize();
            } catch (RuntimeException failure) {
                continue;
            }
            if (Files.isDirectory(path) && (path.startsWith(target) || path.startsWith(build))) return true;
        }
        return false;
    }

    public static void store(Path stateRoot, String hash, List<String> entries) throws IOException {
        if (stateRoot == null || hash == null || hash.isBlank() || entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Classpath cache needs a hash and at least one entry.");
        }
        Files.createDirectories(stateRoot);
        StringBuilder body = new StringBuilder();
        body.append("v1\n").append(hash.trim()).append('\n');
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            body.append(entry.trim()).append('\n');
        }
        Path path = file(stateRoot);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, body.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
