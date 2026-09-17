package tools.dscode.launcher;

import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.PickleballArtifactLocator;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.control.protocol.PickleballVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Extracts the version-matched, controller-only Workbench payload from the
 * consumer's Pickleball dependency and always launches it in a separate JVM.
 *
 * <p>The outer Pickleball JAR is executable ({@code java -jar pickleball-<ver>.jar})
 * and this class is its {@code Main-Class}. It never merges the nested controller
 * classpath into this JVM.</p>
 *
 * <p>Agent-facing Discover/hint/export-guidance/confirm run in this consumer JVM
 * so they can reuse DiagnosticCli and wrap Maven. Isolate/session-start,
 * execute-step, status, events, and stop/kill are one-shot HTTP clients against
 * a detached controller session. UI, MCP, and sync still extract and
 * forward to the controller JAR.</p>
 */
public final class PickleballWorkbenchLauncher {
    /**
     * Safety cap for the opaque thin controller JAR. Fat-jar regression guard:
     * the nested payload is Workbench plus protocol only, so 32 MiB is enough.
     */
    static final long MAX_PAYLOAD_BYTES = 32L * 1024 * 1024;

    static final String WORKBENCH_MAIN_CLASS = "tools.dscode.workbench.WorkbenchApplication";
    static final String OPEN_BOOTSTRAP_ENV = "PKB_OPEN_BOOTSTRAP";
    static final String REEXEC_ENV = "PKB_PICKLEBALL_REEXEC";

    private PickleballWorkbenchLauncher() {
    }

    public static void main(String[] args) {
        WorkbenchCommandLine.Parsed parsed;
        try {
            parsed = WorkbenchCommandLine.parse(args);
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            System.exit(2);
            return;
        }
        Path project = parsed.project() != null
                ? parsed.project()
                : PickleballLocalLayout.findProjectRoot(Path.of(""));
        try {
            Optional<Path> hop = reexecJar(project);
            if (hop.isPresent()) {
                int exitCode = reexec(hop.get(), args);
                if (exitCode != 0) System.exit(exitCode);
                return;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            System.err.println("Pickleball Workbench launcher was interrupted.");
            System.exit(1);
            return;
        } catch (IOException failure) {
            System.err.println("Could not hop to the pinned Pickleball jar: " + failure.getMessage());
            System.exit(1);
            return;
        }

        if (!WorkbenchCommandLine.isAgentCoreCommand(parsed.command())
                || !"export-guidance".equals(parsed.command())) {
            PickleballLocalStore.ensureQuietly(project);
        }

        if (WorkbenchCommandLine.isAgentCoreCommand(parsed.command())) {
            int exitCode = WorkbenchAgentCommands.run(args, System.out, System.err);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }
        if (WorkbenchCommandLine.isSessionClientCommand(parsed.command())) {
            int exitCode = WorkbenchSessionCommands.run(args, System.out, System.err);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }

        String[] forwarded = parsed.forwarded() != null ? parsed.forwarded() : normalizedArguments(args);
        if (parsed.project() == null) {
            project = projectRoot(forwarded);
            PickleballLocalStore.ensureQuietly(project);
        }
        try {
            Path controllerJar = extractEmbeddedPayload(project);
            Process process = new ProcessBuilder(command(controllerJar, forwarded))
                    .directory(project.toFile())
                    .inheritIO()
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) System.exit(exitCode);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            System.err.println("Pickleball Workbench launcher was interrupted.");
            System.exit(1);
        } catch (RuntimeException | IOException failure) {
            System.err.println("Could not launch Pickleball Workbench: " + failure.getMessage());
            System.exit(1);
        }
    }

    /**
     * Open-script bootstrap: if {@code current.json} pins another complete
     * version whose jar is in Maven local / Gradle cache, hop to that jar.
     * Direct {@code java -jar pickleball-X.jar} (no bootstrap env) stays on X
     * so version testing can switch the pin.
     */
    static Optional<Path> reexecJar(Path projectRoot) {
        return reexecJar(
                projectRoot,
                PickleballVersion.running(PickleballWorkbenchLauncher.class),
                "1".equals(System.getenv(OPEN_BOOTSTRAP_ENV)),
                "1".equals(System.getenv(REEXEC_ENV)),
                PickleballArtifactLocator.Repositories.discover()
        );
    }

    static Optional<Path> reexecJar(
            Path projectRoot,
            String runningVersion,
            boolean bootstrap,
            boolean alreadyReexec,
            PickleballArtifactLocator.Repositories repositories
    ) {
        if (alreadyReexec || !bootstrap || projectRoot == null) return Optional.empty();
        Path pickleball = PickleballLocalLayout.root(projectRoot);
        var current = PickleballLocalLayout.readCurrent(pickleball);
        if (current.isPresent() && current.get().usable()) {
            String pinned = current.get().pickleballVersion();
            if (pinned.equals(runningVersion)) return Optional.empty();
            return PickleballArtifactLocator.find(pinned, repositories);
        }
        Optional<PickleballArtifactLocator.Located> latest = PickleballArtifactLocator.findLatest(repositories);
        if (latest.isEmpty()) return Optional.empty();
        if (latest.get().version().equals(runningVersion)) return Optional.empty();
        return Optional.of(latest.get().jar());
    }

    static int reexec(Path jar, String[] args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(jar.toAbsolutePath().normalize().toString());
        if (args != null) command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        builder.environment().put(REEXEC_ENV, "1");
        return builder.start().waitFor();
    }

    static Path extractEmbeddedPayload(Path projectRoot) throws IOException {
        ClassLoader loader = PickleballWorkbenchLauncher.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(ControlProtocol.EMBEDDED_WORKBENCH_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Pickleball artifact is missing " + ControlProtocol.EMBEDDED_WORKBENCH_RESOURCE
                );
            }
            return extractPayload(projectRoot, input);
        }
    }

    static Path extractPayload(Path projectRoot, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Workbench payload must not be empty.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("Embedded Workbench payload exceeds the safety limit.");
        }
        return extractPayload(projectRoot, new ByteArrayInputStream(payload));
    }

    static Path extractPayload(Path projectRoot, InputStream payload) throws IOException {
        if (payload == null) {
            throw new IllegalArgumentException("Workbench payload must not be empty.");
        }
        Path project = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) {
            throw new IllegalArgumentException(
                    "Consumer project directory does not exist: " + project
            );
        }
        Path staging = PickleballLocalLayout.workbenchStateRoot(project).resolve("controller");
        Files.createDirectories(staging);
        Path temporary = Files.createTempFile(staging, "pickleball-workbench-", ".tmp");
        try {
            HashedCopy copy = copyAndHash(payload, temporary);
            if (copy.bytes == 0) {
                throw new IllegalArgumentException("Workbench payload must not be empty.");
            }
            Path directory = staging.resolve(copy.checksum);
            Path target = directory.resolve("pickleball-workbench.jar");
            Files.createDirectories(directory);

            if (Files.isRegularFile(target) && copy.checksum.equals(sha256File(target))) {
                return target;
            }

            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!copy.checksum.equals(sha256File(target))) {
                throw new IllegalStateException("Extracted Workbench payload failed checksum verification.");
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Single fork seam for ui/mcp/sync/isolate session-start. Builds
     * {@code java -cp <thinJar:libs...> tools.dscode.workbench.WorkbenchApplication <args>}.
     */
    static List<String> command(Path controllerJar, String[] args) {
        Path jar = controllerJar.toAbsolutePath().normalize();
        WorkbenchRuntimeLibs.Manifest manifest = WorkbenchRuntimeLibs.withLaunchClassifier(
                WorkbenchRuntimeLibs.readManifest(jar),
                WorkbenchRuntimeLibs.platformKey()
        );
        Path libCache = WorkbenchRuntimeLibs.libCacheForController(jar, manifest.version());
        List<Path> libs = WorkbenchRuntimeLibs.resolve(manifest, libCache);

        StringBuilder classpath = new StringBuilder(jar.toString());
        for (Path lib : libs) {
            classpath.append(java.io.File.pathSeparator).append(lib.toAbsolutePath().normalize());
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(classpath.toString());
        command.add(WORKBENCH_MAIN_CLASS);
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    static String[] normalizedArguments(String[] args) {
        return WorkbenchCommandLine.parse(args).forwarded();
    }

    private static Path projectRoot(String[] args) {
        if (args.length >= 2
                && WorkbenchCommandLine.isForwardedCommand(args[0])
                && !args[1].isBlank()
                && !args[1].startsWith("-")) {
            return Path.of(args[1]).toAbsolutePath().normalize();
        }
        return PickleballLocalLayout.findProjectRoot(Path.of(""));
    }

    static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static HashedCopy copyAndHash(InputStream input, Path target) throws IOException {
        MessageDigest digest = sha256Digest();
        long written = 0;
        try (DigestInputStream digested = new DigestInputStream(input, digest);
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = digested.read(buffer)) >= 0) {
                written += read;
                if (written > MAX_PAYLOAD_BYTES) {
                    throw new IllegalStateException("Embedded Workbench payload exceeds the safety limit.");
                }
                output.write(buffer, 0, read);
            }
        }
        return new HashedCopy(written, HexFormat.of().formatHex(digest.digest()));
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream digested = new DigestInputStream(input, digest)) {
            digested.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private record HashedCopy(long bytes, String checksum) {
    }
}
