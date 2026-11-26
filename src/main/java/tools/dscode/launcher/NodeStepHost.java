package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.Values;
import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.ForeignStepHost;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreEntities;
import tools.dscode.store.StoreHashes;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.StoreSecrets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript and TypeScript step definitions on the Node binary inside the Playwright driver-bundle.
 * The engine package does not name this class. One process per host, stopped when the run closes it.
 */
public final class NodeStepHost implements ForeignStepHost, AutoCloseable {
    static final String BARE_IMPORT = "error: bare import is a rebuild\nfix: add the package to workbench-ui and rebuild";
    static final String ERASABLE = "fix: keep the TypeScript erasable";
    private static final String SCRIPT = "META-INF/pickleball/node/step-host.mjs";
    private static final String API = "META-INF/pickleball/node/pkb-api.mjs";
    private static final String API_IMPORT =
            "import { defineStep, Given, When, Then, And, But, resolveText, saveValue, getValue, softFail, secret, log, entityGet, call } from \"./pkb-api.mjs\";\n";
    private static final Set<String> FNS = Set.of(
            "resolveText", "saveValue", "getValue", "softFail", "secret", "log", "entityGet");
    private static final Pattern SPECIFIER = Pattern.compile(
            "(?s)(?:\\bfrom\\s*|\\bimport\\s*|\\bimport\\s*\\(\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern PARAMETER_PROPERTY = Pattern.compile(
            "constructor\\s*\\(\\s*(?:public|private|protected|readonly)\\b");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object PROBE_LOCK = new Object();
    private static volatile Strip strip = Strip.UNKNOWN;
    private static volatile String stripError = "";
    private static volatile List<String> lastSpawn = List.of();

    private final Object writeLock = new Object();
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<JsonNode> inboundCalls = new LinkedBlockingQueue<>();
    private final Map<String, String> loadedHash = new LinkedHashMap<>();
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final StringBuilder stderr = new StringBuilder();
    private Process process;
    private Path scriptPath;

    public NodeStepHost() {
    }

    /** Argv of the step-host process. The probe is not recorded. Empty until the first spawn. */
    public static List<String> lastSpawn() {
        return lastSpawn;
    }

    /** Function names Java answered for this host. Return values are not stored. */
    public List<String> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    @Override
    public String language() {
        return "js";
    }

    @Override
    public boolean supports(String language) {
        return "js".equals(language) || "ts".equals(language);
    }

    @Override
    public Object invoke(String definitionId, String stepText, List<String> argsJson) {
        Path project = ForeignStepHosts.project();
        if (project == null) {
            throw new IllegalStateException("error: no project for the Node step host"
                    + "\nfix: run the step from PickleballEngine with a project");
        }
        Row row = lookup(project, definitionId);
        byte[] original = readSource(project, row.sourcePath());
        String source = new String(original, StandardCharsets.UTF_8);
        if ("ts".equals(row.language()) || row.sourcePath().endsWith(".ts")) {
            String blocked = nonErasable(source);
            if (blocked != null) {
                throw new IllegalStateException("error: " + blocked + " is not erasable TypeScript\n" + ERASABLE);
            }
        }
        try {
            Prepared prepared = prepare(project, row, source);
            ensureStarted(project);
            requireTsRuntime(row);
            if (!row.contentHash().equals(loadedHash.get(definitionId))) {
                EngineLog.log(project, "host", "load", "load " + definitionId);
                JsonNode loaded = roundTrip(loadMessage(definitionId, row, source, prepared), row.sourcePath());
                requireOk(loaded, row.sourcePath());
                loadedHash.put(definitionId, row.contentHash());
            }
            EngineLog.log(project, "host", "invoke", "invoke " + definitionId);
            JsonNode invoked = roundTrip(invokeMessage(definitionId, stepText, argsJson), row.sourcePath());
            requireOk(invoked, row.sourcePath());
            return valueOf(invoked.get("value"));
        } catch (IOException failure) {
            throw new IllegalStateException("error: the Node step host could not write " + row.sourcePath()
                    + "\nfix: store hydrate", failure);
        }
    }

    @Override
    public void close() {
        Process current = process;
        process = null;
        scriptPath = null;
        loadedHash.clear();
        if (current == null) return;
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private void ensureStarted(Path project) throws IOException {
        if (process != null && !process.isAlive()) {
            process = null;
            scriptPath = null;
            loadedHash.clear();
        }
        if (process != null) return;
        OptionalNode node = nodeBinary();
        if (node.path() == null) {
            throw new IllegalStateException("error: bundled Node is not available"
                    + "\nfix: the playwright driver-bundle dependency must be on the classpath");
        }
        probe(node.path());
        Path home = StorePaths.sandboxHome(project, StoreScope.label(StoreScope.current())).resolve("js");
        Files.createDirectories(home);
        scriptPath = home.resolve("step-host.mjs");
        copyResource(SCRIPT, scriptPath);
        copyResource(API, home.resolve("pkb-api.mjs"));
        List<String> command = new ArrayList<>();
        command.add(node.path().toString());
        if (strip == Strip.FLAG) command.add("--experimental-strip-types");
        command.add(scriptPath.toString());
        lastSpawn = List.copyOf(command);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        builder.environment().putAll(PlaywrightConnection.DRIVER_ENV);
        builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Process started = builder.start();
        process = started;
        Thread reader = new Thread(() -> readLoop(started), "pkb-step-host");
        reader.setDaemon(true);
        reader.start();
        Thread errors = new Thread(() -> drain(started.getErrorStream()), "pkb-step-host-err");
        errors.setDaemon(true);
        errors.start();
        EngineLog.log(project, "host", "spawn", "spawn " + node.path().getFileName());
    }

    private JsonNode roundTrip(ObjectNode message, String sourcePath) {
        long id = message.get("id").asLong();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            send(message);
            while (true) {
                if (future.isDone()) return future.get();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    pending.remove(id);
                    throw new TimeoutException("step host");
                }
                long waitMs = Math.min(50L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                JsonNode call = inboundCalls.poll(waitMs, TimeUnit.MILLISECONDS);
                if (call != null) reply(call);
            }
        } catch (TimeoutException failure) {
            pending.remove(id);
            throw new IllegalStateException("error: step host timed out\nfix: store glue show " + sourcePath
                    + tail(), failure);
        } catch (InterruptedException failure) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("error: step host interrupted\nfix: store glue show " + sourcePath, failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("error: step host stopped\nfix: store glue show " + sourcePath + tail(), cause);
        } catch (IOException failure) {
            pending.remove(id);
            throw new IllegalStateException("error: step host stopped\nfix: store glue show " + sourcePath + tail(), failure);
        }
    }

    private void send(JsonNode message) throws IOException {
        byte[] bytes = (JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            process.getOutputStream().write(bytes);
            process.getOutputStream().flush();
        }
    }

    private void readLoop(Process started) {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try (InputStream input = started.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                for (int index = 0; index < count; index++) {
                    byte unit = buffer[index];
                    if (unit == '\n') {
                        dispatch(line.toString(StandardCharsets.UTF_8));
                        line.reset();
                    } else if (unit != '\r') {
                        line.write(unit);
                    }
                }
            }
        } catch (IOException failure) {
            failPending(failure);
            return;
        }
        failPending(new IOException("step host closed"));
    }

    private void dispatch(String text) {
        if (text.isBlank()) return;
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (IOException failure) {
            return;
        }
        if ("call".equals(message.path("op").asText(""))) {
            inboundCalls.add(message);
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(message.path("id").asLong());
        if (future != null) future.complete(message);
    }

    private void reply(JsonNode message) {
        long id = message.path("id").asLong();
        String fn = message.path("fn").asText("");
        calls.add(fn);
        EngineLog.log(ForeignStepHosts.project(), "host", "call", "call " + fn);
        ObjectNode response = JSON.createObjectNode();
        response.put("id", id);
        try {
            if (!FNS.contains(fn)) {
                response.put("ok", false);
                response.put("error", "unknown fn");
            } else {
                Object value = dispatchFn(fn, message.get("args"));
                response.put("ok", true);
                response.set("value", JSON.valueToTree(value));
            }
        } catch (RuntimeException failure) {
            response.put("ok", false);
            response.put("error", failure.getMessage() == null ? "call failed" : failure.getMessage());
        }
        try {
            send(response);
        } catch (IOException failure) {
            failPending(failure);
        }
    }

    private Object dispatchFn(String fn, JsonNode args) {
        String first = arg(args, 0);
        return switch (fn) {
            case "resolveText" -> resolveText(first);
            case "saveValue" -> {
                Values.saveValue(first, jsonValue(args, 1));
                yield null;
            }
            case "getValue" -> Values.getValue(first == null ? "" : first);
            case "softFail" -> {
                Values.softFail(first);
                yield null;
            }
            case "secret" -> secretValue(first);
            case "log" -> {
                EngineLog.log(ForeignStepHosts.project(), "host", "log", first == null ? "" : first);
                yield null;
            }
            case "entityGet" -> entityValue(first);
            default -> throw new IllegalStateException("unknown fn");
        };
    }

    private static String resolveText(String input) {
        if (input == null) return null;
        try {
            ExecutionContext context = ExecutionContext.current();
            if (context != null && context.parsingMap() != null) {
                return context.parsingMap().resolveWholeText(input);
            }
        } catch (RuntimeException ignored) {
            // Fall through to the running map, then the original text.
        }
        try {
            ParsingMap map = ParsingMap.getRunningParsingMap();
            if (map != null) return map.resolveWholeText(input);
        } catch (RuntimeException ignored) {
            // No scenario map. The text is already resolved.
        }
        return input;
    }

    /** A reference is returned as stored. Every other name is {@code ~MASK;}. Never cleartext. */
    private static String secretValue(String name) {
        if (name != null && StoreSecrets.reference(name)) return name;
        return "~MASK;";
    }

    private static Object entityValue(String source) {
        Object value = StoreEntities.lookup(source);
        if (value == null) return null;
        String text = value instanceof String string ? string : String.valueOf(value);
        if (StoreSecrets.reference(text)) return text;
        String field = entityField(source);
        if (field != null && StoreSecrets.secretName(ForeignStepHosts.project(), field)) return "~MASK;";
        return text;
    }

    private static String entityField(String source) {
        if (source == null) return null;
        int hash = source.indexOf('#');
        if (hash < 0 || hash == source.length() - 1) return null;
        String rest = source.substring(hash + 1);
        int dot = rest.lastIndexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) return null;
        return rest.substring(dot + 1);
    }

    private ObjectNode loadMessage(String definitionId, Row row, String source, Prepared prepared) {
        ObjectNode message = JSON.createObjectNode();
        message.put("id", ids.incrementAndGet());
        message.put("op", "load");
        message.put("definitionId", definitionId);
        message.put("language", row.language());
        message.put("source", source);
        message.put("pattern", row.pattern());
        message.put("file", prepared.file().toString());
        message.put("hash", row.contentHash());
        return message;
    }

    private ObjectNode invokeMessage(String definitionId, String stepText, List<String> args) {
        ObjectNode message = JSON.createObjectNode();
        message.put("id", ids.incrementAndGet());
        message.put("op", "invoke");
        message.put("definitionId", definitionId);
        message.put("text", stepText == null ? "" : stepText);
        message.putArray("args");
        if (args != null) {
            for (String arg : args) message.withArray("args").add(arg);
        }
        return message;
    }

    private Prepared prepare(Path project, Row row, String source) throws IOException {
        Path home = StorePaths.sandboxHome(project, StoreScope.label(StoreScope.current())).resolve("js");
        Files.createDirectories(home);
        Map<String, String> flat = new LinkedHashMap<>();
        String rewritten = rewrite(project, row.sourcePath(), source, home, flat);
        String extension = "ts".equals(row.language()) || row.sourcePath().endsWith(".ts") ? ".ts" : ".mjs";
        Path file = home.resolve(safe(row.id()) + extension);
        Files.writeString(file, API_IMPORT + rewritten, StandardCharsets.UTF_8);
        return new Prepared(file);
    }

    private String rewrite(Path project, String sourcePath, String source, Path home, Map<String, String> flat)
            throws IOException {
        Matcher matcher = SPECIFIER.matcher(source);
        StringBuilder rewritten = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            String spec = matcher.group(1);
            String kind = kind(spec);
            if ("bare".equals(kind)) throw new IllegalStateException(BARE_IMPORT);
            rewritten.append(source, cursor, matcher.start(1));
            if ("relative".equals(kind)) {
                String name = materialize(project, sourcePath, spec, home, flat);
                rewritten.append("./").append(name);
            } else {
                rewritten.append(spec);
            }
            cursor = matcher.end(1);
        }
        rewritten.append(source.substring(cursor));
        return rewritten.toString();
    }

    private String materialize(Path project, String importer, String spec, Path home, Map<String, String> flat)
            throws IOException {
        String key = resolveSpec(importer, spec);
        String existing = flat.get(key);
        if (existing != null) return existing;
        byte[] bytes = readOptional(project, key);
        if (bytes == null) {
            throw new IllegalStateException("error: relative import " + spec + " was not hydrated\nfix: store hydrate");
        }
        String extension = key.endsWith(".ts") ? ".ts" : ".mjs";
        String name = "imp-" + StoreHashes.sha256(key.getBytes(StandardCharsets.UTF_8)).substring(0, 16) + extension;
        flat.put(key, name);
        String body = rewrite(project, key, new String(bytes, StandardCharsets.UTF_8), home, flat);
        Files.writeString(home.resolve(name), API_IMPORT + body, StandardCharsets.UTF_8);
        return name;
    }

    private static String resolveSpec(String importer, String spec) {
        String clean = spec;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        Path parent = Path.of(importer.replace('\\', '/')).getParent();
        Path resolved = (parent == null ? Path.of(clean) : parent.resolve(clean)).normalize();
        return resolved.toString().replace('\\', '/');
    }

    private static String kind(String spec) {
        if (spec.startsWith("./") || spec.startsWith("../")) return "relative";
        if (spec.startsWith("node:") || spec.startsWith("file:") || spec.startsWith("/")) return "allowed";
        if (spec.length() >= 3 && Character.isLetter(spec.charAt(0)) && spec.charAt(1) == ':') return "allowed";
        return "bare";
    }

    static String nonErasable(String source) {
        if (source == null) return null;
        if (source.contains("enum ")) return "enum";
        if (source.contains("namespace ")) return "namespace";
        if (PARAMETER_PROPERTY.matcher(source).find()) return "parameter property";
        return null;
    }

    private static void probe(Path node) {
        if (strip != Strip.UNKNOWN) return;
        synchronized (PROBE_LOCK) {
            if (strip != Strip.UNKNOWN) return;
            Path dir = null;
            try {
                dir = Files.createTempDirectory("pkb-ts-probe");
                Path file = dir.resolve("probe.ts");
                Files.writeString(file, "export const n: number = 1;\n", StandardCharsets.UTF_8);
                int version = run(node, List.of("--version"), dir);
                if (version != 0) {
                    strip = Strip.OFF;
                    stripError = "node --version failed";
                    return;
                }
                int plain = run(node, List.of(file.toString()), dir);
                if (plain == 0) {
                    strip = Strip.ON;
                    return;
                }
                int flagged = run(node, List.of("--experimental-strip-types", file.toString()), dir);
                if (flagged == 0) {
                    strip = Strip.FLAG;
                    return;
                }
                strip = Strip.OFF;
                stripError = "node rejected erasable TypeScript";
            } catch (IOException failure) {
                strip = Strip.OFF;
                stripError = failure.getMessage() == null ? "probe failed" : failure.getMessage();
            } finally {
                if (dir != null) deleteProbe(dir);
            }
        }
    }

    private static int run(Path node, List<String> args, Path directory) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(node.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                return -1;
            }
            return child.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            return -1;
        }
    }

    private static void deleteProbe(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover probe file is outside the project.
                }
            });
        } catch (IOException ignored) {
            // The probe directory is a temp directory.
        }
    }

    private void requireTsRuntime(Row row) {
        if (!"ts".equals(row.language()) && !row.sourcePath().endsWith(".ts")) return;
        if (strip != Strip.OFF) return;
        String detail = stripError == null || stripError.isBlank() ? "this Node cannot run erasable TypeScript" : stripError;
        throw new IllegalStateException("error: " + detail + "\n" + ERASABLE);
    }

    private OptionalNode nodeBinary() {
        var located = PlaywrightNode.locate();
        if (located.isEmpty()) return new OptionalNode(null);
        List<String> command = PlaywrightNode.command(located.get(), List.of());
        if (command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) return new OptionalNode(null);
        return new OptionalNode(Path.of(command.get(0)));
    }

    private Row lookup(Path project, String definitionId) {
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT language, pattern, source_path, content_hash
                     FROM step_definitions
                     WHERE definition_id = ? AND removed = 0
                     """)) {
            statement.setString(1, definitionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("error: step definition " + definitionId + " was not found"
                            + "\nfix: store hydrate");
                }
                return new Row(definitionId, rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4) == null ? "" : rows.getString(4));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("error: step definition " + definitionId + " was not read"
                    + "\nfix: store hydrate", failure);
        }
    }

    private byte[] readSource(Path project, String sourcePath) {
        byte[] bytes = readOptional(project, sourcePath);
        if (bytes == null) {
            throw new IllegalStateException("error: step definition source " + sourcePath + " was not found"
                    + "\nfix: store hydrate");
        }
        return bytes;
    }

    private byte[] readOptional(Path project, String sourcePath) {
        String key = sourcePath.replace('\\', '/');
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT raw_content FROM files
                     WHERE replace(path, char(92), '/') = ?
                        OR replace(norm_path, char(92), '/') = ?
                     """)) {
            statement.setString(1, key);
            statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    byte[] stored = rows.getBytes(1);
                    if (stored != null && stored.length > 0) return stored;
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("error: step definition source " + sourcePath + " was not read"
                    + "\nfix: store hydrate", failure);
        }
        Path disk = project.resolve(key);
        if (Files.isRegularFile(disk)) {
            try {
                return Files.readAllBytes(disk);
            } catch (IOException failure) {
                throw new IllegalStateException("error: step definition source " + sourcePath + " was not read"
                        + "\nfix: store hydrate", failure);
            }
        }
        return null;
    }

    private static void requireOk(JsonNode message, String sourcePath) {
        if (message != null && message.path("ok").asBoolean(false)) return;
        String error = message == null ? "step host stopped" : message.path("error").asText("step host stopped");
        throw new IllegalStateException("error: " + error + "\nfix: store glue show " + sourcePath);
    }

    private static Object valueOf(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.booleanValue();
        if (value.isIntegralNumber()) return value.longValue();
        if (value.isNumber()) return value.decimalValue();
        return value.toString();
    }

    private static String arg(JsonNode args, int index) {
        if (args == null || !args.isArray() || args.size() <= index || args.get(index).isNull()) return null;
        JsonNode node = args.get(index);
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static Object jsonValue(JsonNode args, int index) {
        if (args == null || !args.isArray() || args.size() <= index || args.get(index).isNull()) return null;
        JsonNode node = args.get(index);
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isNumber()) return node.decimalValue();
        return node.toString();
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream input = NodeStepHost.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("error: " + resource + " is not on the classpath"
                        + "\nfix: rebuild pickleball");
            }
            Files.write(target, input.readAllBytes());
        }
    }

    private void drain(InputStream input) {
        byte[] buffer = new byte[1024];
        try (input) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                synchronized (stderr) {
                    if (stderr.length() < 4000) stderr.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // The process is closing.
        }
    }

    private void failPending(Exception failure) {
        IllegalStateException error = new IllegalStateException(
                "error: step host stopped\nfix: the playwright driver-bundle dependency must be on the classpath" + tail(),
                failure);
        for (CompletableFuture<JsonNode> future : pending.values()) future.completeExceptionally(error);
        pending.clear();
    }

    private String tail() {
        synchronized (stderr) {
            String text = stderr.toString().trim();
            if (text.isBlank()) return "";
            if (text.length() > 500) text = text.substring(text.length() - 500);
            return "\n" + text;
        }
    }

    private static String safe(String definitionId) {
        String cleaned = definitionId == null ? "" : definitionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "step" : cleaned;
    }

    private record Row(String id, String language, String pattern, String sourcePath, String contentHash) {
    }

    private record Prepared(Path file) {
    }

    private record OptionalNode(Path path) {
    }

    private enum Strip {
        UNKNOWN, ON, FLAG, OFF
    }
}
