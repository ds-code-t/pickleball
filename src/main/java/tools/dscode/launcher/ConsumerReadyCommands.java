package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.store.StoreEntities;
import tools.dscode.store.StoreHistory;
import tools.dscode.store.StorePaths;

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
import java.util.Map;
import java.util.UUID;

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

    private ConsumerReadyCommands() {
    }

    static int start(WorkbenchCommandLine.Parsed parsed, java.io.PrintStream out, java.io.PrintStream err) {
        String url = flag(parsed, "--url");
        String browser = flag(parsed, "--browser");
        if (url == null || url.isBlank() || browser == null || browser.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("start requires --url and --browser", "start"));
            return 2;
        }
        Map<String, String> env = env(parsed);
        String id = "live" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("url", url.trim());
        body.put("browser", browser.trim());
        body.put("env", env);
        body.put("live", true);
        try {
            Path file = sessionFile(parsed.project());
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writeValueAsString(body) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            err.println("error: could not publish the live session\nfix: " + failure.getMessage() + "\n");
            return 1;
        }
        out.println("session=" + id);
        out.println("url=" + url.trim());
        out.println("browser=" + browser.trim());
        if (!env.isEmpty()) out.println("env=" + envText(env));
        out.println("live=true");
        log(parsed.project(), id, "start url=" + url.trim() + " browser=" + browser.trim());
        return 0;
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
