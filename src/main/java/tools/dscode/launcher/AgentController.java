package tools.dscode.launcher;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One long-lived JVM per project. It owns agent verbs for that project so
 * later CLI calls are socket clients. MCP and the live bridge are unchanged:
 * a verb still goes through {@link WorkbenchAgentCommands}.
 */
public final class AgentController {
    private static final ObjectMapper JSON = new ObjectMapper();
    static {
        JSON.getFactory().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, true);
    }
    private static final AtomicBoolean STOP = new AtomicBoolean();
    private static final AtomicBoolean STOP_AFTER = new AtomicBoolean();

    private AgentController() {
    }

    public static void serve(String[] args) throws Exception {
        System.setProperty(AgentHop.INSIDE, "true");
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            System.err.println("error: controller-serve needs a project directory");
            System.err.println("fix: controller-start");
            System.exit(2);
            return;
        }
        Path project = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(project.resolve(".pickleball"));
        STOP.set(false);
        STOP_AFTER.set(false);
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            write(project, server.getLocalPort());
            while (!STOP.get()) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> {
                    try (Socket ignored = socket) {
                        serveOne(project, socket);
                    } catch (Exception failure) {
                        if (!STOP.get()) System.err.println("controller request failed: " + failure.getMessage());
                    }
                }, "pickleball-agent-controller");
                worker.setDaemon(true);
                worker.start();
            }
        } finally {
            delete(project);
        }
    }

    private static void serveOne(Path project, Socket socket) throws Exception {
        socket.setSoTimeout(120_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        String line = reader.readLine();
        if (line == null || line.isBlank()) {
            writer.println(reply(2, "", "error: empty request\n"));
            afterReply(writer);
            return;
        }
        JsonNode node = JSON.readTree(line);
        String token = node.path("token").asText("");
        if (!token.equals(readToken(project))) {
            writer.println(reply(2, "", "error: controller token rejected\nfix: controller-start\n"));
            afterReply(writer);
            return;
        }
        String op = node.path("op").asText("");
        if ("stop".equals(op)) {
            writer.println(reply(0, "stopped\n", ""));
            writer.flush();
            STOP.set(true);
            delete(project);
            Thread stopper = new Thread(() -> System.exit(0), "pickleball-agent-controller-stop");
            stopper.setDaemon(true);
            stopper.start();
            return;
        }
        if ("ping".equals(op)) {
            long started = System.nanoTime();
            String body = tools.dscode.common.agentfast.AgentEnvelope.ping(project, "ping", started);
            writer.println(reply(0, body.endsWith("\n") ? body : body + "\n", ""));
            afterReply(writer);
            return;
        }
        List<String> argv = new ArrayList<>();
        for (JsonNode arg : node.path("argv")) argv.add(arg.asText(""));
        if (argv.isEmpty()) {
            writer.println(reply(2, "", "error: missing argv\nfix: pass a verb\n"));
            afterReply(writer);
            return;
        }
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit;
        try {
            exit = WorkbenchAgentCommands.run(
                    argv.toArray(String[]::new),
                    new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                    new PrintStream(errBytes, true, StandardCharsets.UTF_8)
            );
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            writer.println(reply(1, "", "error: " + message + "\n"));
            afterReply(writer);
            return;
        }
        writer.println(reply(exit, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8)));
        afterReply(writer);
    }

    /** {@code live-stop} inside the controller exits only after this reply is flushed. */
    public static void requestStopAfterReply() {
        STOP_AFTER.set(true);
    }

    /** Tests clear the flag so a later controller in this JVM is not stopped. */
    public static void clearStopAfterReplyForTests() {
        STOP_AFTER.set(false);
    }

    static boolean stopAfterReply() {
        return STOP_AFTER.get();
    }

    private static void afterReply(PrintWriter writer) {
        writer.flush();
        if (!STOP_AFTER.getAndSet(false)) return;
        STOP.set(true);
        Thread stopper = new Thread(() -> System.exit(0), "pickleball-agent-controller-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private static String reply(int exit, String out, String err) throws Exception {
        ObjectNode node = JSON.createObjectNode();
        node.put("exit", exit);
        node.put("out", out == null ? "" : out);
        node.put("err", err == null ? "" : err);
        return JSON.writeValueAsString(node);
    }

    private static void write(Path project, int port) throws Exception {
        ObjectNode node = JSON.createObjectNode();
        node.put("pid", ProcessHandle.current().pid());
        node.put("port", port);
        node.put("token", UUID.randomUUID().toString());
        Path file = PkbMain.handleFile(project);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(node) + "\n");
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException failure) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String readToken(Path project) {
        try {
            JsonNode node = JSON.readTree(PkbMain.handleFile(project).toFile());
            return node.path("token").asText("");
        } catch (Exception failure) {
            return "";
        }
    }

    private static void delete(Path project) {
        try {
            Files.deleteIfExists(PkbMain.handleFile(project));
        } catch (Exception ignored) {
            // A second stop already removed it.
        }
    }
}
