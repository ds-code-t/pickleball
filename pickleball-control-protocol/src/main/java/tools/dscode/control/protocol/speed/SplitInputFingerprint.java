package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Split input fingerprints for a launcher cache hit.
 * Resources, Java, and build files are separate. A file contributes its
 * relative path, size, and mtime. A file whose mtime is within two seconds
 * of now also contributes a content hash, so a same-size rewrite in that
 * window is visible. Once that content token is stored, it stays the token
 * for that size and mtime, including after the window closes. The same stamp
 * is what {@code WorkbenchSyncInputs} uses for a one-shot digest.
 */
public final class SplitInputFingerprint {
    public static final String FILE_NAME = "input-fingerprints.v1";
    public static final String TOKEN_FILE = "input-fingerprint-tokens.v1";
    public static final long RACY_WINDOW_MS = 2_000L;

    public static final List<String> JAVA_ROOTS = List.of("src/main/java", "src/test/java");
    public static final List<String> RESOURCE_ROOTS = List.of("src/main/resources", "src/test/resources");

    public enum Mode {
        SKIP("skip"),
        RESOURCES("resources"),
        FULL("full");

        public final String wire;

        Mode(String wire) {
            this.wire = wire;
        }
    }

    public record Parts(String resources, String java, String build) {
        public Parts {
            resources = resources == null ? "" : resources;
            java = java == null ? "" : java;
            build = build == null ? "" : build;
        }
    }

    private SplitInputFingerprint() {
    }

    public static Parts capture(Path project) {
        return capture(project, System.currentTimeMillis());
    }

    public static Parts capture(Path project, long nowMillis) {
        Path root = project == null ? Path.of("") : project.toAbsolutePath().normalize();
        return new Parts(
                digestTree(root, RESOURCE_ROOTS, nowMillis),
                digestTree(root, JAVA_ROOTS, nowMillis),
                InputFingerprint.lockHash(root)
        );
    }

    /**
     * Missing stored parts are a skip: the caller stores the current parts
     * and does not launch a wrapper. A later edit is then visible.
     */
    public static Mode decide(Parts stored, Parts current) {
        if (stored == null || current == null) return Mode.SKIP;
        if (!stored.build().equals(current.build()) || !stored.java().equals(current.java())) {
            return Mode.FULL;
        }
        if (!stored.resources().equals(current.resources())) return Mode.RESOURCES;
        return Mode.SKIP;
    }

    public static String generationHash(Parts parts) {
        MessageDigest digest = sha256();
        if (parts != null) {
            digest.update(parts.resources().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(parts.java().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(parts.build().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static Path file(Path stateRoot) {
        return stateRoot.resolve(FILE_NAME);
    }

    public static Parts load(Path stateRoot) {
        if (stateRoot == null) return null;
        Path path = file(stateRoot);
        if (!Files.isRegularFile(path)) return null;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() < 4 || !"v1".equals(lines.get(0).trim())) return null;
            return new Parts(field(lines.get(1), "resources"), field(lines.get(2), "java"), field(lines.get(3), "build"));
        } catch (IOException failure) {
            return null;
        }
    }

    public static void store(Path stateRoot, Parts parts) throws IOException {
        if (stateRoot == null || parts == null) {
            throw new IllegalArgumentException("Fingerprints need a state directory.");
        }
        Files.createDirectories(stateRoot);
        String body = "v1\nresources " + parts.resources() + "\njava " + parts.java() + "\nbuild " + parts.build() + "\n";
        Path path = file(stateRoot);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, body, StandardCharsets.UTF_8);
        try {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Fingerprints plus the tokens that produced them. A content token recorded
     * inside the racy window is reused for that same size and mtime, so the
     * hash does not change when the window later closes.
     */
    public static final class Observation {
        private final Parts parts;
        private final String tokens;

        Observation(Parts parts, String tokens) {
            this.parts = parts;
            this.tokens = tokens == null ? "v1\n" : tokens;
        }

        public Parts parts() {
            return parts;
        }
    }

    public static Observation observe(Path project, Path stateRoot) {
        return observe(project, stateRoot, System.currentTimeMillis());
    }

    public static Observation observe(Path project, Path stateRoot, long nowMillis) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        Map<String, SavedToken> saved = loadTokens(stateRoot);
        StringBuilder body = new StringBuilder("v1\n");
        Parts parts = new Parts(
                digestStable(root, RESOURCE_ROOTS, nowMillis, saved, body, "resources"),
                digestStable(root, JAVA_ROOTS, nowMillis, saved, body, "java"),
                InputFingerprint.lockHash(root)
        );
        return new Observation(parts, body.toString());
    }

    public static void store(Path stateRoot, Observation observation) throws IOException {
        if (stateRoot == null || observation == null || observation.parts == null) {
            throw new IllegalArgumentException("Fingerprints need a state directory.");
        }
        Path tokens = stateRoot.resolve(TOKEN_FILE);
        Path temp = tokens.resolveSibling(tokens.getFileName() + ".tmp");
        Files.createDirectories(stateRoot);
        Files.writeString(temp, observation.tokens, StandardCharsets.UTF_8);
        try {
            Files.move(temp, tokens, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, tokens, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        store(stateRoot, observation.parts);
    }

    /** Path, size, mtime, and a content hash when the mtime is inside the racy window. */
    public static String digestFiles(Path projectRoot, List<Path> files) {
        return digestFiles(projectRoot, files, System.currentTimeMillis());
    }

    public static String digestFiles(Path projectRoot, List<Path> files, long nowMillis) {
        try {
            MessageDigest digest = sha256();
            Path root = projectRoot == null ? Path.of("").toAbsolutePath().normalize() : projectRoot.toAbsolutePath().normalize();
            List<Path> ordered = new ArrayList<>();
            if (files != null) {
                for (Path file : files) {
                    if (file != null) ordered.add(file.toAbsolutePath().normalize());
                }
            }
            ordered.sort(Comparator.comparing(path -> relativeKey(root, path)));
            for (Path file : ordered) {
                digest.update(relativeKey(root, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(file)) {
                    stampFile(digest, file, nowMillis);
                } else {
                    digest.update("MISSING".getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 1);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not fingerprint inputs.", failure);
        }
    }

    private static String digestTree(Path root, List<String> relatives, long nowMillis) {
        List<Path> files = new ArrayList<>();
        for (String relative : relatives) {
            collect(root.resolve(relative), files);
        }
        return digestFiles(root, files, nowMillis);
    }

    private static void collect(Path directory, List<Path> files) {
        if (!Files.isDirectory(directory)) return;
        try (var walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not scan " + directory, failure);
        }
    }

    private static void stampFile(MessageDigest digest, Path file, long nowMillis) throws IOException {
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        digest.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(mtime).getBytes(StandardCharsets.UTF_8));
        if (Math.abs(nowMillis - mtime) <= RACY_WINDOW_MS) {
            digest.update((byte) 0);
            digest.update(content(file).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String content(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relativeKey(Path root, Path file) {
        if (file.startsWith(root)) return root.relativize(file).toString().replace('\\', '/');
        return file.toString().replace('\\', '/');
    }

    private static String field(String line, String name) {
        String trimmed = line == null ? "" : line.trim();
        String prefix = name + " ";
        if (trimmed.startsWith(prefix)) return trimmed.substring(prefix.length()).trim();
        return trimmed;
    }

    private static String digestStable(
            Path root,
            List<String> relatives,
            long nowMillis,
            Map<String, SavedToken> saved,
            StringBuilder body,
            String section
    ) {
        try {
            List<Path> files = new ArrayList<>();
            for (String relative : relatives) collect(root.resolve(relative), files);
            files.sort(Comparator.comparing(path -> relativeKey(root, path)));
            MessageDigest digest = sha256();
            for (Path file : files) {
                String rel = relativeKey(root, file);
                long size = Files.size(file);
                long mtime = Files.getLastModifiedTime(file).toMillis();
                String key = size + ":" + mtime;
                SavedToken cached = saved.get(section + "\0" + rel);
                boolean outside = Math.abs(nowMillis - mtime) > RACY_WINDOW_MS;
                String token;
                long recordedAt;
                if (cached != null && cached.key.equals(key) && (outside || !racy(mtime, cached.recordedAt))) {
                    token = cached.token;
                    recordedAt = outside ? nowMillis : cached.recordedAt;
                } else if (cached != null && cached.key.equals(key)) {
                    String content = "c:" + content(file);
                    token = content.equals(cached.token) ? cached.token : content;
                    recordedAt = cached.recordedAt;
                } else if (!outside) {
                    token = "c:" + content(file);
                    recordedAt = nowMillis;
                } else {
                    token = "s:" + key;
                    recordedAt = nowMillis;
                }
                digest.update(rel.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(token.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1);
                body.append(section).append('\t')
                        .append(sanitize(rel)).append('\t')
                        .append(key).append('\t')
                        .append(token).append('\t')
                        .append(recordedAt).append('\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not fingerprint inputs.", failure);
        }
    }

    private static boolean racy(long mtime, long recordedAt) {
        long delta = recordedAt - mtime;
        return delta <= RACY_WINDOW_MS && delta >= -RACY_WINDOW_MS;
    }

    private static String sanitize(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private record SavedToken(String key, String token, long recordedAt) {
    }

    private static Map<String, SavedToken> loadTokens(Path stateRoot) {
        Map<String, SavedToken> saved = new HashMap<>();
        if (stateRoot == null) return saved;
        Path path = stateRoot.resolve(TOKEN_FILE);
        if (!Files.isRegularFile(path)) return saved;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !"v1".equals(lines.get(0).trim())) return saved;
            for (int i = 1; i < lines.size(); i++) {
                String[] fields = lines.get(i).split("\t", -1);
                if (fields.length != 5) continue;
                long recordedAt;
                try {
                    recordedAt = Long.parseLong(fields[4].trim());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                saved.put(fields[0] + "\0" + fields[1], new SavedToken(fields[2], fields[3], recordedAt));
            }
        } catch (IOException ignored) {
            return new HashMap<>();
        }
        return saved;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
