package tools.dscode.workbench.sync;

import java.nio.file.Files;
import java.nio.file.Path;

/** One selected Maven or Gradle project/module synchronized by Workbench. */
public record WorkbenchProject(
        Path root,
        Type type,
        Path buildRoot,
        Path launcher
) {
    public enum Type { MAVEN, GRADLE }

    public static WorkbenchProject locate(Path requested) {
        Path root = requested.toAbsolutePath().normalize();
        if (Files.isRegularFile(root)) {
            root = root.getParent();
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workbench project directory does not exist: " + root);
        }

        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"));
        if (maven == gradle) {
            throw new IllegalArgumentException(
                    "Workbench requires one selected Maven or Gradle project/module: " + root
            );
        }

        Type type = maven ? Type.MAVEN : Type.GRADLE;
        Path buildRoot = findBuildRoot(root, type);
        Path launcher = findLauncher(buildRoot, type);
        return new WorkbenchProject(root, type, buildRoot, launcher);
    }

    private static Path findBuildRoot(Path selected, Type type) {
        Path current = selected;
        while (current != null) {
            if (hasWrapper(current, type)) {
                return current;
            }
            current = current.getParent();
        }
        return selected;
    }

    private static Path findLauncher(Path buildRoot, Type type) {
        String windows = type == Type.MAVEN ? "mvnw.cmd" : "gradlew.bat";
        String unix = type == Type.MAVEN ? "mvnw" : "gradlew";
        Path windowsPath = buildRoot.resolve(windows);
        Path unixPath = buildRoot.resolve(unix);

        if (isWindows() && Files.isRegularFile(windowsPath)) return windowsPath;
        if (Files.isRegularFile(unixPath)) return unixPath;
        if (Files.isRegularFile(windowsPath)) return windowsPath;
        if (isWindows()) {
            return Path.of(type == Type.MAVEN ? "mvn.cmd" : "gradle.bat");
        }
        return Path.of(type == Type.MAVEN ? "mvn" : "gradle");
    }

    private static boolean hasWrapper(Path directory, Type type) {
        return type == Type.MAVEN
                ? Files.isRegularFile(directory.resolve("mvnw"))
                    || Files.isRegularFile(directory.resolve("mvnw.cmd"))
                : Files.isRegularFile(directory.resolve("gradlew"))
                    || Files.isRegularFile(directory.resolve("gradlew.bat"));
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
