package tools.dscode.dalton;

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
 * Warm JVM for {@code dalton run}. One process, loopback only, same engine as
 * {@link WorkbenchDirectRun}. A glue fingerprint change replaces the process.
 */
public final class DaltonRunWorker {
    private DaltonRunWorker() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("error: Dalton warm worker needs a state directory");
            System.err.println("fix: dalton run");
            System.exit(2);
            return;
        }
        Path state = Path.of(args[0]);
        Files.createDirectories(state);
        String fingerprint = Files.readString(state.resolve(DaltonRun.FINGERPRINT), StandardCharsets.UTF_8).trim();
        try (ServerSocket server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress())) {
            Files.writeString(state.resolve(DaltonRun.WARM),
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
        List<String> properties = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !".".equals(line)) properties.add(line);
        String project = reader.readLine();
        int exit = WorkbenchDirectRun.run(project == null ? Path.of("") : Path.of(project), properties);
        writer.println("EXIT " + exit);
        writer.println("END");
        return false;
    }
}
