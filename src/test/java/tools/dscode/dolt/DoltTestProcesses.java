package tools.dscode.dolt;

import tools.dscode.dolt.store.DoltConnections;
import tools.dscode.dolt.store.StoreFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Process hygiene for store tests that run on Windows. A child JVM keeps files
 * and its working directory locked there, so {@code @TempDir} cleanup fails
 * unless the test stops it and waits for the exit first. Child JVM arguments
 * go through an argument file because the test classpath is longer than the
 * Windows command line.
 */
final class DoltTestProcesses {
    private DoltTestProcesses() {
    }

    /**
     * Closes this JVM's pools for the project, ends any {@code dolt} child this JVM started,
     * and waits for the SQLite files to be renameable. A file still held at the deadline fails
     * the test with the marker contents.
     */
    static void release(Path project) throws IOException {
        if (project == null) return;
        List<ProcessHandle> spawned = ProcessHandle.current().descendants()
                .filter(handle -> command(handle).contains("dolt"))
                .toList();
        DoltConnections.closeProject(project);
        for (ProcessHandle handle : spawned) terminate(handle, 5_000);
        if (!StoreFiles.releaseProject(project, StoreFiles.RELEASE_MS)) {
            throw new AssertionError("store files still held: " + Files.readString(StoreFiles.pendingDelete(project)));
        }
    }

    private static boolean terminate(ProcessHandle handle, long graceMillis) {
        if (handle == null) return true;
        if (handle.pid() == ProcessHandle.current().pid()) return false;
        if (handle.isAlive()) handle.destroy();
        if (awaitExit(handle, graceMillis)) return true;
        handle.destroyForcibly();
        return awaitExit(handle, 5_000);
    }

    private static boolean awaitExit(ProcessHandle handle, long millis) {
        if (handle == null) return true;
        try {
            handle.onExit().get(Math.max(0, millis), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException timedOut) {
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            return !handle.isAlive();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return !handle.isAlive();
        }
    }

    private static String command(ProcessHandle handle) {
        return handle.info().command().orElse("").toLowerCase(Locale.ROOT);
    }

    /**
     * Returns {@code java @argfile}. Everything after the java executable, JVM
     * options, main class, and program arguments, goes in the file.
     */
    static List<String> java(List<String> arguments) throws IOException {
        Path file = Files.createTempFile("pkb-dolt-child-", ".args");
        file.toFile().deleteOnExit();
        List<String> lines = new ArrayList<>();
        for (String argument : arguments) lines.add(quote(argument));
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(javaBin().toString());
        command.add("@" + file.toAbsolutePath());
        return command;
    }

    static Path javaBin() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(ch);
            }
        }
        return quoted.append('"').toString();
    }
}
