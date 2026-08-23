package tools.dscode.launcher;

import tools.dscode.control.protocol.ControlProtocol;

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
import java.util.Set;

/**
 * Extracts the version-matched, controller-only Workbench payload from the
 * consumer's Pickleball dependency and always launches it in a separate JVM.
 */
public final class PickleballWorkbenchLauncher {
    /**
     * Safety cap for the opaque controller JAR. OpenJFX WebView natives make
     * the payload larger than a plain Java executable, so this is a stream
     * limit rather than an in-memory buffer size.
     */
    static final long MAX_PAYLOAD_BYTES = 512L * 1024 * 1024;
    private static final Set<String> PROJECT_COMMANDS = Set.of(
            "sync", "status", "worker-check", "live-check", "ui", "mcp"
    );

    private PickleballWorkbenchLauncher() {
    }

    public static void main(String[] args) {
        String[] forwarded = normalizedArguments(args);
        Path project = projectRoot(forwarded);
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
        Path staging = project.resolve(".pickleball")
                .resolve("workbench")
                .resolve("controller");
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

    static List<String> command(Path controllerJar, String[] args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(controllerJar.toAbsolutePath().normalize().toString());
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    static String[] normalizedArguments(String[] args) {
        Path currentProject = Path.of("").toAbsolutePath().normalize();
        if (args == null || args.length == 0) {
            return new String[]{"ui", currentProject.toString()};
        }
        if (args.length == 1 && PROJECT_COMMANDS.contains(args[0])) {
            return new String[]{args[0], currentProject.toString()};
        }
        return args.clone();
    }

    private static Path projectRoot(String[] args) {
        if (args.length == 2
                && PROJECT_COMMANDS.contains(args[0])
                && !args[1].isBlank()) {
            return Path.of(args[1]).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path javaExecutable() {
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
