package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK-only consumer {@code .pickleball} layout: a current-version pointer at the
 * root, version-specific trees under {@code v/<version>/}, and a legacy
 * unversioned fallback.
 *
 * <p>Workbench and Pickleball both use this class. It must stay dependency-free.
 */
public final class PickleballLocalLayout {
    public static final String DIRECTORY = ".pickleball";
    public static final String CURRENT_FILE = "current.json";
    public static final String VERSIONS_DIRECTORY = "v";
    public static final String OPEN_DIRECTORY = "open";
    public static final String WORKBENCH_DIRECTORY = "workbench";
    public static final String AGENT_GUIDE = "AGENT-GUIDE.md";
    public static final String GUIDANCE_MANIFEST = "GUIDANCE-MANIFEST.json";
    public static final String LAST_DISCOVER_FILE = "last-discover.json";
    public static final String CLI_SESSION_FILE = "cli-session.json";
    public static final String ATTACH_FILE = "attach.json";
    public static final String MATERIALIZE_LOCK = ".materialize.lock";
    public static final String OPEN_SCRIPT_UNIX = "pickleball-workbench.sh";
    public static final String OPEN_SCRIPT_CMD = "pickleball-workbench.cmd";
    public static final String OPEN_SCRIPT_POWERSHELL = "pickleball-workbench.ps1";

    private static final Pattern JSON_STRING = Pattern.compile(
            "\"pickleballVersion\"\\s*:\\s*\"([^\"]+)\""
    );
    private static final Pattern JSON_COMPLETE = Pattern.compile(
            "\"complete\"\\s*:\\s*(true|false)"
    );
    private static final Pattern JSON_UPDATED = Pattern.compile(
            "\"updatedAt\"\\s*:\\s*\"([^\"]+)\""
    );

    private PickleballLocalLayout() {
    }

    public record CurrentPointer(String pickleballVersion, boolean complete, String updatedAt) {
        public CurrentPointer {
            pickleballVersion = pickleballVersion == null ? "" : pickleballVersion.trim();
            updatedAt = updatedAt == null ? "" : updatedAt.trim();
        }

        public static CurrentPointer completeNow(String version) {
            return new CurrentPointer(version, true, Instant.now().toString());
        }

        public boolean usable() {
            return complete && isSafeVersion(pickleballVersion);
        }

        public String toJson() {
            return "{\n"
                    + "  \"pickleballVersion\": \"" + escape(pickleballVersion) + "\",\n"
                    + "  \"complete\": " + complete + ",\n"
                    + "  \"updatedAt\": \"" + escape(updatedAt) + "\"\n"
                    + "}\n";
        }
    }

    public static Path root(Path projectRoot) {
        return requireDirectoryHint(projectRoot).resolve(DIRECTORY);
    }

    public static Path currentFile(Path projectRoot) {
        return root(projectRoot).resolve(CURRENT_FILE);
    }

    public static Path openDirectory(Path projectRoot) {
        return root(projectRoot).resolve(OPEN_DIRECTORY);
    }

    public static Path materializeLock(Path pickleballRoot) {
        return pickleballRoot.toAbsolutePath().normalize().resolve(MATERIALIZE_LOCK);
    }

    public static Path versionRoot(Path pickleballRoot, String version) {
        return pickleballRoot.toAbsolutePath().normalize()
                .resolve(VERSIONS_DIRECTORY)
                .resolve(requireSafeVersion(version));
    }

    public static Path versionRootForProject(Path projectRoot, String version) {
        return versionRoot(root(projectRoot), version);
    }

    public static Path versionWorkbench(Path pickleballRoot, String version) {
        return versionRoot(pickleballRoot, version).resolve(WORKBENCH_DIRECTORY);
    }

    public static Path legacyWorkbench(Path pickleballRoot) {
        return pickleballRoot.toAbsolutePath().normalize().resolve(WORKBENCH_DIRECTORY);
    }

    /**
     * Workbench disposable state for this project. Uses {@code v/<current>/workbench}
     * when {@code current.json} is complete; otherwise the historical
     * {@code .pickleball/workbench} location so existing tests and unmigrated
     * trees keep working.
     */
    public static Path workbenchStateRoot(Path projectRoot) {
        Path pickleball = root(projectRoot);
        Optional<CurrentPointer> current = readCurrent(pickleball);
        if (current.isPresent() && current.get().usable()) {
            return versionWorkbench(pickleball, current.get().pickleballVersion());
        }
        return legacyWorkbench(pickleball);
    }

    /**
     * Workbench state for a known Pickleball version. Always the versioned
     * folder when {@code version} is a safe folder name.
     */
    public static Path workbenchStateRoot(Path projectRoot, String version) {
        if (isSafeVersion(version)) {
            return versionWorkbench(root(projectRoot), version);
        }
        return workbenchStateRoot(projectRoot);
    }

    public static Path lastDiscoverSnapshot(Path projectRoot) {
        return workbenchStateRoot(projectRoot).resolve(LAST_DISCOVER_FILE);
    }

    public static Path cliSessionState(Path projectRoot) {
        return workbenchStateRoot(projectRoot).resolve(CLI_SESSION_FILE);
    }

    public static Path attachFile(Path projectRoot) {
        return workbenchStateRoot(projectRoot).resolve(ATTACH_FILE);
    }

    public static Path investigationsDirectory(Path projectRoot) {
        Path pickleball = root(projectRoot);
        Optional<CurrentPointer> current = readCurrent(pickleball);
        if (current.isPresent() && current.get().usable()) {
            return versionRoot(pickleball, current.get().pickleballVersion())
                    .resolve(InvestigationHandoff.INVESTIGATIONS_DIRECTORY);
        }
        return pickleball.resolve(InvestigationHandoff.INVESTIGATIONS_DIRECTORY);
    }

    public static Optional<CurrentPointer> readCurrent(Path pickleballRoot) {
        Path file = pickleballRoot.toAbsolutePath().normalize().resolve(CURRENT_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return parseCurrent(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<CurrentPointer> parseCurrent(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        Matcher version = JSON_STRING.matcher(json);
        if (!version.find()) return Optional.empty();
        String pickleballVersion = version.group(1).trim();
        if (!isSafeVersion(pickleballVersion)) return Optional.empty();
        Matcher complete = JSON_COMPLETE.matcher(json);
        boolean done = !complete.find() || Boolean.parseBoolean(complete.group(1));
        Matcher updated = JSON_UPDATED.matcher(json);
        String updatedAt = updated.find() ? updated.group(1) : "";
        return Optional.of(new CurrentPointer(pickleballVersion, done, updatedAt));
    }

    public static void writeCurrent(Path pickleballRoot, CurrentPointer pointer) throws IOException {
        if (pointer == null || !isSafeVersion(pointer.pickleballVersion())) {
            throw new IllegalArgumentException("current.json requires a safe pickleballVersion.");
        }
        Path root = pickleballRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(CURRENT_FILE);
        Path temporary = root.resolve("." + CURRENT_FILE + ".tmp");
        Files.writeString(temporary, pointer.toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean isSafeVersion(String version) {
        if (version == null || version.isBlank()) return false;
        String value = version.trim();
        if (value.contains("/") || value.contains("\\") || value.contains("..")) return false;
        if (value.contains(":") || value.startsWith(".")) return false;
        return !value.equals(".") && !value.equals("..");
    }

    public static String requireSafeVersion(String version) {
        if (!isSafeVersion(version)) {
            throw new IllegalArgumentException("Unsafe Pickleball version folder name: " + version);
        }
        return version.trim();
    }

    /**
     * Walk {@code start} (file or directory) and its parents for a consumer
     * project: {@code .pickleball}, {@code pom.xml}, or a Gradle build file.
     */
    public static Path findProjectRoot(Path start) {
        Path current = start == null
                ? Path.of("").toAbsolutePath().normalize()
                : start.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        Path fallback = current;
        while (current != null) {
            if (looksLikeProject(current)) return current;
            current = current.getParent();
        }
        return fallback;
    }

    public static boolean looksLikeProject(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return false;
        return Files.isDirectory(directory.resolve(DIRECTORY))
                || Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"));
    }

    public static boolean isPickleballDirectory(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && DIRECTORY.equals(name.toString());
    }

    private static Path requireDirectoryHint(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Consumer project root is required.");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
