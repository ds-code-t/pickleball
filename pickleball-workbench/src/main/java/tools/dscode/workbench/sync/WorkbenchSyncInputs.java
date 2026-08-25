package tools.dscode.workbench.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Input-side fingerprints used to decide whether Workbench can skip the wrapper
 * or refresh resources without a full test-compile.
 *
 * <p>These are distinct from {@link WorkbenchManifest#fingerprint()}, which
 * remains output provenance over merged classes plus dependency artifact bytes.</p>
 */
public record WorkbenchSyncInputs(
        String javaFingerprint,
        String resourceFingerprint,
        String buildFingerprint,
        String dependencyFingerprint,
        List<Path> sourceRoots
) {
    public WorkbenchSyncInputs {
        javaFingerprint = javaFingerprint == null ? "" : javaFingerprint;
        resourceFingerprint = resourceFingerprint == null ? "" : resourceFingerprint;
        buildFingerprint = buildFingerprint == null ? "" : buildFingerprint;
        dependencyFingerprint = dependencyFingerprint == null ? "" : dependencyFingerprint;
        sourceRoots = List.copyOf(sourceRoots == null ? List.of() : sourceRoots);
    }

    WorkbenchSyncInputs withDependencyFingerprint(String value) {
        return new WorkbenchSyncInputs(
                javaFingerprint, resourceFingerprint, buildFingerprint, value, sourceRoots
        );
    }

    static WorkbenchSyncInputs capture(WorkbenchProject project, WorkbenchManifest previous) {
        return capture(
                project,
                resolveSourceRoots(project, previous),
                previous == null ? List.of() : previous.dependencyClasspath()
        );
    }

    static WorkbenchSyncInputs capture(
            WorkbenchProject project,
            List<Path> sourceRoots,
            List<String> dependencies
    ) {
        List<Path> roots = sourceRoots == null || sourceRoots.isEmpty()
                ? resolveSourceRoots(project, null)
                : sourceRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        List<Path> javaFiles = new ArrayList<>();
        List<Path> resourceFiles = new ArrayList<>();
        for (Path root : roots) {
            classify(root, javaFiles, resourceFiles);
        }
        return new WorkbenchSyncInputs(
                fingerprintFiles(project.root(), javaFiles),
                fingerprintFiles(project.root(), resourceFiles),
                fingerprintFiles(project.root(), buildFiles(project)),
                fingerprintDependencies(dependencies),
                roots
        );
    }

    static String fingerprintDependencies(List<String> dependencies) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> ordered = dependencies == null
                    ? List.of()
                    : dependencies.stream().sorted().toList();
            for (String dependency : ordered) {
                if (dependency == null || dependency.isBlank()) continue;
                Path path = Path.of(dependency).toAbsolutePath().normalize();
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(path)) {
                    updateFileDigest(digest, path);
                } else if (Files.isDirectory(path)) {
                    updateDirectoryDigest(digest, path);
                } else {
                    digest.update("MISSING".getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint Workbench dependency inputs.", failure);
        }
    }

    static List<Path> resolveSourceRoots(WorkbenchProject project, WorkbenchManifest previous) {
        if (previous != null && previous.sourceRoots() != null && !previous.sourceRoots().isEmpty()) {
            return previous.sourceRoots().stream()
                    .map(value -> Path.of(value).toAbsolutePath().normalize())
                    .toList();
        }
        Path root = project.root();
        return List.of(
                root.resolve("src/main/java"),
                root.resolve("src/test/java"),
                root.resolve("src/main/resources"),
                root.resolve("src/test/resources")
        );
    }

    static List<Path> buildFiles(WorkbenchProject project) {
        List<Path> files = new ArrayList<>();
        Path root = project.root();
        Path buildRoot = project.buildRoot();
        if (project.type() == WorkbenchProject.Type.MAVEN) {
            addIfFile(files, root.resolve("pom.xml"));
            addIfFile(files, root.resolve(".mvn/maven.config"));
            addIfFile(files, root.resolve(".mvn/jvm.config"));
        } else {
            addIfFile(files, root.resolve("build.gradle"));
            addIfFile(files, root.resolve("build.gradle.kts"));
            addIfFile(files, root.resolve("gradle.properties"));
            addIfFile(files, buildRoot.resolve("settings.gradle"));
            addIfFile(files, buildRoot.resolve("settings.gradle.kts"));
            addIfFile(files, buildRoot.resolve("gradle.properties"));
            addIfFile(files, buildRoot.resolve("gradle/libs.versions.toml"));
            addIfFile(files, buildRoot.resolve("gradle/wrapper/gradle-wrapper.properties"));
        }
        return List.copyOf(files);
    }

    static boolean compiledOutputsPresent(WorkbenchManifest previous) {
        if (previous == null || previous.outputRoots() == null || previous.outputRoots().isEmpty()) {
            return false;
        }
        long previousLiveClasses = countClassFiles(previous.liveOutputPath());
        long currentOutputClasses = 0;
        for (WorkbenchManifest.OutputRoot output : previous.outputRoots()) {
            Path path = Path.of(output.path()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                return previousLiveClasses == 0;
            }
            currentOutputClasses += countClassFiles(path);
        }
        if (previousLiveClasses == 0) return true;
        return currentOutputClasses >= previousLiveClasses;
    }

    static long countClassFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) return 0;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    static boolean snapshotReady(Path stateRoot) {
        return Files.isDirectory(stateRoot.resolve("live").resolve("classes"))
                && Files.isRegularFile(stateRoot.resolve("classpath.txt"))
                && Files.isRegularFile(stateRoot.resolve("manifest.json"));
    }

    private static void classify(Path root, List<Path> javaFiles, List<Path> resourceFiles) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
                    javaFiles.add(file);
                } else {
                    resourceFiles.add(file);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Could not scan Workbench source root: " + root, failure);
        }
    }

    private static void addIfFile(List<Path> files, Path path) {
        if (Files.isRegularFile(path)) files.add(path.toAbsolutePath().normalize());
    }

    private static String fingerprintFiles(Path projectRoot, List<Path> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path root = projectRoot.toAbsolutePath().normalize();
            List<Path> ordered = files.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(path -> relativeKey(root, path)))
                    .toList();
            for (Path file : ordered) {
                digest.update(relativeKey(root, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (Files.isRegularFile(file)) {
                    updateFileDigest(digest, file);
                } else {
                    digest.update("MISSING".getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint Workbench source inputs.", failure);
        }
    }

    private static String relativeKey(Path root, Path file) {
        if (file.startsWith(root)) {
            return root.relativize(file).toString().replace('\\', '/');
        }
        return file.toString().replace('\\', '/');
    }

    private static void updateDirectoryDigest(MessageDigest digest, Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                updateFileDigest(digest, file);
                digest.update((byte) 0);
            }
        }
    }

    private static void updateFileDigest(MessageDigest digest, Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }
}
