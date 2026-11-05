package dev.domcomposite;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Thin client for the {@code composite} CLI.
 *
 * <p>Every call is one line to a long-lived {@code composite serve --stdio}
 * child. This class does not capture, query, resolve, or validate. It is safe
 * to share across threads and it is not a singleton. {@link #openSession}
 * gives the calling thread its own named session ({@code --process} unless the
 * config sets it false) so those threads do not share a browser.
 *
 * <p>The child gets its own {@code COMPOSITE_SESSION_DIR}. Logging stays off
 * unless {@code COMPOSITE_LOG*} is already in the environment or a log map is
 * passed in. The log keys match the other bindings: {@code entities},
 * {@code level}, {@code dir}, {@code capBytes} or {@code cap_bytes},
 * {@code maxBytes} or {@code max_bytes}, {@code rate}.
 */
public final class Client implements AutoCloseable {
    private static final AtomicLong IDS = new AtomicLong();

    private final String bin;
    private final Map<String, String> env;
    private final Path sessionDir;
    private final Object lock = new Object();
    private final Map<String, Session> owned = new ConcurrentHashMap<>();
    private final ThreadLocal<Session> threadSession = new ThreadLocal<>();
    private final AtomicLong nextId = new AtomicLong();

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private volatile boolean closed;

    public Client() {
        this((String) null, null, null);
    }

    public Client(String bin) {
        this(bin, null, null);
    }

    public Client(Path bin) {
        this(bin == null ? null : bin.toString(), null, null);
    }

    /** {@code log} is the same map the JS and Python clients take. */
    public Client(Map<String, ?> log) {
        this((String) null, null, log);
    }

    public Client(String bin, Map<String, ?> log) {
        this(bin, null, log);
    }

    public Client(Path bin, Map<String, ?> log) {
        this(bin == null ? null : bin.toString(), null, log);
    }

    private Client(String bin, Map<String, String> baseEnv, Map<String, ?> log) {
        this.bin = bin == null || bin.isBlank() ? findBin() : bin;
        try {
            this.sessionDir = Files.createTempDirectory("composite-java-sessions-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> base = baseEnv == null ? System.getenv() : baseEnv;
        this.env = childEnv(base, log, sessionDir);
    }

    /** Test hook: {@code baseEnv} replaces the inherited environment. */
    static Client forTest(String bin, Map<String, String> baseEnv, Map<String, ?> log) {
        return new Client(bin, baseEnv, log);
    }

    static Map<String, String> childEnv(Map<String, String> base, Map<String, ?> log, Path sessionDir) {
        Map<String, String> env = new HashMap<>();
        if (base != null) {
            for (Map.Entry<String, String> entry : base.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    env.put(entry.getKey(), entry.getValue());
                }
            }
        }
        applyLog(env, log);
        if (sessionDir != null) {
            env.put("COMPOSITE_SESSION_DIR", sessionDir.toAbsolutePath().toString());
        }
        return env;
    }

    private static void applyLog(Map<String, String> env, Map<String, ?> log) {
        if (log == null || log.isEmpty()) return;
        putLog(env, "COMPOSITE_LOG", first(log, "entities", "COMPOSITE_LOG"));
        putLog(env, "COMPOSITE_LOG_LEVEL", first(log, "level", "COMPOSITE_LOG_LEVEL"));
        putLog(env, "COMPOSITE_LOG_DIR", first(log, "dir", "COMPOSITE_LOG_DIR"));
        putLog(env, "COMPOSITE_LOG_CAP_BYTES", first(log, "capBytes", "cap_bytes", "COMPOSITE_LOG_CAP_BYTES"));
        putLog(env, "COMPOSITE_LOG_MAX_BYTES", first(log, "maxBytes", "max_bytes", "COMPOSITE_LOG_MAX_BYTES"));
        putLog(env, "COMPOSITE_LOG_RATE", first(log, "rate", "COMPOSITE_LOG_RATE"));
    }

    private static Object first(Map<String, ?> log, String... keys) {
        for (String key : keys) {
            if (log.containsKey(key) && log.get(key) != null) return log.get(key);
        }
        return null;
    }

    private static void putLog(Map<String, String> env, String name, Object value) {
        if (value == null) return;
        String text = String.valueOf(value);
        if (!text.isEmpty()) env.put(name, text);
    }

    /**
     * {@code COMPOSITE_BIN} when it points at a file, otherwise the newest
     * {@code target/debug/composite} or {@code target/release/composite} walked
     * up from the working directory, otherwise {@code composite} on {@code PATH}.
     */
    public static String findBin() {
        String envBin = System.getenv("COMPOSITE_BIN");
        if (envBin != null && !envBin.isEmpty() && Files.isRegularFile(Path.of(envBin))) {
            return envBin;
        }
        List<Path> roots = new ArrayList<>();
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            roots.add(dir);
            dir = dir.getParent();
        }
        Path best = null;
        FileTime bestTime = null;
        for (Path root : roots) {
            for (String rel : new String[] {"target/debug/composite", "target/release/composite"}) {
                Path candidate = root.resolve(rel);
                if (!Files.isRegularFile(candidate)) continue;
                FileTime mtime;
                try {
                    mtime = Files.getLastModifiedTime(candidate);
                } catch (IOException e) {
                    continue;
                }
                if (bestTime == null || mtime.compareTo(bestTime) > 0) {
                    best = candidate;
                    bestTime = mtime;
                }
            }
        }
        return best == null ? "composite" : best.toString();
    }

    public String bin() {
        return bin;
    }

    public Path sessionDir() {
        return sessionDir;
    }

    public boolean isClosed() {
        return closed;
    }

    boolean workerAlive() {
        synchronized (lock) {
            return process != null && process.isAlive();
        }
    }

    /**
     * Launch config is a {@code Map} or a JSON object string, with the same
     * fields as {@code session create} / {@code POST /v1/session/create}.
     * The calling thread keeps this session. A second call on that thread throws
     * {@link InvalidException} until the session is closed.
     */
    public Session openSession(Object config) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("client is closed");
            Session existing = threadSession.get();
            if (existing != null && !existing.isClosed()) {
                throw InvalidException.local("this thread already has session " + existing.id());
            }
            ObjectNode body = asObject(config);
            if (!Argv.hasText(body, "id")) body.put("id", freshId());
            JsonNode processFlag = body.get("process");
            if (processFlag == null || processFlag.isNull()) body.put("process", true);
            JsonNode record = exchangeLocked(Argv.from("session/create", body));
            String id = record.path("id").asText(body.path("id").asText());
            if (id.isEmpty()) id = body.path("id").asText();
            Session session = new Session(this, id, record);
            owned.put(id, session);
            threadSession.set(session);
            return session;
        }
    }

    /** The session {@link #openSession} bound to the calling thread, or null. */
    public Session currentSession() {
        Session session = threadSession.get();
        if (session == null || session.isClosed()) return null;
        return session;
    }

    public JsonNode call(List<String> argv) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("client is closed");
            return exchangeLocked(List.copyOf(argv));
        }
    }

    /**
     * {@code command} is a CLI command ({@code query}, {@code session/create},
     * {@code click}, …). {@code body} is a {@code Map}, a JSON object string, or null.
     */
    public JsonNode command(String command, Object body) {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("client is closed");
            return exchangeLocked(Argv.from(command, asObject(body)));
        }
    }

    /** Forwards `composite desktop <verb>`. No desktop logic lives here. */
    public JsonNode desktop(String verb, Object body) {
        return command("desktop/" + verb, body);
    }

    public JsonNode version() {
        return command("version", null);
    }

    public JsonNode capabilities() {
        return command("capabilities", null);
    }

    public JsonNode query(Object body) {
        ObjectNode obj = asObject(body);
        if (!Argv.hasText(obj, "path") && !Argv.hasText(obj, "file")) {
            obj = bindLive(obj, true);
        }
        return command("query", obj);
    }

    public JsonNode resolve(Object body) {
        ObjectNode obj = asObject(body);
        if (!Argv.hasText(obj, "path") && !Argv.hasText(obj, "file")) {
            obj = bindLive(obj, true);
        }
        return command("resolve", obj);
    }

    public JsonNode info(String path) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        if (path != null) body.put("path", path);
        return command("info", body);
    }

    public JsonNode capture(Object body) {
        return command("capture", body);
    }

    public JsonNode recommend(Object body) {
        return command("recommend", body);
    }

    public JsonNode validate(Object body) {
        return command("validate", body);
    }

    public JsonNode click(Object body) {
        return command("click", bindLive(body, true));
    }

    public JsonNode type(Object body) {
        return command("type", bindLive(body, true));
    }

    public JsonNode scroll(Object body) {
        return command("scroll", bindLive(body, true));
    }

    public JsonNode submit(Object body) {
        return command("submit", bindLive(body, true));
    }

    public JsonNode key(Object body) {
        return command("key", bindLive(body, true));
    }

    public JsonNode dblclick(Object body) {
        return command("dblclick", bindLive(body, true));
    }

    public JsonNode contextclick(Object body) {
        return command("contextclick", bindLive(body, true));
    }

    public JsonNode drag(Object body) {
        return command("drag", bindLive(body, true));
    }

    public JsonNode eval(Object body) {
        return command("eval", bindLive(body, true));
    }

    public JsonNode screenshot(Object body) {
        return command("screenshot", bindLive(body, false));
    }

    public JsonNode snapshot(Object body) {
        return command("snapshot", bindLive(body, false));
    }

    /**
     * Closes every session this client opened, then stops the sidecar.
     * Idempotent. Does not throw if a session is already gone.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            for (Session session : owned.values()) session.markClosed();
            for (String id : List.copyOf(owned.keySet())) {
                try {
                    ObjectNode body = Json.MAPPER.createObjectNode();
                    body.put("id", id);
                    exchangeLocked(Argv.from("session/close", body));
                } catch (RuntimeException ignored) {
                    // The browser may already be gone. Still drop the sidecar.
                }
            }
            owned.clear();
            threadSession.remove();
            destroyProcessLocked();
            deleteTree(sessionDir);
            closed = true;
        }
    }

    void closeSession(String id) {
        synchronized (lock) {
            Session session = owned.remove(id);
            if (session != null) session.markClosed();
            Session current = threadSession.get();
            if (current != null && id.equals(current.id())) threadSession.remove();
            if (closed || session == null) return;
            try {
                ObjectNode body = Json.MAPPER.createObjectNode();
                body.put("id", id);
                exchangeLocked(Argv.from("session/close", body));
            } catch (RuntimeException ignored) {
                // Close is cleanup. A second close is not an error.
            }
        }
    }

    ObjectNode forceSession(Object body, String id) {
        ObjectNode obj = asObject(body);
        obj.put("session", id);
        return obj;
    }

    private ObjectNode bindLive(Object body, boolean required) {
        ObjectNode obj = asObject(body);
        if (Argv.hasText(obj, "session") || Argv.hasText(obj, "cdp")) return obj;
        Session session = threadSession.get();
        if (session != null && !session.isClosed()) {
            obj.put("session", session.id());
            return obj;
        }
        if (required) throw InvalidException.local("no session on this thread");
        return obj;
    }

    static ObjectNode asObject(Object body) {
        if (body == null) return Json.MAPPER.createObjectNode();
        if (body instanceof ObjectNode node) return node.deepCopy();
        if (body instanceof JsonNode node) {
            if (node.isNull()) return Json.MAPPER.createObjectNode();
            if (!node.isObject()) throw InvalidException.local("launch config must be a JSON object");
            return ((ObjectNode) node).deepCopy();
        }
        if (body instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return Json.MAPPER.createObjectNode();
            JsonNode node;
            try {
                node = Json.MAPPER.readTree(trimmed);
            } catch (JsonProcessingException e) {
                throw InvalidException.local("launch config is not JSON: " + e.getOriginalMessage());
            }
            if (!node.isObject()) throw InvalidException.local("launch config must be a JSON object");
            return (ObjectNode) node;
        }
        if (body instanceof Map<?, ?>) {
            JsonNode node = Json.MAPPER.valueToTree(body);
            if (!node.isObject()) throw InvalidException.local("launch config must be a JSON object");
            return (ObjectNode) node;
        }
        throw InvalidException.local("launch config must be a Map or a JSON string");
    }

    private static String freshId() {
        long tid = Thread.currentThread().getId();
        long n = IDS.incrementAndGet();
        return "j" + Long.toUnsignedString(tid, 36) + "n" + Long.toUnsignedString(n, 36);
    }

    private JsonNode exchangeLocked(List<String> argv) {
        try {
            ensureLocked();
        } catch (IOException e) {
            throw FailedException.worker("stdio worker: " + e.getMessage());
        }
        String id = "j" + nextId.incrementAndGet();
        ObjectNode req = Json.MAPPER.createObjectNode();
        req.put("id", id);
        ArrayNode args = req.putArray("argv");
        for (String arg : argv) args.add(arg);
        try {
            stdin.write(Json.MAPPER.writeValueAsString(req));
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            restartLocked();
            throw FailedException.worker("stdio worker exited; the in-flight request was not retried");
        }
        String line;
        try {
            line = stdout.readLine();
        } catch (IOException e) {
            restartLocked();
            throw FailedException.worker("stdio worker exited; the in-flight request was not retried");
        }
        if (line == null || line.isBlank()) {
            restartLocked();
            throw FailedException.worker("stdio worker exited; the in-flight request was not retried");
        }
        JsonNode res;
        try {
            res = Json.MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            restartLocked();
            throw FailedException.worker("stdio worker returned a non-JSON line; the in-flight request was not retried");
        }
        int exit = res.path("exit").asInt(1);
        String out = res.path("stdout").asText("");
        String err = res.path("stderr").asText("");
        if (exit == 0) return parseSuccess(out);
        throw CompositeException.from(exit, out, err);
    }

    private JsonNode parseSuccess(String stdout) {
        if (stdout == null || stdout.isBlank()) return Json.MAPPER.createObjectNode();
        String trimmed = stdout.trim();
        try {
            return Json.MAPPER.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(stdout);
        }
    }

    private void ensureLocked() throws IOException {
        if (process != null && process.isAlive()) return;
        spawnLocked();
    }

    private void restartLocked() {
        destroyProcessLocked();
        try {
            spawnLocked();
        } catch (IOException ignored) {
            // The next call reports the spawn failure.
        }
    }

    private void spawnLocked() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(bin, "serve", "--stdio");
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = builder.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private void destroyProcessLocked() {
        Process proc = process;
        process = null;
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
            stdin = null;
        }
        if (stdout != null) {
            try {
                stdout.close();
            } catch (IOException ignored) {
            }
            stdout = null;
        }
        if (proc == null) return;
        proc.destroy();
        try {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
}
