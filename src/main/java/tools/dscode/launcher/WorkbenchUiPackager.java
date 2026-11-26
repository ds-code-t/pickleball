package tools.dscode.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Copies {@code workbench-ui} into {@code META-INF/pickleball/workbench-ui}.
 * An empty lockfile skips npm. A lockfile that names packages fails closed until npm ci runs.
 */
public final class WorkbenchUiPackager {
    private WorkbenchUiPackager() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("error: workbenchUi needs a project directory and an output directory");
            System.err.println("fix: run the workbenchUi Gradle task");
            System.exit(2);
        }
        int code = packageUi(Path.of(args[0]), Path.of(args[1]));
        if (code != 0) System.exit(code);
    }

    static int packageUi(Path project, Path output) {
        Path source = project.resolve("workbench-ui");
        if (!Files.isDirectory(source)) {
            System.err.println("error: workbench-ui is missing");
            System.err.println("fix: keep workbench-ui/server.mjs in the project");
            return 2;
        }
        Path lock = source.resolve("package-lock.json");
        try {
            if (Files.isRegularFile(lock) && Files.readString(lock, StandardCharsets.UTF_8).contains("node_modules/")) {
                System.err.println("error: workbench-ui lockfile has packages and npm ci has not run");
                System.err.println("fix: the workbenchUi task runs npm ci from the pinned npm CLI");
                return 2;
            }
            Path destination = output.resolve("META-INF").resolve("pickleball").resolve("workbench-ui");
            if (Files.exists(destination)) {
                try (Stream<Path> walk = Files.walk(destination)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
                }
            }
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path path : walk.toList()) {
                    Path relative = source.relativize(path);
                    if (relative.getNameCount() == 0) continue;
                    String first = relative.getName(0).toString();
                    if ("node_modules".equals(first)) continue;
                    Path target = destination.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(path, target);
                    }
                }
            }
            return 0;
        } catch (IOException | RuntimeException failure) {
            System.err.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            System.err.println("fix: run the workbenchUi Gradle task");
            return 1;
        }
    }
}
