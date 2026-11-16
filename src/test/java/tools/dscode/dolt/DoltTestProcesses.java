package tools.dscode.dolt;

import tools.dscode.dolt.server.DoltServer;
import tools.dscode.dolt.store.DoltConnections;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Process hygiene for Dolt tests that run on Windows. A live sql-server, supervisor,
 * or child JVM keeps files and its working directory locked there, so {@code @TempDir}
 * cleanup fails unless the test stops them and waits for the exit first. Child JVM
 * arguments go through an argument file because the test classpath is longer than
 * the Windows command line.
 */
final class DoltTestProcesses {
    private DoltTestProcesses() {
    }

    /**
     * Closes this JVM's pools for the project, then stops its server and supervisor and waits for both.
     * Any other {@code dolt} process this JVM started (directly or through a supervisor) is ended too and
     * awaited. The snapshot is taken before the stop, because an orphaned server is no longer reachable
     * as a descendant once its supervisor is gone. On Windows it then waits up to 15 seconds for the
     * store journal handle to be released; if it is still held, this fails with the live Dolt processes.
     */
    static void release(Path project) throws IOException {
        if (project == null) return;
        List<ProcessHandle> spawned = ProcessHandle.current().descendants()
                .filter(handle -> command(handle).contains("dolt"))
                .toList();
        DoltConnections.closeProject(project);
        DoltServer.stop(project);
        for (ProcessHandle handle : spawned) DoltServer.terminate(handle, 5_000);
        if (windows()) requireJournalReleased(project, spawned);
    }

    private static void requireJournalReleased(Path project, List<ProcessHandle> spawned) {
        Path journal = DoltServer.dataDir(project).resolve("pickleball/.dolt/noms/journal.idx");
        if (!Files.isRegularFile(journal)) return;
        Path moved = journal.resolveSibling("journal.idx.release-check");
        // Windows can keep a killed process's mapped journal referenced for a moment after the
        // process object is signaled. Wait (bounded) for that handle to go before teardown.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        IOException locked = null;
        while (true) {
            try {
                Files.move(journal, moved);
                Files.move(moved, journal);
                return;
            } catch (IOException busy) {
                locked = busy;
            }
            if (System.nanoTime() > deadline) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        StringBuilder live = new StringBuilder();
        ProcessHandle.allProcesses()
                .filter(handle -> command(handle).contains("dolt"))
                .forEach(handle -> live.append("\n  pid=").append(handle.pid())
                        .append(" parent=").append(handle.parent().map(ProcessHandle::pid).orElse(-1L))
                        .append(" alive=").append(handle.isAlive())
                        .append(" start=").append(handle.info().startInstant().map(Object::toString).orElse("?"))
                        .append(" command=").append(handle.info().command().orElse("?")));
        StringBuilder swept = new StringBuilder();
        for (ProcessHandle handle : spawned) swept.append(' ').append(handle.pid());
        throw new AssertionError("Dolt journal still locked after stop: " + journal + " (" + locked + ")"
                + "\nswept dolt descendants:" + swept + "\nlive dolt processes:" + live);
    }

    private static String command(ProcessHandle handle) {
        return handle.info().command().orElse("").toLowerCase(Locale.ROOT);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
