package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.ControlProtocol;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One-shot consumer-JVM client for a long-lived headless Workbench CLI session.
 * Maven exec always exits; the controller {@code session} process stays up.
 */
public final class WorkbenchSessionCommands {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL = Duration.ofMillis(200);

    private WorkbenchSessionCommands() {
    }

    @FunctionalInterface
    interface DetachedStarter {
        Process start(Path project, String tags, String name, Path logFile) throws IOException;
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, WorkbenchSessionCommands::startControllerSession, defaultHttp());
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter
    ) {
        return run(args, out, err, starter, defaultHttp());
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter,
            HttpClient http
    ) {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(args);
        SessionFlags flags = SessionFlags.parse(args);
        try {
            return switch (parsed.command()) {
                case "isolate", "session-start" -> sessionStart(parsed, out, err, starter, http);
                case "execute-step" -> executeStep(parsed, flags, out, err, http);
                case "status" -> status(parsed, flags, out, err, http);
                case "events" -> events(parsed, out, err, http);
                case "stop", "kill" -> stop(parsed, out, err, http);
                default -> {
                    err.println("Unknown Workbench session command: " + parsed.command());
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("Workbench " + parsed.command() + " failed: " + failure.getMessage());
            return 1;
        }
    }

    static Process startControllerSession(Path project, String tags, String name, Path logFile) throws IOException {
        Path controllerJar = PickleballWorkbenchLauncher.extractEmbeddedPayload(project);
        List<String> forwarded = new ArrayList<>();
        forwarded.add("session");
        forwarded.add(project.toString());
        if (tags != null && !tags.isBlank()) {
            forwarded.add("--tags");
            forwarded.add(tags);
        }
        if (name != null && !name.isBlank()) {
            forwarded.add("--name");
            forwarded.add(name);
        }
        return startDetached(project, PickleballWorkbenchLauncher.command(
                controllerJar, forwarded.toArray(String[]::new)
        ), logFile);
    }

    static Process startDetached(Path project, List<String> command, Path logFile) throws IOException {
        Files.createDirectories(logFile.getParent());
        if (!Files.exists(logFile)) Files.createFile(logFile);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectInput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static int sessionStart(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            DetachedStarter starter,
            HttpClient http
    ) {
        Path project = parsed.project();
        Optional<SessionState> existing = readHealthy(project, http);
        if (existing.isPresent()) {
            SessionState state = existing.get();
            out.println("ACK SESSION already-running pid=" + state.pid() + " url=" + state.url());
            return 0;
        }

        Path logFile = project.resolve(".pickleball").resolve("workbench").resolve("session.log");
        Process process;
        try {
            process = starter.start(project, parsed.tags(), parsed.name(), logFile);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not start Workbench session: " + failure.getMessage(), failure);
        }

        long deadline = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<SessionState> ready = readHealthy(project, http);
            if (ready.isPresent()) {
                SessionState state = ready.get();
                out.println("ACK SESSION pid=" + state.pid() + " url=" + state.url());
                return 0;
            }
            if (process != null && !process.isAlive()) {
                Optional<SessionState> afterExit = readHealthy(project, http);
                if (afterExit.isPresent()) {
                    SessionState state = afterExit.get();
                    out.println("ACK SESSION pid=" + state.pid() + " url=" + state.url());
                    return 0;
                }
                err.println("Workbench session process exited before becoming healthy. See " + logFile);
                return 1;
            }
            sleep(POLL);
        }
        err.println("Workbench session did not become healthy within " + HEALTH_TIMEOUT.toSeconds() + "s. See " + logFile);
        return 1;
    }

    private static int executeStep(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        SessionState state = requireSession(parsed.project(), http);
        String text = flags.text;
        if (text == null || text.isBlank()) {
            err.println("Usage: execute-step <gherkin> or execute-step --text=<gherkin> [--ack-only]");
            return 2;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "execute-step");
        body.put("text", text);
        if (flags.id != null && !flags.id.isBlank()) body.put("id", flags.id);
        JsonNode ack = postJson(http, state, "/commands", body);
        String id = textOr(ack, "id", "");
        String status = textOr(ack, "status", "QUEUED");
        out.println("ACK " + id + " " + status);
        if (flags.ackOnly || !flags.wait) return 0;
        return waitForDone(http, state, id, out, err);
    }

    private static int status(
            WorkbenchCommandLine.Parsed parsed,
            SessionFlags flags,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Optional<SessionState> state = readHealthy(parsed.project(), http);
        if (state.isEmpty()) {
            err.println("No healthy Workbench CLI session. Run isolate / session-start first.");
            return 1;
        }
        String id = flags.id;
        if (id == null || id.isBlank()) {
            SessionState session = state.get();
            out.println("SESSION pid=" + session.pid() + " url=" + session.url() + " mode=" + session.mode());
            return 0;
        }
        try {
            JsonNode view = getJson(http, state.get(), "/commands/" + id);
            String status = textOr(view, "status", "");
            out.println(id + " " + status);
            if (view.has("result")) out.println(view.get("result").toString());
            return terminalExit(status);
        } catch (RuntimeException failure) {
            err.println("DONE " + id + " TIMEOUT");
            return 1;
        }
    }

    private static int events(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        SessionState state = requireSession(parsed.project(), http);
        try {
            JsonNode events = postJson(http, state, "/tools/workbench_events", Map.of());
            out.println(events.toString());
            return 0;
        } catch (RuntimeException failure) {
            err.println("Workbench events failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int stop(
            WorkbenchCommandLine.Parsed parsed,
            PrintStream out,
            PrintStream err,
            HttpClient http
    ) {
        Optional<SessionState> state = readHealthy(parsed.project(), http);
        if (state.isEmpty()) {
            out.println("ACK SESSION already-stopped");
            return 0;
        }
        SessionState session = state.get();
        try {
            postJson(http, session, "/commands", Map.of("op", "stop"));
        } catch (RuntimeException ignored) {
            // Fall through to pid destroy.
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (readHealthy(parsed.project(), http).isEmpty() && !pidAlive(session.pid())) {
                out.println("ACK SESSION stopped pid=" + session.pid());
                return 0;
            }
            sleep(POLL);
        }
        ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroy);
        sleep(Duration.ofMillis(400));
        if (pidAlive(session.pid())) ProcessHandle.of(session.pid()).ifPresent(ProcessHandle::destroyForcibly);
        try {
            Files.deleteIfExists(sessionFile(parsed.project()));
        } catch (IOException ignored) {
            // Disposable session state.
        }
        out.println("ACK SESSION killed pid=" + session.pid());
        return 0;
    }

    private static int waitForDone(
            HttpClient http,
            SessionState state,
            String id,
            PrintStream out,
            PrintStream err
    ) {
        String last = "";
        while (true) {
            JsonNode view;
            try {
                if (!pidAlive(state.pid()) && readHealthy(state.project(), http).isEmpty()) {
                    err.println("DONE " + id + " TIMEOUT");
                    return 1;
                }
                view = getJson(http, state, "/commands/" + id);
            } catch (RuntimeException failure) {
                err.println("DONE " + id + " TIMEOUT");
                return 1;
            }
            String status = textOr(view, "status", "");
            if ("STILL_WORKING".equals(status) || "RUNNING".equals(status) || "QUEUED".equals(status)) {
                if (!status.equals(last)) {
                    out.println("STILL_WORKING " + id);
                    last = status;
                }
                sleep(POLL);
                continue;
            }
            if ("TIMEOUT".equals(status)) {
                err.println("DONE " + id + " TIMEOUT");
                return 1;
            }
            out.println("DONE " + id + " " + status);
            if (view.has("result")) out.println(view.get("result").toString());
            return terminalExit(status);
        }
    }

    private static int terminalExit(String status) {
        return "SUCCESS".equals(status) ? 0 : 1;
    }

    private static SessionState requireSession(Path project, HttpClient http) {
        return readHealthy(project, http).orElseThrow(() -> new IllegalStateException(
                "No healthy Workbench CLI session. Run isolate / session-start first."
        ));
    }

    static Optional<SessionState> readHealthy(Path project, HttpClient http) {
        Path file = sessionFile(project);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(file.toFile());
            SessionState state = SessionState.from(project, root);
            if (!pidAlive(state.pid())) return Optional.empty();
            JsonNode health = getJson(http, state, "/health");
            if (!"ok".equals(textOr(health, "status", ""))) return Optional.empty();
            return Optional.of(state);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static Path sessionFile(Path project) {
        return project.toAbsolutePath().normalize().resolve(ControlProtocol.CLI_SESSION_STATE_RELATIVE);
    }

    private static boolean pidAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static JsonNode postJson(HttpClient http, SessionState state, String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(state.url() + path))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + state.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
            }
            return JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Workbench session.", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static JsonNode getJson(HttpClient http, SessionState state, String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(state.url() + path))
                    .timeout(HTTP_TIMEOUT)
                    .GET();
            if (!"/health".equals(path)) {
                builder.header("Authorization", "Bearer " + state.token());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " " + response.body());
            }
            return JSON.readTree(response.body().isBlank() ? "{}" : response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Workbench session.", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Workbench session.");
        }
    }

    private static HttpClient defaultHttp() {
        return HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    record SessionState(Path project, String url, String token, long pid, String mode) {
        static SessionState from(Path project, JsonNode root) {
            return new SessionState(
                    project.toAbsolutePath().normalize(),
                    root.path("url").asText(""),
                    root.path("token").asText(""),
                    root.path("pid").asLong(0L),
                    root.path("mode").asText("")
            );
        }
    }

    static final class SessionFlags {
        final String text;
        final String id;
        final boolean ackOnly;
        final boolean wait;

        private SessionFlags(String text, String id, boolean ackOnly, boolean wait) {
            this.text = text;
            this.id = id;
            this.ackOnly = ackOnly;
            this.wait = wait;
        }

        static SessionFlags parse(String[] args) {
            String text = null;
            String id = null;
            boolean ackOnly = false;
            boolean wait = true;
            List<String> words = new ArrayList<>();
            if (args == null) return new SessionFlags(null, null, false, true);
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (token == null) continue;
                if (token.startsWith("--text=")) {
                    text = token.substring("--text=".length());
                    continue;
                }
                if ("--text".equals(token) && index + 1 < args.length) {
                    text = args[++index];
                    continue;
                }
                if (token.startsWith("--id=")) {
                    id = token.substring("--id=".length());
                    continue;
                }
                if ("--id".equals(token) && index + 1 < args.length) {
                    id = args[++index];
                    continue;
                }
                if ("--ack-only".equals(token)) {
                    ackOnly = true;
                    wait = false;
                    continue;
                }
                if ("--wait".equals(token)) {
                    wait = true;
                    continue;
                }
                if ("--no-wait".equals(token)) {
                    wait = false;
                    continue;
                }
                if (token.startsWith("--tags=") || token.startsWith("--name=")) continue;
                if (("--tags".equals(token) || "--name".equals(token)) && index + 1 < args.length) {
                    index++;
                    continue;
                }
                if (token.startsWith("-")) continue;
                if (looksLikeProject(token)) continue;
                words.add(token);
            }
            if (text == null && !words.isEmpty()) {
                if ("status".equals(args[0]) && words.size() == 1) {
                    id = words.getFirst();
                } else {
                    text = String.join(" ", words);
                    if ("status".equals(args[0]) && id == null && words.size() == 1) id = words.getFirst();
                }
            }
            return new SessionFlags(text, id, ackOnly, wait);
        }

        private static boolean looksLikeProject(String token) {
            if (token == null || token.isBlank()) return false;
            if (".".equals(token) || "..".equals(token)) return true;
            if (token.contains("/") || token.contains("\\")) return true;
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-d")) return true;
            return Files.isDirectory(Path.of(token));
        }
    }
}
