package tools.dscode.control.protocol.speed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Hash of the lockable build inputs. A content match skips Maven resolve.
 * Source trees use size plus last-modified time so a warm check stays cheap.
 */
public final class InputFingerprint {
    /** Pom, wrapper, and Gradle lock files. Missing files are skipped, not hashed as empty. */
    public static final List<String> LOCK_FILES = List.of(
            "pom.xml",
            ".mvn/wrapper/maven-wrapper.properties",
            ".mvn/maven.config",
            ".mvn/jvm.config",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties"
    );

    private static final List<String> SOURCE_ROOTS = List.of(
            "src/main/java",
            "src/test/java",
            "src/main/resources",
            "src/test/resources"
    );

    private InputFingerprint() {
    }

    /** Content hash of lock files that exist. */
    public static String lockHash(Path project) {
        return hash(project, false);
    }

    /** Lock-file bytes plus size and mtime of source files. Invalidates a warm JVM. */
    public static String watchHash(Path project) {
        return hash(project, true);
    }

    private static String hash(Path project, boolean includeSources) {
        MessageDigest digest = sha256();
        Path root = project == null ? Path.of("") : project.toAbsolutePath().normalize();
        for (String relative : LOCK_FILES) {
            Path file = root.resolve(relative);
            if (!Files.isRegularFile(file)) continue;
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digestFile(digest, file);
            digest.update((byte) 1);
        }
        if (includeSources) {
            for (String relative : SOURCE_ROOTS) {
                stampTree(digest, root.resolve(relative), root);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void stampTree(MessageDigest digest, Path directory, Path root) {
        if (!Files.isDirectory(directory)) return;
        try (var walk = Files.walk(directory)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
            files.sort(Path::compareTo);
            for (Path file : files) {
                String key = root.relativize(file).toString().replace('\\', '/');
                digest.update(key.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 1);
            }
        } catch (IOException failure) {
            digest.update("unreadable".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void digestFile(MessageDigest digest, Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        } catch (IOException failure) {
            digest.update("unreadable".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
