package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CLI side of the protocol host: reuses the project's running host when its endpoints match, otherwise starts one
 * (a child JVM on this classpath, detached, logging to {@code .pickleball/proto/host.log}) and sends one command.
 */
public final class ProtoClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private ProtoClient() {
    }

    /** The running host's info, or null. */
    public static JsonNode running(Path project) {
        Path file = ProtoHost.hostFile(project);
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode info = ProtoJson.MAPPER.readTree(Files.readString(file));
            long pid = info.path("pid").asLong();
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return info;
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    /** Ensures a host for these endpoints (null keeps whatever is running) and sends one command. */
    public static ObjectNode call(Path project, String cdp, String bidi, String run, String verb, Map<String, String> flags) {
        JsonNode info = running(project);
        boolean explicit = cdp != null || bidi != null;
        if (info != null && explicit && (!Objects.equals(cdp, text(info, "cdp")) || !Objects.equals(bidi, text(info, "bidi")))) {
            send(info, "host.stop", Map.of());
            waitGone(info.path("pid").asLong());
            info = null;
        }
        if (info == null) {
            if (!explicit) {
                throw new IllegalStateException("error: no protocol host and no browser endpoint\n"
                        + "fix: start a live browser, or pass --cdp-endpoint=<url|chrome> or --bidi-endpoint=<ws url>");
            }
            info = start(project, cdp, bidi, run);
        }
        return send(info, verb, flags);
    }

    private static String text(JsonNode info, String key) {
        return info.hasNonNull(key) ? info.get(key).asText() : null;
    }

    static ObjectNode send(JsonNode info, String verb, Map<String, String> flags) {
        ObjectNode body = ProtoJson.obj().put("verb", verb);
        ObjectNode f = body.putObject("flags");
        flags.forEach(f::put);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + info.path("port").asInt() + "/cmd"))
                    .timeout(Duration.ofSeconds(180))
                    .header("X-Pkb-Token", info.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return (ObjectNode) ProtoJson.MAPPER.readTree(response.body());
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("error: protocol host did not answer " + verb + ": " + failure.getMessage()
                    + "\nfix: browser-events --stop, then retry; see .pickleball/proto/host.log");
        }
    }

    public static boolean stop(Path project) {
        JsonNode info = running(project);
        if (info == null) return false;
        send(info, "host.stop", Map.of());
        waitGone(info.path("pid").asLong());
        return true;
    }

    private static void waitGone(long pid) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static JsonNode start(Path project, String cdp, String bidi, String run) {
        try {
            Files.createDirectories(ProtoHost.dir(project));
            Files.deleteIfExists(ProtoHost.hostFile(project));
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
            for (String name : System.getProperties().stringPropertyNames()) {
                if (name.startsWith("pkb_") || name.startsWith("pkb.")) command.add("-D" + name + "=" + System.getProperty(name));
            }
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(ProtoHost.class.getName());
            command.add("--project=" + project.toAbsolutePath());
            if (cdp != null) command.add("--cdp=" + cdp);
            if (bidi != null) command.add("--bidi=" + bidi);
            if (run != null) command.add("--run=" + run);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(ProtoHost.dir(project).resolve("host.log").toFile()));
            builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            Process process = builder.start();
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode info = running(project);
                if (info != null && info.path("pid").asLong() == process.pid()) return info;
                if (!process.isAlive()) break;
                Thread.sleep(50);
            }
            throw new IllegalStateException("error: protocol host did not start\nfix: see " + ProtoHost.dir(project).resolve("host.log"));
        } catch (IOException failure) {
            throw new IllegalStateException("error: protocol host did not start: " + failure.getMessage() + "\nfix: check java.home and the classpath");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("error: interrupted while starting the protocol host");
        }
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }
}
