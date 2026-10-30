package tools.dscode.common.reporting.diagnostic;

import tools.dscode.common.runtime.ProcessTrees;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.DynamicSuiteEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Invokes the consumer Maven or Gradle wrapper. A machine-wide install is not used. */
public final class ConsumerMavenTestRunner {
    private ConsumerMavenTestRunner() {
    }

    public interface ProcessLauncher {
        int run(List<String> command, Path directory, PrintStream out, PrintStream err)
                throws IOException, InterruptedException;
    }

    public static final String SUREFIRE_CUCUMBER_ENGINE = "-Dsurefire.includeJUnit5Engines=pickleball-test-engine";
    public static final String PLATFORM_CUCUMBER_ENGINE = "-Djunit.platform.discovery.includeEngines=pickleball-test-engine";

    public static List<String> command(Path projectRoot, Path workspace, Map<String, String> selection) {
        return command(projectRoot, workspace, selection, null);
    }

    public static List<String> command(
            Path projectRoot,
            Path workspace,
            Map<String, String> selection,
            String featurePaths
    ) {
        return build(projectRoot, workspace, selection, featurePaths, "workbench-discover");
    }

    public static List<String> confirmCommand(Path projectRoot, Path workspace, Map<String, String> selection) {
        return confirmCommand(projectRoot, workspace, selection, null);
    }

    public static List<String> confirmCommand(
            Path projectRoot,
            Path workspace,
            Map<String, String> selection,
            String featurePaths
    ) {
        return build(projectRoot, workspace, selection, featurePaths, "workbench-confirm");
    }

    /**
     * Does not put selected feature paths into {@code pkb_features}. Nested
     * {@code listPickles} reads that key. Top-level narrowing is a separate
     * {@link DynamicSuiteEngine#TOP_LEVEL_FEATURES_PROPERTY} argument.
     * A blank feature list leaves the original string unchanged.
     */
    public static String embedFeaturePaths(String compactRunVars, String featurePaths) {
        if (featurePaths == null || featurePaths.isBlank()) {
            return compactRunVars == null ? "" : compactRunVars;
        }
        return compactRunVars == null ? "" : compactRunVars;
    }

    /** One Maven {@code -D} argument for top-level narrowing, or null when there is nothing to narrow. */
    public static String topLevelFeaturesArgument(String featurePaths) {
        if (featurePaths == null || featurePaths.isBlank()) {
            return null;
        }
        return "-D" + DynamicSuiteEngine.TOP_LEVEL_FEATURES_PROPERTY + "=" + featurePaths;
    }

    private static List<String> build(
            Path projectRoot,
            Path workspace,
            Map<String, String> selection,
            String featurePaths,
            String purpose
    ) {
        List<String> command = new ArrayList<>(launchPrefix(wrapper(projectRoot)));
        boolean mavenProject = Files.isRegularFile(projectRoot.toAbsolutePath().normalize().resolve("pom.xml"));
        if (mavenProject) {
            if (offlineSafe(projectRoot)) command.add("-o");
            command.add("-q");
        } else if (offlineSafe(projectRoot)) {
            command.add("--offline");
        }
        command.add("test");
        command.addAll(Workspace.launchArguments(workspace, selection));
        String topLevel = topLevelFeaturesArgument(featurePaths);
        if (topLevel != null) {
            command.add(topLevel);
        }
        command.add("-Dpkb_run_purpose=" + purpose);
        if (Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            command.add(SUREFIRE_CUCUMBER_ENGINE);
            command.add(PLATFORM_CUCUMBER_ENGINE);
        }
        return List.copyOf(command);
    }

    /**
     * Offline is safe only after this project has already compiled tests, so
     * the local repository has the resolved plugins and dependencies. A fresh
     * checkout stays online. A normal {@code mvn test} does not call this.
     */
    /** Build-tool output that means an offline launch failed only because a plugin or provider was never downloaded. */
    public static boolean offlineResolutionFailure(String output) {
        if (output == null) return false;
        return output.contains("in offline mode") || output.contains("has not been downloaded from it before");
    }

    /** True when {@code command} carries the warm-launch offline flag ({@code -o} or {@code --offline}). */
    public static boolean hasOfflineFlag(List<String> command) {
        return command.contains("-o") || command.contains("--offline");
    }

    /** The same launch without the warm-launch offline flag. Every other argument is kept in order. */
    public static List<String> withoutOffline(List<String> command) {
        List<String> online = new ArrayList<>(command.size());
        for (String argument : command) {
            if (!argument.equals("-o") && !argument.equals("--offline")) online.add(argument);
        }
        return List.copyOf(online);
    }

    static boolean offlineSafe(Path projectRoot) {
        Path project = projectRoot.toAbsolutePath().normalize();
        if (Files.isRegularFile(project.resolve("pom.xml"))) {
            return Files.isDirectory(project.resolve("target/test-classes"));
        }
        if (Files.isRegularFile(project.resolve("build.gradle"))
                || Files.isRegularFile(project.resolve("build.gradle.kts"))) {
            return Files.isDirectory(project.resolve("build/classes/java/test"))
                    || Files.isDirectory(project.resolve("build/classes/kotlin/test"));
        }
        return false;
    }

    public static int run(
            Path projectRoot,
            List<String> command,
            PrintStream out,
            PrintStream err
    ) {
        return run(projectRoot, command, out, err, inheritIoLauncher());
    }

    public static int run(
            Path projectRoot,
            List<String> command,
            PrintStream out,
            PrintStream err,
            ProcessLauncher launcher
    ) {
        try {
            return launcher.run(command, projectRoot.toAbsolutePath().normalize(), out, err);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("Workbench Maven test was interrupted.");
            return 1;
        } catch (IOException failure) {
            err.println("Workbench could not run Maven test: " + failure.getMessage());
            return 1;
        }
    }

    static Path wrapper(Path projectRoot) {
        Path project = projectRoot.toAbsolutePath().normalize();
        boolean mavenFile = Files.isRegularFile(project.resolve("pom.xml"));
        boolean gradleFile = Files.isRegularFile(project.resolve("build.gradle"))
                || Files.isRegularFile(project.resolve("build.gradle.kts"));
        if (mavenFile && gradleFile) {
            throw new IllegalArgumentException(
                    "Discover requires one Maven or Gradle project: " + project
                            + ". Pickleball does not use a machine-wide Maven or Gradle install."
            );
        }
        if (!mavenFile && !gradleFile) {
            return findLauncher(project, true);
        }
        return findLauncher(project, mavenFile);
    }

    private static Path findLauncher(Path buildRoot, boolean maven) {
        boolean windows = isWindows();
        String windowsName = maven ? "mvnw.cmd" : "gradlew.bat";
        String unixName = maven ? "mvnw" : "gradlew";
        Path windowsPath = buildRoot.resolve(windowsName);
        Path unixPath = buildRoot.resolve(unixName);
        if (windows && Files.isRegularFile(windowsPath)) return windowsPath;
        if (Files.isRegularFile(unixPath)) return unixPath;
        if (Files.isRegularFile(windowsPath)) return windowsPath;
        Path jar = wrapperJar(buildRoot, maven);
        if (Files.isRegularFile(jar)) return jar;
        String script = windows ? windowsName : unixName;
        String kind = maven ? "Maven" : "Gradle";
        throw new IllegalArgumentException(
                "No " + kind + " wrapper at " + buildRoot
                        + ". Expected " + script + " or " + jar
                        + ". Pickleball does not use a machine-wide Maven or Gradle install."
        );
    }

    private static Path wrapperJar(Path buildRoot, boolean maven) {
        return maven
                ? buildRoot.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.jar")
                : buildRoot.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar");
    }

    static List<String> launchPrefix(Path launcher) {
        String name = launcher.getFileName() == null ? launcher.toString() : launcher.getFileName().toString();
        if (name.endsWith(".jar")) {
            return List.of(javaExecutable(), "-jar", launcher.toString());
        }
        if (isWindows() && (name.endsWith(".cmd") || name.endsWith(".bat"))) {
            return List.of("cmd.exe", "/d", "/c", launcher.toString());
        }
        return List.of(launcher.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path bin = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        return Files.isRegularFile(bin) ? bin.toString() : (windows ? "java.exe" : "java");
    }

    static ProcessLauncher inheritIoLauncher() {
        return (command, directory, out, err) -> {
            // stdout and stderr must be the streams the caller passed in.
            // inheritIO() writes to the JVM's original descriptors and skips them,
            // so Workbench console capture stayed empty.
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process process = builder.start();
            Thread output = pump(process.getInputStream(), out, "pickleball-maven-stdout");
            Thread errors = pump(process.getErrorStream(), err, "pickleball-maven-stderr");
            try {
                int exit = process.waitFor();
                output.join(30_000);
                errors.join(30_000);
                return exit;
            } catch (InterruptedException interrupted) {
                ProcessTrees.killTree(process.toHandle());
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                output.interrupt();
                errors.interrupt();
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        };
    }

    private static Thread pump(InputStream input, PrintStream target, String name) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (target != null && read > 0) {
                        target.write(buffer, 0, read);
                        target.flush();
                    }
                }
            } catch (IOException ignored) {
                // The process exiting, or an interrupt closing the pipe, ends the copy.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing a finished pipe is only to unblock the pump.
        }
    }
}
