package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable copy of project output for one input fingerprint.
 * Warm JVMs run from {@code warm/gen-<hash>/{classes,test-classes}}, never
 * from a live {@code target/} or {@code build/} tree. Copies, not hardlinks.
 */
public final class WarmGeneration {
    public static final String TEST_CLASSES_PROPERTY = "pkb_workbench_test_classes";
    public static final String DIRECTORY = "warm";
    private static final String ACTIVE = "active-generation.txt";
    private static final String JVM_GENERATION = "jvm-generation";
    private static final String IN_USE = "in-use";
    private static final String DEFERRED = "gc-deferred.txt";
    private static final String COMPLETE = ".complete";

    public record Snapshot(String hash, Path classes, Path testClasses, long files, long bytes, long millis) {
    }

    private WarmGeneration() {
    }

    public static final class Lock implements AutoCloseable {
        private final FileChannel channel;

        private Lock(FileChannel channel) {
            this.channel = channel;
        }

        public static Lock open(Path stateRoot) throws IOException {
            Path file = warm(stateRoot).resolve("sync.lock");
            Files.createDirectories(file.getParent());
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            channel.lock();
            return new Lock(channel);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    public static Path warm(Path stateRoot) {
        return stateRoot.resolve(DIRECTORY);
    }

    public static Snapshot materialize(Path project, Path stateRoot, SplitInputFingerprint.Parts parts) throws IOException {
        long started = System.nanoTime();
        String hash = SplitInputFingerprint.generationHash(parts);
        Path gen = warm(stateRoot).resolve("gen-" + hash);
        Path classes = gen.resolve("classes");
        Path testClasses = gen.resolve("test-classes");
        long files = 0;
        long bytes = 0;
        Path marker = gen.resolve(COMPLETE);
        if (!Files.isRegularFile(marker)) {
            Files.createDirectories(gen);
            Path mainSource = mainClasses(project);
            Path testSource = testClasses(project);
            Path gradleMainResources = project.resolve("build").resolve("resources").resolve("main");
            Path gradleTestResources = project.resolve("build").resolve("resources").resolve("test");
            long[] main = copyOutput(mainSource, classes);
            long[] mainResources = sameDirectory(mainSource, gradleMainResources) ? new long[]{0, 0} : copyOutput(gradleMainResources, classes);
            long[] test = copyOutput(testSource, testClasses);
            long[] testResources = sameDirectory(testSource, gradleTestResources) ? new long[]{0, 0} : copyOutput(gradleTestResources, testClasses);
            files = main[0] + mainResources[0] + test[0] + testResources[0];
            bytes = main[1] + mainResources[1] + test[1] + testResources[1];
            Files.writeString(marker, "files=" + files + "\nbytes=" + bytes + "\n", StandardCharsets.UTF_8);
        } else {
            long[] counted = readCounts(marker);
            files = counted[0];
            bytes = counted[1];
        }
        long millis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        writeActive(stateRoot, hash, classes, testClasses);
        return new Snapshot(hash, classes, testClasses, files, bytes, millis);
    }

    public static Snapshot readActive(Path stateRoot) {
        if (stateRoot == null) return null;
        Path file = warm(stateRoot).resolve(ACTIVE);
        if (!Files.isRegularFile(file)) return null;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 3 || lines.get(0).isBlank()) return null;
            return new Snapshot(lines.get(0).trim(), Path.of(lines.get(1).trim()), Path.of(lines.get(2).trim()), 0, 0, 0);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    public static void writeJvmGeneration(Path stateRoot, String hash) {
        if (stateRoot == null || hash == null || hash.isBlank()) return;
        try {
            Path file = warm(stateRoot).resolve(JVM_GENERATION);
            Files.createDirectories(file.getParent());
            Files.writeString(file, hash.trim() + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The next launch treats a missing pin as unreferenced.
        }
    }

    public static String jvmGeneration(Path stateRoot) {
        if (stateRoot == null) return "";
        Path file = warm(stateRoot).resolve(JVM_GENERATION);
        if (!Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            return "";
        }
    }

    /** One hash per line. A listed generation is not deleted. */
    public static void markInUse(Path stateRoot, String hash) {
        if (stateRoot == null || hash == null || hash.isBlank()) return;
        try {
            Path file = warm(stateRoot).resolve(IN_USE);
            Files.createDirectories(file.getParent());
            String existing = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (existing.lines().anyMatch(line -> hash.equals(line.trim()))) return;
            Files.writeString(file, existing + hash.trim() + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // GC stays conservative only for hashes it can read.
        }
    }

    /**
     * Deletes generations nobody references. A delete that fails (a Windows
     * file still open) is recorded and tried again next time. It is never forced.
     */
    public static void gc(Path stateRoot, String currentHash) {
        if (stateRoot == null) return;
        Path warm = warm(stateRoot);
        if (!Files.isDirectory(warm)) return;
        Set<String> keep = referenced(stateRoot, currentHash);
        retryDeferred(stateRoot, keep);
        try (var children = Files.list(warm)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("gen-"))
                    .forEach(path -> {
                        String hash = path.getFileName().toString().substring("gen-".length());
                        if (keep.contains(hash)) return;
                        if (!deleteTree(path)) rememberDeferred(stateRoot, path);
                    });
        } catch (IOException ignored) {
            // The next command tries again.
        }
    }

    /**
     * Generation directories first, then dependency jars. Live {@code target/}
     * and {@code build/} output entries are dropped so a warm JVM cannot see them.
     */
    public static List<String> pinClasspath(Path project, List<String> entries, Path classes, Path testClasses) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        List<String> pinned = new ArrayList<>();
        if (testClasses != null) pinned.add(testClasses.toAbsolutePath().normalize().toString());
        if (classes != null) pinned.add(classes.toAbsolutePath().normalize().toString());
        if (entries == null) return List.copyOf(pinned);
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
            if (isProjectOutput(root, path)) continue;
            String text = path.toString();
            if (!pinned.contains(text)) pinned.add(text);
        }
        return List.copyOf(pinned);
    }

    private static void writeActive(Path stateRoot, String hash, Path classes, Path testClasses) throws IOException {
        Path file = warm(stateRoot).resolve(ACTIVE);
        Files.createDirectories(file.getParent());
        String body = hash + "\n" + classes.toAbsolutePath().normalize() + "\n" + testClasses.toAbsolutePath().normalize() + "\n";
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private static Set<String> referenced(Path stateRoot, String currentHash) {
        Set<String> keep = new HashSet<>();
        if (currentHash != null && !currentHash.isBlank()) keep.add(currentHash.trim());
        Path inUse = warm(stateRoot).resolve(IN_USE);
        if (Files.isRegularFile(inUse)) {
            try {
                for (String line : Files.readAllLines(inUse, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) keep.add(line.trim());
                }
            } catch (IOException ignored) {
                // An unreadable pin is not a license to delete.
            }
        }
        String jvm = jvmGeneration(stateRoot);
        if (!jvm.isBlank()) {
            long pid = WarmProcess.read(stateRoot).map(WarmProcess.Handle::pid).orElse(WarmProcess.rememberedPid(stateRoot));
            if (WarmProcess.alive(pid)) keep.add(jvm);
        }
        for (WarmSlot.Member member : WarmSlot.list(stateRoot)) {
            if (!WarmProcess.alive(member.handle().pid())) continue;
            String generation = member.handle().generation();
            if (generation != null && !generation.isBlank()) keep.add(generation);
        }
        return keep;
    }

    private static void retryDeferred(Path stateRoot, Set<String> keep) {
        Path file = warm(stateRoot).resolve(DEFERRED);
        if (!Files.isRegularFile(file)) return;
        List<String> left = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Path path = Path.of(line.trim());
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                String hash = name.startsWith("gen-") ? name.substring("gen-".length()) : "";
                if (keep.contains(hash) || !deleteTree(path)) left.add(line.trim());
            }
            if (left.isEmpty()) Files.deleteIfExists(file);
            else Files.writeString(file, String.join("\n", left) + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Leave the file for the next command.
        }
    }

    private static void rememberDeferred(Path stateRoot, Path generation) {
        try {
            Path file = warm(stateRoot).resolve(DEFERRED);
            String existing = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            String text = generation.toAbsolutePath().normalize().toString();
            if (existing.contains(text)) return;
            Files.writeString(file, existing + text + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The directory remains and the next walk sees it.
        }
    }

    private static boolean deleteTree(Path root) {
        if (!Files.exists(root)) return true;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private static long[] copyOutput(Path source, Path dest) throws IOException {
        if (source == null || !Files.isDirectory(source)) return new long[]{0, 0};
        Files.createDirectories(dest);
        long[] totals = new long[]{0, 0};
        try (var walk = Files.walk(source)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path target = dest.resolve(source.relativize(file));
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                totals[0]++;
                totals[1] += Files.size(target);
            }
        }
        return totals;
    }

    private static boolean sameDirectory(Path left, Path right) {
        if (left == null || right == null) return false;
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private static long[] readCounts(Path marker) {
        long files = 0;
        long bytes = 0;
        try {
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                if (line.startsWith("files=")) files = Long.parseLong(line.substring("files=".length()).trim());
                if (line.startsWith("bytes=")) bytes = Long.parseLong(line.substring("bytes=".length()).trim());
            }
        } catch (IOException | NumberFormatException ignored) {
            return new long[]{0, 0};
        }
        return new long[]{files, bytes};
    }

    static Path mainClasses(Path project) {
        Path root = project.toAbsolutePath().normalize();
        Path maven = root.resolve("target").resolve("classes");
        if (Files.isDirectory(maven)) return maven;
        Path gradle = root.resolve("build").resolve("classes").resolve("java").resolve("main");
        if (Files.isDirectory(gradle)) return gradle;
        Path resources = root.resolve("build").resolve("resources").resolve("main");
        return Files.isDirectory(resources) ? resources : maven;
    }

    static Path testClasses(Path project) {
        Path root = project.toAbsolutePath().normalize();
        Path maven = root.resolve("target").resolve("test-classes");
        if (Files.isDirectory(maven)) return maven;
        Path gradle = root.resolve("build").resolve("classes").resolve("java").resolve("test");
        if (Files.isDirectory(gradle)) return gradle;
        Path resources = root.resolve("build").resolve("resources").resolve("test");
        return Files.isDirectory(resources) ? resources : maven;
    }

    private static boolean isProjectOutput(Path root, Path path) {
        return under(path, root.resolve("target").resolve("classes"))
                || under(path, root.resolve("target").resolve("test-classes"))
                || under(path, root.resolve("build").resolve("classes"))
                || under(path, root.resolve("build").resolve("resources"));
    }

    private static boolean under(Path path, Path dir) {
        Path base = dir.toAbsolutePath().normalize();
        return path.startsWith(base);
    }
}
