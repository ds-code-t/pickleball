package tools.dscode.control.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The live page the CLI and MCP share. A Workbench isolate worker
 * ({@code live-bridge.json}) wins when no session id is given. Otherwise the
 * {@code start} host is used. A session id selects that one process.
 */
public final class LiveSessionAttach {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** {@code kind} is {@code worker} or {@code start}. */
    public record Attached(String kind, LiveBridgeFile.Bridge bridge) {
    }

    public record Posted(int exit, String bodyJson, String error) {
    }

    private LiveSessionAttach() {
    }

    /**
     * @param strict when true, a session id that is not a live worker or start host is a miss.
     *               When false, that id is ignored and the current live session is used.
     */
    public static Optional<Attached> resolve(Path project, String sessionId, boolean strict) {
        String wanted = sessionId == null ? "" : sessionId.trim();
        if (!wanted.isEmpty()) {
            Optional<Attached> exact = match(project, wanted);
            if (exact.isPresent() || strict) return exact;
        }
        return current(project);
    }

    /** Worker when its pid is alive, otherwise the start host. */
    public static Optional<Attached> current(Path project) {
        Optional<LiveBridgeFile.Bridge> worker = LiveBridgeFile.read(project);
        if (worker.isPresent()) return Optional.of(new Attached("worker", worker.get()));
        return startHost(project);
    }

    private static Optional<Attached> match(Path project, String sessionId) {
        Optional<LiveBridgeFile.Bridge> worker = LiveBridgeFile.read(project);
        if (worker.isPresent() && sessionId.equals(worker.get().sessionId())) {
            return Optional.of(new Attached("worker", worker.get()));
        }
        Optional<Attached> host = startHost(project);
        if (host.isPresent() && sessionId.equals(host.get().bridge().sessionId())) return host;
        return Optional.empty();
    }

    private static Optional<Attached> startHost(Path project) {
        Optional<LiveHostFile.Host> read = LiveHostFile.read(project);
        if (read.isEmpty()) return Optional.empty();
        LiveHostFile.Host found = read.get();
        if (!alive(found.pid())) return Optional.empty();
        String hostName = "127.0.0.1";
        try {
            URI uri = URI.create(found.url() == null ? "" : found.url());
            if (uri.getHost() != null && !uri.getHost().isBlank()) hostName = uri.getHost();
        } catch (RuntimeException ignored) {
            // The stored url is still the health base the parent wrote.
        }
        String session = found.sessionId() == null ? "" : found.sessionId();
        return Optional.of(new Attached("start", new LiveBridgeFile.Bridge(
                hostName, found.port(), found.token(), found.pid(), session
        )));
    }

    public static Duration httpTimeout(String op, Map<String, String> args) {
        long declaredMs = 45_000L;
        if (waits(op)) {
            try {
                declaredMs = declaredWaitMs(op, args);
            } catch (NumberFormatException ignored) {
                declaredMs = 30_000L;
            }
        }
        long seconds = (declaredMs + 999L) / 1000L;
        return Duration.ofSeconds(Math.min(180L, Math.max(60L, seconds + 15L)));
    }

    /** Longest documented wait for a waiting verb, in milliseconds. */
    public static final long MAX_WAIT_MS = 120_000L;

    /**
     * The wait a waiting verb declared, in milliseconds, capped at {@link #MAX_WAIT_MS}.
     * chat-turn and chat_wait take {@code --timeout} in seconds, ui-poll and chat-poll take
     * {@code --wait} in seconds, watch takes {@code --duration} in milliseconds, and
     * ask-and-wait, answer-wait, wait and wait-for take {@code --timeout} in milliseconds.
     * A trailing {@code s} or {@code ms} wins over the verb's bare unit.
     * Text that is not a number throws {@link NumberFormatException}.
     */
    public static long declaredWaitMs(String op, Map<String, String> args) {
        String key;
        boolean bareSeconds;
        switch (op == null ? "" : op) {
            case "chat-turn", "chat_wait" -> { key = "timeout"; bareSeconds = true; }
            case "ui-poll", "chat-poll" -> { key = "wait"; bareSeconds = true; }
            case "watch" -> { key = "duration"; bareSeconds = false; }
            default -> { key = "timeout"; bareSeconds = false; }
        }
        String raw = args == null ? null : args.get(key);
        if ((raw == null || raw.isBlank()) && "watch".equals(op) && args != null) raw = args.get("timeout");
        long ms = 30_000L;
        if (raw != null && !raw.isBlank()) {
            String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (text.endsWith("ms")) {
                ms = Math.round(Double.parseDouble(text.substring(0, text.length() - 2).trim()));
            } else if (text.endsWith("s")) {
                ms = Math.round(Double.parseDouble(text.substring(0, text.length() - 1).trim()) * 1000.0);
            } else {
                double value = Double.parseDouble(text);
                ms = Math.round(bareSeconds ? value * 1000.0 : value);
            }
        }
        if (ms < 0) ms = 0;
        return Math.min(MAX_WAIT_MS, ms);
    }

    /** The next step after a client-side timeout, per verb. */
    static String timeoutFix(String op) {
        return switch (op == null ? "" : op) {
            case "chat-turn" -> "chat-read --last=5 shows a reply that arrived late; then chat-turn again (--timeout max 120)";
            case "chat_wait" -> "chat-read --last=5, then chat_wait again (--timeout max 120)";
            case "ask-and-wait", "answer-wait" -> "answer-wait --id=<question> reads a stored choice (--timeout max 120s)";
            case "ui-poll" -> "ui-poll again with the returned cursor (--wait max 120)";
            case "watch" -> "watch again (--duration max 120000 ms)";
            case "wait", "wait-for" -> op + " again (--timeout max 120000 ms)";
            default -> op + " again";
        };
    }

    public static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId
    ) {
        String host = bridge.host() == null || bridge.host().isBlank() ? "127.0.0.1" : bridge.host();
        URI uri = URI.create("http://" + host + ":" + bridge.port() + "/v1/agent/command");
        AgentFastLog.log(project, "forward", "op=" + op + " port=" + bridge.port());
        return post(bridge, op, args, extra, project, runId, agentId, httpTimeout(op, args), uri);
    }

    public static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId,
            Duration timeout
    ) {
        String host = bridge.host() == null || bridge.host().isBlank() ? "127.0.0.1" : bridge.host();
        URI uri = URI.create("http://" + host + ":" + bridge.port() + "/v1/agent/command");
        return post(bridge, op, args, extra, project, runId, agentId, timeout, uri);
    }

    private static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId,
            Duration timeout,
            URI uri
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("op", op == null ? "" : op);
        payload.put("args", args == null ? Map.of() : args);
        payload.put("project", project == null ? "" : project.toAbsolutePath().normalize().toString());
        payload.put("runId", runId == null ? "" : runId);
        payload.put("agentId", agentId == null ? "" : agentId);
        payload.put("extraArgs", extra == null ? List.of() : extra);
        try {
            byte[] bytes = JsonLite.write(payload).getBytes(StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout == null ? Duration.ofSeconds(60) : timeout)
                    .header("Authorization", "Bearer " + bridge.token())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String text = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() != 200) {
                return new Posted(1, "", "error: live worker HTTP " + response.statusCode());
            }
            ClientHeartbeat.touch(project);
            Object parsed = JsonLite.parse(text.isBlank() ? "{}" : text);
            Map<String, Object> node = JsonLite.object(parsed);
            if (node == null) return new Posted(1, "", "error: live worker returned a non-object");
            int exit = 1;
            Object exitValue = node.get("exit");
            if (exitValue instanceof Number number) exit = number.intValue();
            Object body = node.get("body");
            String bodyJson = body == null ? "{}" : JsonLite.write(body);
            return new Posted(exit, bodyJson, "");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Posted(1, "", "error: live worker interrupted");
        } catch (Exception failure) {
            return failed(bridge, op, failure);
        }
    }

    /** A live pid is a timeout. Unreachable is only a worker that is actually gone. */
    static Posted failed(LiveBridgeFile.Bridge bridge, String op, Exception failure) {
        boolean alive = bridge != null && bridge.pid() > 0
                && ProcessHandle.of(bridge.pid()).map(ProcessHandle::isAlive).orElse(false);
        if (alive && waits(op)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("op", op);
            body.put("error", "timeout");
            body.put("sentence", "timed out waiting for the live worker");
            body.put("fix", timeoutFix(op));
            return new Posted(1, JsonLite.write(body), "");
        }
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return new Posted(1, "", "error: live worker unreachable: " + message);
    }

    private static boolean waits(String op) {
        return "chat-turn".equals(op) || "chat_wait".equals(op) || "ask-and-wait".equals(op)
                || "answer-wait".equals(op) || "ui-poll".equals(op) || "chat-poll".equals(op)
                || "watch".equals(op) || "wait".equals(op) || "wait-for".equals(op);
    }

    private static boolean alive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
