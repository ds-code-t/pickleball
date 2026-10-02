package tools.dscode.common.reporting.diagnostic;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Invokes the consumer Maven or Gradle wrapper. A machine-wide install is not used. */
public final class ConsumerMavenTestRunner {
    private ConsumerMavenTestRunner() {
    }

    public interface ProcessLauncher {
        int run(List<String> command, Path directory, PrintStream out, PrintStream err)
                throws IOException, InterruptedException;
    }

    public static List<String> command(Path projectRoot, String compactRunVars) {
        List<String> command = new ArrayList<>(launchPrefix(wrapper(projectRoot)));
        command.add("test");
        command.add("-Dpkb_runvars=" + compactRunVars);
        command.add("-Dpkb_run_purpose=workbench-discover");
        return List.copyOf(command);
    }

    public static List<String> confirmCommand(Path projectRoot, String compactRunVars) {
        List<String> command = new ArrayList<>(launchPrefix(wrapper(projectRoot)));
        command.add("test");
        command.add("-Dpkb_runvars=" + compactRunVars);
        command.add("-Dpkb_run_purpose=workbench-confirm");
        return List.copyOf(command);
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

    private static ProcessLauncher inheritIoLauncher() {
        return (command, directory, out, err) -> {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .inheritIO();
            Process process = builder.start();
            return process.waitFor();
        };
    }
}
