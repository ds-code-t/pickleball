package tools.dscode.dolt;

import tools.dscode.launcher.WorkbenchDirectRun;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Warm JVM for {@code dolt run}. One process, loopback only, same engine as
 * {@link WorkbenchDirectRun}. A glue fingerprint change replaces the process.
 */
public final class DoltRunWorker {
    private static volatile Path outcomeFile;

    private DoltRunWorker() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("error: Dolt warm worker needs a state directory");
            System.err.println("fix: dolt run");
            System.exit(2);
            return;
        }
        Path state = Path.of(args[0]);
        DoltScope.current();
        Files.createDirectories(state);
        outcomeFile = state.resolve("run-outcomes.txt");
        String fingerprint = Files.readString(state.resolve(DoltRun.FINGERPRINT), StandardCharsets.UTF_8).trim();
        try (ServerSocket server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress())) {
            Files.writeString(state.resolve(DoltRun.WARM),
                    ProcessHandle.current().pid() + "\n" + server.getLocalPort() + "\n" + fingerprint + "\n",
                    StandardCharsets.UTF_8);
            while (true) {
                try (Socket socket = server.accept()) {
                    if (serve(socket)) return;
                }
            }
        }
    }

    private static boolean serve(Socket socket) throws Exception {
        socket.setSoTimeout(600_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        String verb = reader.readLine();
        if ("PING".equals(verb)) {
            writer.println("PONG");
            return false;
        }
        if ("STOP".equals(verb)) {
            writer.println("BYE");
            return true;
        }
        if (!"RUN".equals(verb)) {
            writer.println("EXIT 2");
            writer.println("END");
            return false;
        }
        List<String> incoming = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !".".equals(line)) incoming.add(line);
        String project = reader.readLine();
        Path trace = outcomeFile;
        if (trace != null) Files.deleteIfExists(trace);
        List<String> properties = runProperties(incoming, trace, project);
        int exit = WorkbenchDirectRun.run(project == null ? Path.of("") : Path.of(project), properties);
        writer.println("EXIT " + exit);
        if (trace != null && Files.isRegularFile(trace)) {
            for (String outcome : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
                if (!outcome.isBlank()) writer.println(outcome);
            }
        }
        writer.println("END");
        return false;
    }

    /** Properties for one RUN. A missing run id becomes a new diagnostic-shaped id. */
    static List<String> runProperties(List<String> incoming, Path trace, String project) {
        List<String> properties = new ArrayList<>();
        if (incoming != null) properties.addAll(incoming);
        if (trace != null) {
            properties.add("-Dpkb_dolt_run_trace=true");
            properties.add("-Dpkb_dolt_run_trace_file=" + trace.toAbsolutePath().normalize());
        }
        if (properties.stream().noneMatch(item -> item.startsWith("-Dpkb_dolt_track="))) {
            properties.add("-Dpkb_dolt_track=true");
        }
        if (properties.stream().noneMatch(item -> item.startsWith("-Dpkb_run_id="))) {
            properties.add("-Dpkb_run_id=" + DoltExecution.newRunId());
        }
        if (project != null && properties.stream().noneMatch(item -> item.startsWith("-Dpkb_dolt_project="))) {
            properties.add("-Dpkb_dolt_project=" + Path.of(project).toAbsolutePath().normalize());
        }
        return properties;
    }
}
