package tools.dscode.workbench.launch;

import tools.dscode.control.protocol.PickleballArtifactLocator;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Fast launcher for the B1 verbs. The child JVM is
 * {@code java -cp ConsumerClasspath.entries tools.dscode.launcher.PickleballWorkbenchLauncher}.
 * The published jar stays thin.
 */
public final class ConsumerReadyForwarder {
    /** Test seam. Production leaves this null and starts a process. */
    public static volatile Function<List<String>, Result> runner;

    private ConsumerReadyForwarder() {
    }

    public record Result(int exit, String out, String err, List<String> command) {
    }

    public static Result forward(Path project, List<String> verbArgs) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        Path jar = locateJar(root).orElse(null);
        List<String> command = command(root, jar, verbArgs);
        Function<List<String>, Result> hook = runner;
        if (hook != null) return hook.apply(command);
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Result(
                    1,
                    "",
                    "error: pickleball jar was not found\nfix: Set -Dpkb.pickleball.jar or publish the jar.\n",
                    command
            );
        }
        return runProcess(root, command);
    }

    /** The process command. Tests assert this list without starting a JVM. */
    public static List<String> command(Path project, Path jar, List<String> verbArgs) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        List<Path> entries = ConsumerClasspath.entries(root, jar);
        String separator = java.io.File.pathSeparator;
        StringBuilder classpath = new StringBuilder();
        for (Path entry : entries) {
            if (!classpath.isEmpty()) classpath.append(separator);
            classpath.append(entry.toAbsolutePath().normalize());
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath.toString());
        command.add(ConsumerClasspath.MAIN_CLASS);
        if (verbArgs != null) {
            for (String arg : verbArgs) {
                if (arg != null && !arg.isBlank()) command.add(arg);
            }
        }
        command.add("--project=" + root);
        return List.copyOf(command);
    }

    private static Result runProcess(Path root, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        try {
            Process process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outThread = new Thread(() -> transfer(process.getInputStream(), stdout), "consumer-ready-out");
            Thread errThread = new Thread(() -> transfer(process.getErrorStream(), stderr), "consumer-ready-err");
            outThread.start();
            errThread.start();
            int exit = process.waitFor();
            outThread.join();
            errThread.join();
            return new Result(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8), command);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(1, "", "error: consumer forward was interrupted\n", command);
        } catch (Exception failure) {
            return new Result(1, "", "error: " + failure.getMessage() + "\n", command);
        }
    }

    private static Optional<Path> locateJar(Path project) {
        String property = System.getProperty("pkb.pickleball.jar");
        if (property == null || property.isBlank()) property = System.getenv("PKB_PICKLEBALL_JAR");
        if (property != null && !property.isBlank()) {
            Path given = Path.of(property);
            if (Files.isRegularFile(given)) return Optional.of(given);
        }
        Optional<PickleballLocalLayout.CurrentPointer> current =
                PickleballLocalLayout.readCurrent(PickleballLocalLayout.root(project));
        PickleballArtifactLocator.Repositories repositories = PickleballArtifactLocator.Repositories.discover();
        if (current.isPresent() && current.get().usable()) {
            Optional<Path> pinned = PickleballArtifactLocator.find(current.get().pickleballVersion(), repositories);
            if (pinned.isPresent()) return pinned;
        }
        return PickleballArtifactLocator.findLatest(repositories).map(PickleballArtifactLocator.Located::jar);
    }

    private static void transfer(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (Exception ignored) {
            // The exit code still reports the launch.
        }
    }
}
