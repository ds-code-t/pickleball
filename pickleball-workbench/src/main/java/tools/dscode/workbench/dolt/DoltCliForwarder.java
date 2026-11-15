package tools.dscode.workbench.dolt;

import tools.dscode.control.protocol.PickleballArtifactLocator;
import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Workbench action that only forwards to the Pickleball {@code dolt} CLI.
 * This module does not link Dolt classes.
 */
public final class DoltCliForwarder {
    private DoltCliForwarder() {
    }

    public record Result(int exit, String out, String err, List<String> command) {
    }

    public static Result run(Path project, List<String> doltArgs) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        return run(root, doltArgs, locateJar(root).orElse(null));
    }

    public static Result run(Path project, List<String> doltArgs, Path jar) {
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        if (jar == null || !Files.isRegularFile(jar)) {
            return new Result(
                    1,
                    "",
                    "error: pickleball jar was not found\nfix: Set -Dpkb.pickleball.jar or publish the jar.\n",
                    List.of()
            );
        }
        List<String> command = command(jar, root, doltArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(root.toFile());
        try {
            Process process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outThread = new Thread(() -> transfer(process.getInputStream(), stdout), "dolt-out");
            Thread errThread = new Thread(() -> transfer(process.getErrorStream(), stderr), "dolt-err");
            outThread.start();
            errThread.start();
            int exit = process.waitFor();
            outThread.join();
            errThread.join();
            return new Result(
                    exit,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8),
                    List.copyOf(command)
            );
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(1, "", "error: Dolt forward was interrupted\n", command);
        } catch (Exception failure) {
            return new Result(1, "", "error: " + failure.getMessage() + "\n", command);
        }
    }

    /** The process command. Tests assert this list without starting a JVM. */
    public static List<String> command(Path jar, Path project, List<String> doltArgs) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toAbsolutePath().normalize().toString());
        command.add("dolt");
        if (doltArgs != null) {
            for (String arg : doltArgs) {
                if (arg != null && !arg.isBlank()) command.add(arg);
            }
        }
        command.add("--project=" + project.toAbsolutePath().normalize());
        return List.copyOf(command);
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
            // The exit code still reports the forward.
        }
    }
}
