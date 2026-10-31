package tools.dscode.control.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Optional dom-composite attach for one live Workbench session.
 *
 * <p>The only reader of {@code pkb_direct_control_provider} is
 * {@link #configured}. A blank value, a non-live run, or a missing CDP
 * address starts nothing. {@code session attach} is a short command: it
 * records the session and exits. {@link Handle#detach()} runs
 * {@code session close} for that id and session directory. It does not
 * signal the browser.</p>
 */
public final class DirectControlProvider {
    public static final String KEY = "pkb_direct_control_provider";
    public static final String RUNVARS_PROPERTY = "pkb_runvars." + KEY;
    public static final String BIN_ENV = "COMPOSITE_BIN";
    public static final String HOME_ENV = "COMPOSITE_HOME";
    public static final String TOKEN = "composite";
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private DirectControlProvider() {
    }

    public enum Kind {
        STARTED,
        NOT_LIVE,
        NOT_CONFIGURED,
        NO_CDP,
        BINARY_MISSING
    }

    /**
     * System property {@code pkb_direct_control_provider}, then
     * {@code pkb_runvars.pkb_direct_control_provider}, then that run's
     * workspace {@code runvars.properties}. Blank is absent.
     */
    public static Optional<String> configured(Path project, String runId) {
        String property = blank(System.getProperty(KEY));
        if (property == null) property = blank(System.getProperty(RUNVARS_PROPERTY));
        if (property != null) return Optional.of(property);
        if (project == null || runId == null || runId.isBlank()) return Optional.empty();
        Path workspace = Workspace.directory(project, runId);
        if (!Workspace.exists(workspace)) return Optional.empty();
        return Optional.ofNullable(blank(Workspace.readRunVars(workspace).get(KEY)));
    }

    /**
     * {@code composite} uses {@code COMPOSITE_BIN}, then {@code COMPOSITE_HOME}
     * or a sibling {@code dom-composite} checkout
     * ({@code target/debug/composite}, then {@code target/release/composite}).
     * Any other value is a path to the binary.
     */
    public static Optional<Path> resolveBinary(String configured, Path project, Map<String, String> env) {
        String value = blank(configured);
        if (value == null) return Optional.empty();
        Map<String, String> environment = env == null ? System.getenv() : env;
        if (TOKEN.equals(value)) {
            Path named = executable(environment.get(BIN_ENV));
            if (named != null) return Optional.of(named);
            for (Path home : homes(project, environment)) {
                Path debug = executable(home.resolve("target").resolve("debug").resolve(TOKEN).toString());
                if (debug != null) return Optional.of(debug);
                Path release = executable(home.resolve("target").resolve("release").resolve(TOKEN).toString());
                if (release != null) return Optional.of(release);
            }
            return Optional.empty();
        }
        Path given = Path.of(value);
        if (!given.isAbsolute() && project != null) given = project.resolve(given);
        Path file = executable(given.toString());
        return file == null ? Optional.empty() : Optional.of(file);
    }

    /**
     * Per-run composite registry: {@code .pickleball/runs/<runId>/session/composite}.
     */
    public static Path sessionDirectory(Path project, String runId) {
        if (project == null) throw new IllegalArgumentException("project is required.");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is required.");
        return SessionDescriptorWriter.file(project, runId)
                .getParent()
                .resolve("composite")
                .toAbsolutePath()
                .normalize();
    }

    public static Outcome open(Request request) {
        if (request == null) throw new IllegalArgumentException("request is required.");
        if (!request.live()) return Outcome.idle(Kind.NOT_LIVE, "not a live session");
        String configured = blank(request.configured());
        if (configured == null) return Outcome.idle(Kind.NOT_CONFIGURED, "provider is not configured");
        String cdp = withScheme(request.cdpAddress());
        if (cdp == null) return Outcome.idle(Kind.NO_CDP, "no CDP address");
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required.");
        }
        Optional<Path> binary = request.binary() != null
                ? Optional.of(request.binary())
                : resolveBinary(configured, request.project(), request.environment());
        if (binary.isEmpty()) {
            return Outcome.idle(
                    Kind.BINARY_MISSING,
                    "Direct-control provider skipped: composite binary not found."
            );
        }
        Path sessionDir = sessionDirectory(request.project(), request.runId());
        List<String> command = List.of(
                binary.get().toAbsolutePath().normalize().toString(),
                "session", "attach",
                "--cdp", cdp,
                "--id", request.sessionId(),
                "--i-accept-local-attach",
                "--session-dir", sessionDir.toString()
        );
        Path logFile = request.logFile();
        try {
            Files.createDirectories(sessionDir);
            if (logFile != null && logFile.getParent() != null) Files.createDirectories(logFile.getParent());
            Completed completed = run(command, request.project(), request.environment(), logFile);
            if (completed.exitCode() != 0) {
                throw new IllegalStateException(
                        "composite session attach exited " + completed.exitCode() + ": " + oneLine(completed.detail())
                );
            }
            Map<String, Object> json = object(completed.stdout(), "composite session attach");
            String compositeId = json.get("id") instanceof String id && !id.isBlank() ? id.trim() : null;
            if (compositeId == null) {
                throw new IllegalStateException(
                        "composite session attach returned no session id: " + oneLine(completed.stdout())
                );
            }
            if (!Boolean.FALSE.equals(json.get("launched"))) {
                throw new IllegalStateException(
                        "composite session attach must record launched false so close cannot kill the browser: "
                                + oneLine(completed.stdout())
                );
            }
            publishComposite(request, sessionDir, compositeId);
            note(request.project(), request.runId(), "start", command.get(0) + " " + compositeId);
            return new Outcome(
                    Kind.STARTED,
                    new Handle(
                            completed.process(),
                            command,
                            request.project(),
                            request.runId(),
                            binary.get().toAbsolutePath().normalize(),
                            sessionDir,
                            compositeId,
                            completed.stdout(),
                            request.environment(),
                            logFile
                    ),
                    null
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not start direct-control provider: " + failure.getMessage(),
                    failure
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while attaching the composite session.");
        }
    }

    public static String withScheme(String address) {
        String value = blank(address);
        if (value == null) return null;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ws://")
                || lower.startsWith("wss://")) {
            return value;
        }
        return "http://" + value;
    }

    private static List<Path> homes(Path project, Map<String, String> env) {
        List<Path> homes = new ArrayList<>();
        String home = blank(env.get(HOME_ENV));
        if (home != null) homes.add(Path.of(home));
        if (project != null) {
            Path parent = project.toAbsolutePath().normalize().getParent();
            if (parent != null) homes.add(parent.resolve("dom-composite"));
        }
        return homes;
    }

    private static Path executable(String value) {
        String text = blank(value);
        if (text == null) return null;
        Path path = Path.of(text).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return null;
        return path;
    }

    private static void note(Path project, String runId, String event, String purpose) {
        if (project == null) return;
        try {
            AgentLog.append(project, Instant.now(), "-", runId, event, purpose, null);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not append the short log: " + failure.getMessage(), failure);
        }
    }

    private static String blank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void publishComposite(Request request, Path sessionDir, String compositeId) {
        if (request.project() == null || request.runId() == null || request.runId().isBlank()) return;
        Optional<SessionDescriptor> current = SessionDescriptorWriter.read(request.project(), request.runId());
        if (current.isEmpty()) return;
        SessionDescriptor updated = current.get().withComposite(
                sessionDir.toAbsolutePath().normalize().toString(),
                compositeId,
                Instant.now()
        );
        SessionDescriptorWriter.publish(request.project(), updated, true);
    }

    private static Completed run(List<String> command, Path directory, Map<String, String> environment, Path logFile)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        builder.redirectErrorStream(false);
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = pump(process.getInputStream(), out);
        Thread stderr = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        stdout.join(5_000);
        stderr.join(5_000);
        String output = out.toString(StandardCharsets.UTF_8);
        String errors = err.toString(StandardCharsets.UTF_8);
        append(logFile, output);
        if (!errors.isBlank()) append(logFile, errors);
        if (!finished) {
            throw new IllegalStateException(
                    "composite command timed out after " + COMMAND_TIMEOUT.toSeconds() + "s: " + String.join(" ", command)
            );
        }
        return new Completed(process, process.exitValue(), output, errors);
    }

    private static Thread pump(InputStream stream, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            try {
                stream.transferTo(sink);
            } catch (IOException ignored) {
                // The process ended or was destroyed.
            }
        }, "composite-cli");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void append(Path logFile, String text) throws IOException {
        if (logFile == null || text == null || text.isEmpty()) return;
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        Files.writeString(
                logFile,
                text.endsWith("\n") ? text : text + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    private static Map<String, Object> object(String json, String what) {
        Object parsed;
        try {
            parsed = parseJson(json);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(what + " exited 0 but stdout was not JSON: " + oneLine(json), failure);
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalStateException(what + " exited 0 but stdout was not a JSON object: " + oneLine(json));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalStateException(what + " returned a non-string JSON key.");
            }
            map.put(name, value);
        });
        return map;
    }

    private static boolean registryHas(Path sessionDirectory, String id) {
        Path registry = sessionDirectory.resolve("registry.json");
        if (!Files.isRegularFile(registry)) return false;
        try {
            Object parsed = parseJson(Files.readString(registry));
            if (!(parsed instanceof Map<?, ?> root)) return false;
            Object sessions = root.get("sessions");
            if (!(sessions instanceof Map<?, ?> live)) return false;
            return live.containsKey(id);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + registry + ": " + failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "(no output)";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("composite returned empty output.");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skip();
        if (!parser.end()) throw new IllegalArgumentException("Trailing data in composite output.");
        return value;
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object parseValue() {
            skip();
            if (end()) throw new IllegalArgumentException("Unexpected end of composite output.");
            char ch = text.charAt(index);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseArray();
            if (ch == '"') return parseString();
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            if (startsWith("true")) {
                index += 4;
                return Boolean.TRUE;
            }
            if (startsWith("false")) {
                index += 5;
                return Boolean.FALSE;
            }
            if (ch == '-' || (ch >= '0' && ch <= '9')) return parseNumber();
            throw new IllegalArgumentException("Unexpected JSON at " + index + ".");
        }

        private Map<String, Object> parseObject() {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return map;
            }
            while (true) {
                skip();
                if (end() || text.charAt(index) != '"') {
                    throw new IllegalArgumentException("Expected a JSON string key.");
                }
                String key = parseString();
                skip();
                if (!peek(':')) throw new IllegalArgumentException("Expected ':' after " + key + ".");
                index++;
                map.put(key, parseValue());
                skip();
                if (peek('}')) {
                    index++;
                    return map;
                }
                if (!peek(',')) throw new IllegalArgumentException("Expected ',' or '}' in composite output.");
                index++;
            }
        }

        private List<Object> parseArray() {
            index++;
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skip();
                if (peek(']')) {
                    index++;
                    return list;
                }
                if (!peek(',')) throw new IllegalArgumentException("Expected ',' or ']' in composite output.");
                index++;
            }
        }

        private String parseString() {
            index++;
            StringBuilder value = new StringBuilder();
            while (!end()) {
                char ch = text.charAt(index++);
                if (ch == '"') return value.toString();
                if (ch != '\\') {
                    value.append(ch);
                    continue;
                }
                if (end()) break;
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(unicode());
                    default -> throw new IllegalArgumentException("Bad JSON escape.");
                }
            }
            throw new IllegalArgumentException("Unclosed JSON string.");
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) index++;
            while (!end() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            boolean fraction = false;
            if (peek('.')) {
                fraction = true;
                index++;
                while (!end() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            }
            if (!end() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                fraction = true;
                index++;
                if (!end() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                while (!end() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            }
            String token = text.substring(start, index);
            if (fraction) return Double.valueOf(token);
            try {
                return Long.valueOf(token);
            } catch (NumberFormatException failure) {
                return Double.valueOf(token);
            }
        }

        private char unicode() {
            if (index + 4 > text.length()) throw new IllegalArgumentException("Short JSON unicode escape.");
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw new IllegalArgumentException("Bad JSON unicode escape.");
                code = (code << 4) + digit;
            }
            return (char) code;
        }

        private void skip() {
            while (!end() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean peek(char expected) {
            return !end() && text.charAt(index) == expected;
        }

        private boolean startsWith(String token) {
            return text.startsWith(token, index);
        }

        private boolean end() {
            return index >= text.length();
        }
    }

    private record Completed(Process process, int exitCode, String stdout, String stderr) {
        private String detail() {
            if (stderr != null && !stderr.isBlank()) return stderr;
            return stdout == null ? "" : stdout;
        }
    }

    public record Request(
            boolean live,
            String configured,
            Path project,
            String runId,
            String sessionId,
            String cdpAddress,
            Path descriptorFile,
            Path logFile,
            Path binary,
            Map<String, String> environment
    ) {
        public Request {
            environment = environment == null ? null : Map.copyOf(environment);
        }
    }

    public record Outcome(Kind kind, Handle handle, String message) {
        private static Outcome idle(Kind kind, String message) {
            return new Outcome(kind, null, message);
        }
    }

    /** One attached composite session. {@link #detach()} closes that session and does not signal the browser. */
    public static final class Handle implements AutoCloseable {
        private final Process process;
        private final List<String> command;
        private final Path project;
        private final String runId;
        private final Path binary;
        private final Path sessionDirectory;
        private final String compositeSessionId;
        private final String attachJson;
        private final Map<String, String> environment;
        private final Path logFile;
        private boolean detached;

        private Handle(
                Process process,
                List<String> command,
                Path project,
                String runId,
                Path binary,
                Path sessionDirectory,
                String compositeSessionId,
                String attachJson,
                Map<String, String> environment,
                Path logFile
        ) {
            this.process = process;
            this.command = List.copyOf(command);
            this.project = project;
            this.runId = runId;
            this.binary = binary;
            this.sessionDirectory = sessionDirectory;
            this.compositeSessionId = compositeSessionId;
            this.attachJson = attachJson == null ? "" : attachJson;
            this.environment = environment;
            this.logFile = logFile;
        }

        public List<String> command() {
            return command;
        }

        public Path sessionDirectory() {
            return sessionDirectory;
        }

        public String compositeSessionId() {
            return compositeSessionId;
        }

        /** Stdout from {@code session attach}, kept after that process has exited. */
        public String attachJson() {
            return attachJson;
        }

        /** True while this id is still in the composite registry for the run's session directory. */
        public boolean alive() {
            return registryHas(sessionDirectory, compositeSessionId);
        }

        public long pid() {
            return process.pid();
        }

        public void detach() {
            if (detached) return;
            detached = true;
            List<String> close = List.of(
                    binary.toString(),
                    "session", "close",
                    "--id", compositeSessionId,
                    "--session-dir", sessionDirectory.toString()
            );
            try {
                Completed completed = run(close, project, environment, logFile);
                if (completed.exitCode() != 0) {
                    throw new IllegalStateException(
                            "composite session close exited " + completed.exitCode() + ": " + oneLine(completed.detail())
                    );
                }
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Could not close the composite session: " + failure.getMessage(),
                        failure
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing the composite session.");
            } finally {
                note(project, runId, "stop", "provider detach");
            }
        }

        public boolean waitFor(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            detach();
        }
    }
}
