package tools.dscode.launcher;

import tools.dscode.control.protocol.speed.WarmProcess;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One long-lived JVM. Later commands connect instead of starting Maven or a
 * new process. Each command runs in a fresh class loader so runner statics
 * do not leak. A hash mismatch makes the launcher kill this process.
 */
public final class WorkbenchWarmMain {
    private WorkbenchWarmMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 2) {
            System.err.println("error: warm JVM needs a state directory and a watch hash.");
            System.err.println("fix: the launcher starts this process. Do not invoke it.");
            System.exit(2);
            return;
        }
        Path state = Path.of(args[0]);
        String watch = args[1];
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            WarmProcess.write(state, new WarmProcess.Handle(ProcessHandle.current().pid(), server.getLocalPort(), watch));
            while (true) {
                try (Socket socket = server.accept()) {
                    serve(socket);
                } catch (Exception failure) {
                    System.err.println("warm JVM request failed: " + failure.getMessage());
                }
            }
        }
    }

    private static void serve(Socket socket) throws Exception {
        socket.setSoTimeout(180_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        String verb = reader.readLine();
        if ("PING".equals(verb)) {
            writer.println("PONG");
            return;
        }
        if (!"RUN".equals(verb)) {
            writer.println("EXIT 2");
            writer.println("END");
            return;
        }
        List<String> dashD = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null && !".".equals(line)) dashD.add(line);
        String projectLine = reader.readLine();
        Path project = projectLine == null || projectLine.isBlank() ? Path.of("") : Path.of(projectLine);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(captured, true, StandardCharsets.UTF_8);
        PrintStream out = System.out;
        PrintStream err = System.err;
        int exit;
        ClassLoader loader = newLoader();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            System.setOut(tee);
            System.setErr(tee);
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> type = Class.forName("tools.dscode.launcher.WorkbenchDirectRun", true, loader);
            exit = (Integer) type.getMethod("run", Path.class, List.class).invoke(null, project, dashD);
        } catch (Throwable failure) {
            exit = 1;
            tee.println("error: warm run failed: " + failure.getMessage());
            tee.println("fix: the wrapper path still runs this command.");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            System.setOut(out);
            System.setErr(err);
            loader = null;
        }
        writer.println("EXIT " + exit);
        for (String capturedLine : captured.toString(StandardCharsets.UTF_8).split("\\R", -1)) {
            if ("END".equals(capturedLine)) capturedLine = "END ";
            writer.println(capturedLine);
        }
        writer.println("END");
    }

    private static ClassLoader newLoader() {
        String classpath = System.getProperty("java.class.path", "");
        java.util.List<java.net.URL> urls = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator, -1)) {
            if (entry.isBlank()) continue;
            try {
                urls.add(Path.of(entry).toAbsolutePath().normalize().toUri().toURL());
            } catch (java.net.MalformedURLException ignored) {
                // Skip a broken entry. The fallback path still runs.
            }
        }
        return new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}
