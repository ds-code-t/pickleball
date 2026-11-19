package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovery file for the live worker bridge. The descriptor JSON has no token;
 * this file carries host, port, token, pid, and session id so the CLI can POST
 * {@code /v1/agent/command} without a second transport. A dead pid is absent.
 */
public final class LiveBridgeFile {
    public static final String RELATIVE = ".pickleball/workbench/live-bridge.json";

    public record Bridge(String host, int port, String token, long pid, String sessionId) { }

    private LiveBridgeFile() {
    }

    public static Path file(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(RELATIVE);
    }

    public static void write(Path project, Bridge bridge) throws IOException {
        if (bridge == null) throw new IOException("live bridge is missing");
        Path target = file(project);
        Files.createDirectories(target.getParent());
        String json = "{"
                + "\"host\":\"" + escape(bridge.host()) + "\","
                + "\"port\":" + bridge.port() + ","
                + "\"token\":\"" + escape(bridge.token()) + "\","
                + "\"pid\":" + bridge.pid() + ","
                + "\"sessionId\":\"" + escape(bridge.sessionId()) + "\""
                + "}\n";
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    /** Present only when the file parses and the recorded pid is still alive. */
    public static Optional<Bridge> read(Path project) {
        Path target = file(project);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            String json = Files.readString(target);
            String host = stringField(json, "host");
            String token = stringField(json, "token");
            String sessionId = stringField(json, "sessionId");
            String portText = numberField(json, "port");
            String pidText = numberField(json, "pid");
            if (host == null || host.isBlank() || token == null || token.isBlank() || portText == null || pidText == null) {
                return Optional.empty();
            }
            int port = Integer.parseInt(portText);
            long pid = Long.parseLong(pidText);
            if (port <= 0 || port > 65535 || !alive(pid)) return Optional.empty();
            return Optional.of(new Bridge(host, port, token, pid, sessionId == null ? "" : sessionId));
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    public static void delete(Path project) {
        try {
            Files.deleteIfExists(file(project));
        } catch (IOException ignored) {
            // A missing file is the stopped state.
        }
    }

    static boolean alive(long pid) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stringField(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) return null;
        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String numberField(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
