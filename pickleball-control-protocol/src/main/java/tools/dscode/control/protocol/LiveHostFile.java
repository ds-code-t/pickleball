package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * State for the {@code start} host. This is not the Workbench worker bridge.
 * A worker {@code live-bridge.json} is left alone by start and live-stop.
 * When both exist, page verbs use the worker bridge while its pid is alive,
 * and otherwise the start host.
 */
public final class LiveHostFile {
    public static final String RELATIVE = ".pickleball/live-browser/current.json";
    public static final String OWNER = "live-browser";

    private LiveHostFile() {
    }

    public static Path file(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(RELATIVE);
    }

    public record Host(
            String url,
            String token,
            int port,
            long pid,
            long chromePid,
            String sessionId,
            boolean headed,
            String browser,
            boolean navigated,
            String navError,
            boolean pageAttached
    ) {
    }

    public static void write(Path project, Host host) throws IOException {
        Path target = file(project);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        String json = "{"
                + "\"owner\":\"" + OWNER + "\""
                + ",\"url\":\"" + escape(host.url()) + "\""
                + ",\"token\":\"" + escape(host.token()) + "\""
                + ",\"port\":" + host.port()
                + ",\"pid\":" + host.pid()
                + ",\"chromePid\":" + host.chromePid()
                + ",\"sessionId\":\"" + escape(host.sessionId()) + "\""
                + ",\"headed\":" + host.headed()
                + ",\"browser\":\"" + escape(host.browser()) + "\""
                + ",\"navigated\":" + host.navigated()
                + ",\"navError\":\"" + escape(host.navError()) + "\""
                + ",\"pageAttached\":" + host.pageAttached()
                + "}\n";
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    public static Optional<Host> read(Path project) {
        Path target = file(project);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            String text = Files.readString(target);
            if (!text.contains("\"owner\":\"" + OWNER + "\"")) return Optional.empty();
            return Optional.of(new Host(
                    field(text, "url"),
                    field(text, "token"),
                    (int) number(text, "port"),
                    number(text, "pid"),
                    number(text, "chromePid"),
                    field(text, "sessionId"),
                    text.contains("\"headed\":true"),
                    field(text, "browser"),
                    !text.contains("\"navigated\":false"),
                    field(text, "navError"),
                    text.contains("\"pageAttached\":true")
            ));
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    public static void delete(Path project) {
        try {
            Files.deleteIfExists(file(project));
        } catch (IOException ignored) {
            // The process kill is the stop that still runs.
        }
    }

    private static String field(String json, String name) {
        String key = "\"" + name + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        StringBuilder value = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                value.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (ch == '"') break;
            value.append(ch);
        }
        return value.toString();
    }

    private static long number(String json, String name) {
        String key = "\"" + name + "\":";
        int start = json.indexOf(key);
        if (start < 0) return 0L;
        start += key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return 0L;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
