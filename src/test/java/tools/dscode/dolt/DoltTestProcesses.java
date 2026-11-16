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

    /** Closes this JVM's pools for the project, then stops its server and supervisor and waits for both. */
    static void release(Path project) throws IOException {
        if (project == null) return;
        DoltConnections.closeProject(project);
        DoltServer.stop(project);
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
