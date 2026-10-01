package tools.dscode.common.reporting.diagnostic;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Invokes the consumer Maven wrapper. A machine-wide Maven install is not used. */
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
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = project.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(script)) return script;
        Path alternate = project.resolve(windows ? "mvnw" : "mvnw.cmd");
        if (Files.isRegularFile(alternate)) return alternate;
        Path jar = project.resolve(".mvn").resolve("wrapper").resolve("maven-wrapper.jar");
        if (Files.isRegularFile(jar)) return jar;
        throw new IllegalArgumentException(
                "No Maven wrapper at " + project
                        + ". Expected mvnw or mvnw.cmd, or .mvn/wrapper/maven-wrapper.jar."
                        + " Pickleball does not use a machine-wide Maven install."
        );
    }

    static List<String> launchPrefix(Path launcher) {
        String name = launcher.getFileName() == null ? launcher.toString() : launcher.getFileName().toString();
        if (name.endsWith(".jar")) {
            return List.of(javaExecutable(), "-jar", launcher.toString());
        }
        return List.of(launcher.toString());
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
