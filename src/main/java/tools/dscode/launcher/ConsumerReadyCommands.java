package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.speed.ProcessTree;
import tools.dscode.store.StoreEntities;
import tools.dscode.store.StoreHistory;
import tools.dscode.store.StorePaths;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Phase B1 verbs. Page commands with no {@code --run-id} use the session
 * {@code start} wrote. Logging is {@code .pickleball/logs/consumer-cli.log}
 * (off when {@code pkb_consumer_log=off}), one date-stamped line per entity, capped at 1 MiB.
 */
final class ConsumerReadyCommands {
    static final String SESSION_NAME = "consumer-session.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long LOG_CAP = 1_048_576L;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build();

    private ConsumerReadyCommands() {
    }

    static int start(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String url = flag(parsed, "--url");
        String browser = flag(parsed, "--browser");
        if (url == null || url.isBlank() || browser == null || browser.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("start requires --url and --browser", "start"));
            return 2;
        }
        Path project = parsed.project();
        stopQuiet(project);
        Map<String, String> env = env(parsed);
        String id = "live" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String token = UUID.randomUUID().toString().replace("-", "");
        boolean headed = showHeaded(browser);
        Path logFile = project.resolve(".pickleball").resolve("logs").resolve("live-browser.log");
        Process process;
        try {
            process = spawnHost(project, id, url.trim(), browser.trim(), token, headed, logFile);
        } catch (IOException failure) {
            err.println("error: could not start the live browser\nfix: " + failure.getMessage() + "\n");
            return 1;
        }
        try {
            if (!awaitHealthy(process, project, logFile, err)) {
                destroyHost(process);
                stopQuiet(project);
                return 1;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("url", url.trim());
            body.put("browser", browser.trim());
            body.put("env", env);
            body.put("live", true);
            body.put("headed", headed);
            Path file = sessionFile(project);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body) + "\n", StandardCharsets.UTF_8);
            AgentControl.armChatCursor(project, id);
        } catch (IOException failure) {
            destroyHost(process);
            stopQuiet(project);
            err.println("error: could not publish the live session\nfix: " + failure.getMessage() + "\n");
            return 1;
        }
        out.println("session=" + id);
        out.println("url=" + url.trim());
        out.println("browser=" + browser.trim());
        if (!env.isEmpty()) out.println("env=" + envText(env));
        out.println("live=true");
        out.println("headed=" + headed);
        log(project, id, "start url=" + url.trim() + " browser=" + browser.trim() + " headed=" + headed);
        return 0;
    }

    /** Stops a host left by {@code start}. A missing host is a no-op. */
    static void stopQuiet(Path project) {
        if (project == null) return;
        long chromePid = chromePid(project);
        Optional<LiveBridgeFile.Bridge> bridge = LiveBridgeFile.read(project);
        if (bridge.isPresent()) {
            postStop(bridge.get());
            long pid = bridge.get().pid();
            ProcessHandle.of(pid).ifPresent(handle -> {
                try {
                    handle.onExit().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    ProcessTree.kill(pid);
                }
                if (handle.isAlive()) ProcessTree.kill(pid);
            });
        }
        if (chromePid > 0) ProcessTree.kill(chromePid);
        LiveBridgeFile.delete(project);
    }

    static int doctor(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        Path project = parsed.project();
        String chrome = BrowserSession.findChrome() == null ? "absent" : "present";
        String session = sessionId(project);
        if (session == null || session.isBlank()) session = "absent";
        CliHealth health = readCliHealth(project);
        boolean bridge = LiveBridgeFile.read(project).isPresent();
        out.println("chrome=" + chrome);
        out.println("session=" + session);
        out.println("health=" + health.status());
        out.println("headed=" + health.headed());
        out.println("pid=" + health.pid());
        out.println("bridge=" + (bridge ? "present" : "absent"));
        if (err == null) return 2;
        log(project, session, "doctor health=" + health.status());
        return 0;
    }

    private static boolean showHeaded(String browser) {
        if (GraphicsEnvironment.isHeadless()) return false;
        return browser == null || !browser.toLowerCase(Locale.ROOT).contains("headless");
    }

    private static Process spawnHost(
            Path project,
            String id,
            String url,
            String browser,
            String token,
            boolean headed,
            Path logFile
    ) throws IOException {
        Path argFile = project.resolve(".pickleball").resolve("live-browser").resolve(id).resolve("args.txt");
        Files.createDirectories(argFile.getParent());
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "", StandardCharsets.UTF_8);
        String classpath = System.getProperty("java.class.path", "");
        String body = String.join("\n",
                "-cp",
                quoteArg(classpath),
                "tools.dscode.common.agentfast.LiveBrowserHost",
                quoteArg(project.toAbsolutePath().normalize().toString()),
                quoteArg(id),
                quoteArg(url),
                quoteArg(browser),
                quoteArg(token),
                headed ? "true" : "false"
        ) + "\n";
        Files.writeString(argFile, body, StandardCharsets.UTF_8);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(javaBin.toString(), "@" + argFile.toAbsolutePath());
        builder.directory(project.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static String quoteArg(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        boolean simple = true;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch <= ' ' || ch == '"' || ch == '\\' || ch == '#') {
                simple = false;
                break;
            }
        }
        if (simple) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean awaitHealthy(Process process, Path project, Path logFile, java.io.PrintStream err) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    err.println("error: live browser host exited\nfix: see " + logFile + "\n" + tail(logFile));
                    return false;
                }
                Optional<LiveBridgeFile.Bridge> bridge = LiveBridgeFile.read(project);
                if (bridge.isPresent() && "ok".equals(healthStatus(bridge.get()))) return true;
                Thread.sleep(100L);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("error: live browser host wait was interrupted\nfix: retry start\n");
            return false;
        }
        err.println("error: live browser host did not become healthy\nfix: see " + logFile + "\n" + tail(logFile));
        return false;
    }

    private static String healthStatus(LiveBridgeFile.Bridge bridge) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + bridge.host() + ":" + bridge.port() + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + bridge.token())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "down";
            return JSON.readTree(response.body() == null ? "" : response.body()).path("status").asText("");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return "down";
        } catch (Exception failure) {
            return "down";
        }
    }

    private static void postStop(LiveBridgeFile.Bridge bridge) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + bridge.host() + ":" + bridge.port() + "/v1/stop"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + bridge.token())
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // The process kill below is the stop that still runs.
        }
    }

    private static void destroyHost(Process process) {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) ProcessTree.kill(process.pid());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            ProcessTree.kill(process.pid());
        }
    }

    private static long chromePid(Path project) {
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        if (!Files.isRegularFile(file)) return 0L;
        try {
            JsonNode node = JSON.readTree(file.toFile());
            return node.path("chromePid").asLong(0L);
        } catch (IOException failure) {
            return 0L;
        }
    }

    private record CliHealth(String status, String headed, String pid) {
        private static CliHealth absent() {
            return new CliHealth("absent", "absent", "absent");
        }
    }

    private static CliHealth readCliHealth(Path project) {
        Path file = project.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        if (!Files.isRegularFile(file)) return CliHealth.absent();
        try {
            JsonNode node = JSON.readTree(file.toFile());
            long pid = node.path("pid").asLong(0L);
            String headed = node.has("headed") ? Boolean.toString(node.path("headed").asBoolean(false)) : "absent";
            String pidText = pid > 0 ? Long.toString(pid) : "absent";
            boolean alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) return new CliHealth("down", headed, pidText);
            String url = node.path("url").asText("");
            String token = node.path("token").asText("");
            if (url.isBlank() || token.isBlank()) return new CliHealth("down", headed, pidText);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.endsWith("/") ? url + "health" : url + "/health"))
                    .timeout(HEALTH_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new CliHealth("down", headed, pidText);
            JsonNode body = JSON.readTree(response.body() == null ? "" : response.body());
            if (!"ok".equals(body.path("status").asText(""))) return new CliHealth("down", headed, pidText);
            if (body.has("headed")) headed = Boolean.toString(body.path("headed").asBoolean(false));
            return new CliHealth("ok", headed, pidText);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new CliHealth("down", "absent", "absent");
        } catch (Exception failure) {
            return new CliHealth("down", "absent", "absent");
        }
    }

    private static String tail(Path logFile) {
        if (logFile == null || !Files.isRegularFile(logFile)) return "";
        try {
            String text = Files.readString(logFile);
            int start = Math.max(0, text.length() - 1200);
            return text.substring(start);
        } catch (IOException failure) {
            return "";
        }
    }

    /** Blank when {@code start} has not published a session. */
    static String sessionId(Path project) {
        Map<String, Object> body = readSession(project);
        Object id = body.get("id");
        return id == null ? null : id.toString();
    }

    static int chatWait(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) runId = sessionId(parsed.project());
        if (runId == null || runId.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("start a session first", "chat_wait"));
            return 2;
        }
        long timeoutMillis = timeoutMillis(parsed, err);
        if (timeoutMillis < 0) return 2;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        try {
            while (true) {
                AgentControl.Result polled = AgentControl.execute(
                        parsed.project(), runId, "chat-poll", Map.of(), List.of(), parsed.coordination().agentId());
                int count = count(polled.body());
                if (count > 0) {
                    out.println(JSON.writeValueAsString(polled.body()));
                    log(parsed.project(), runId, "chat_wait count=" + count);
                    return 0;
                }
                if (System.nanoTime() >= deadline) {
                    out.println(JSON.writeValueAsString(polled.body()));
                    err.println("error: chat_wait timed out\nfix: chat-send, then chat_wait again.\n");
                    log(parsed.project(), runId, "chat_wait timeout");
                    return 1;
                }
                Thread.sleep(50L);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("error: chat_wait was interrupted\nfix: retry chat_wait\n");
            return 1;
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: retry chat_wait\n");
            return 1;
        }
    }

    static int historySummary(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        Map<String, String> status = new LinkedHashMap<>();
        Path runs = parsed.project().resolve(".pickleball").resolve("runs");
        if (Files.isDirectory(runs)) {
            try (var children = Files.list(runs)) {
                for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                    Path record = child.resolve("record.json");
                    if (!Files.isRegularFile(record)) continue;
                    status.put(child.getFileName().toString(), statusOf(record));
                }
            } catch (IOException failure) {
                err.println("error: " + failure.getMessage() + "\nfix: check .pickleball/runs\n");
                return 1;
            }
        }
        if (StorePaths.ready(parsed.project())) {
            try {
                for (StoreHistory.Run run : StoreHistory.list(parsed.project())) {
                    if (run.id() != null) status.put(run.id(), run.status() == null ? "" : run.status());
                }
            } catch (RuntimeException | java.sql.SQLException failure) {
                err.println("error: " + failure.getMessage() + "\nfix: store run history could not be read\n");
                return 1;
            }
        }
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int other = 0;
        for (String value : status.values()) {
            String token = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
            switch (token) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                default -> other++;
            }
        }
        out.println("runs=" + status.size());
        out.println("passed=" + passed);
        out.println("failed=" + failed);
        out.println("skipped=" + skipped);
        out.println("other=" + other);
        log(parsed.project(), "history", "summary runs=" + status.size());
        return 0;
    }

    static int entityLookup(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String ref = flag(parsed, "--ref");
        if (ref == null || ref.isBlank()) ref = positional(parsed);
        if (ref == null || ref.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("entity-lookup requires --ref", "entity-lookup"));
            return 2;
        }
        String source = ref.trim();
        if (source.startsWith("<") && source.endsWith(">") && source.length() > 2) {
            source = source.substring(1, source.length() - 1).trim();
        }
        String previous = System.getProperty("pkb_store_project");
        System.setProperty("pkb_store_project", parsed.project().toAbsolutePath().normalize().toString());
        try {
            Object value = StoreEntities.lookup(source);
            if (value == null) {
                out.println("miss");
                err.println("error: no entity for " + source + "\nfix: store entity put, or pass entity:Type#id.field\n");
                log(parsed.project(), source, "entity-lookup miss");
                return 1;
            }
            String printed = value instanceof String text ? text : JSON.writeValueAsString(value);
            out.println(printed);
            log(parsed.project(), source, "entity-lookup hit");
            return 0;
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: retry entity-lookup\n");
            return 1;
        } finally {
            if (previous == null) System.clearProperty("pkb_store_project");
            else System.setProperty("pkb_store_project", previous);
        }
    }

    static int openRun(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("open-run requires --run-id", "open-run"));
            return 2;
        }
        String tab = flag(parsed, "--tab");
        if (tab == null || tab.isBlank()) tab = "explorer";
        tab = tab.trim().toLowerCase(java.util.Locale.ROOT);
        if (!tab.equals("explorer") && !tab.equals("tracking")) {
            err.print(WorkbenchCliHelp.usageError("tab is explorer or tracking", "open-run"));
            return 2;
        }
        Path record = parsed.project().resolve(".pickleball").resolve("runs").resolve(runId).resolve("record.json");
        if (!Files.isRegularFile(record)) {
            err.println("error: run " + runId + " has no record\nfix: pass a run id that already finished or is in progress\n");
            return 1;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("tab", tab);
        try {
            Path file = parsed.project().resolve(".pickleball").resolve("workbench").resolve("open-run.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: check .pickleball/workbench\n");
            return 1;
        }
        out.println("tab=" + tab);
        out.println("run-id=" + runId);
        log(parsed.project(), runId, "open-run tab=" + tab);
        return 0;
    }

    static int bridgeHealth(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String url = flag(parsed, "--url");
        if (url == null || url.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("bridge-health requires --url", "bridge-health"));
            return 2;
        }
        String base = url.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URI uri;
        try {
            uri = URI.create(base + "/v1/health");
        } catch (IllegalArgumentException failure) {
            err.print(WorkbenchCliHelp.usageError("bridge-health --url is not a URL", "bridge-health"));
            return 2;
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(HEALTH_TIMEOUT).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(HEALTH_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();
            out.println("status=" + response.statusCode());
            out.println(responseBody);
            log(parsed.project(), "bridge", "health " + response.statusCode());
            return response.statusCode() == 200 ? 0 : 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            err.println("error: bridge-health was interrupted\nfix: retry bridge-health\n");
            return 1;
        } catch (IOException | RuntimeException failure) {
            err.println("error: " + failure.getMessage() + "\nfix: the bridge did not answer GET /v1/health within 2s\n");
            log(parsed.project(), "bridge", "health down");
            return 1;
        }
    }

    private static Map<String, Object> readSession(Path project) {
        Path file = sessionFile(project);
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(Files.readString(file), Map.class);
            return body == null ? Map.of() : body;
        } catch (IOException | RuntimeException failure) {
            return Map.of();
        }
    }

    private static Path sessionFile(Path project) {
        Path root = project == null ? Path.of("").toAbsolutePath() : project.toAbsolutePath().normalize();
        return root.resolve(".pickleball").resolve(SESSION_NAME);
    }

    private static String statusOf(Path record) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(Files.readString(record), Map.class);
            Object status = body == null ? null : body.get("status");
            return status == null ? "" : status.toString();
        } catch (IOException | RuntimeException failure) {
            return "";
        }
    }

    private static int count(Map<String, Object> body) {
        if (body == null) return 0;
        Object count = body.get("count");
        if (count instanceof Number number) return number.intValue();
        Object messages = body.get("messages");
        if (messages instanceof List<?> list) return list.size();
        return 0;
    }

    private static long timeoutMillis(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream err) {
        String raw = flag(parsed, "--timeout");
        if (raw == null || raw.isBlank()) return 30_000L;
        try {
            double seconds = Double.parseDouble(raw.trim());
            if (seconds < 0 || seconds > 120) {
                err.print(WorkbenchCliHelp.usageError("chat_wait --timeout is seconds from 0 to 120", "chat_wait"));
                return -1;
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException failure) {
            err.print(WorkbenchCliHelp.usageError("chat_wait --timeout is seconds from 0 to 120", "chat_wait"));
            return -1;
        }
    }

    private static Map<String, String> env(WorkbenchCommandLine.Parsed parsed) {
        Map<String, String> env = new LinkedHashMap<>();
        String[] args = parsed.forwarded();
        if (args == null) return env;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            String value = null;
            if (token.startsWith("--env=")) value = token.substring("--env=".length());
            else if ("--env".equals(token) && index + 1 < args.length) value = args[++index];
            if (value == null || value.isBlank()) continue;
            for (String piece : value.split(",")) {
                int equals = piece.indexOf('=');
                if (equals <= 0) continue;
                env.put(piece.substring(0, equals).trim(), piece.substring(equals + 1));
            }
        }
        return env;
    }

    private static String envText(Map<String, String> env) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (!text.isEmpty()) text.append(',');
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    private static String positional(WorkbenchCommandLine.Parsed parsed) {
        String[] args = parsed.forwarded();
        if (args == null) return null;
        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if (token == null || token.startsWith("-")) continue;
            if (token.contains("#") || token.startsWith("entity:") || token.startsWith("<entity:")) return token;
        }
        return null;
    }

    private static String flag(WorkbenchCommandLine.Parsed parsed, String name) {
        String[] args = parsed.forwarded();
        if (args == null) return null;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
            if (name.equals(token) && index + 1 < args.length) return args[index + 1];
        }
        return null;
    }

    private static void log(Path project, String entity, String message) {
        if (project == null) return;
        if ("off".equalsIgnoreCase(System.getProperty("pkb_consumer_log", "on"))) return;
        try {
            Path file = project.toAbsolutePath().normalize()
                    .resolve(".pickleball").resolve("logs").resolve("consumer-cli.log");
            Files.createDirectories(file.getParent());
            String line = Instant.now() + "\t" + (entity == null ? "-" : entity) + "\t" + message + "\n";
            if (Files.isRegularFile(file) && Files.size(file) > LOG_CAP) {
                Files.writeString(file, line, StandardCharsets.UTF_8);
            } else {
                Files.writeString(
                        file,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (IOException ignored) {
            // A log line must not change the command result.
        }
    }
}
