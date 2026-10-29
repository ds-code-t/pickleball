package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Optional dom-composite launch for one live Workbench session.
 *
 * <p>The only reader of {@code pkb_direct_control_provider} is
 * {@link #configured}. A blank value, a non-live run, or a missing CDP
 * address starts nothing. Detach stops only the provider process.</p>
 */
public final class DirectControlProvider {
    public static final String KEY = "pkb_direct_control_provider";
    public static final String RUNVARS_PROPERTY = "pkb_runvars." + KEY;
    public static final String BIN_ENV = "COMPOSITE_BIN";
    public static final String HOME_ENV = "COMPOSITE_HOME";
    public static final String DESCRIPTOR_ENV = "PICKLEBALL_SESSION_DESCRIPTOR";
    public static final String TOKEN = "composite";

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
        List<String> command = List.of(
                binary.get().toAbsolutePath().normalize().toString(),
                "session", "attach",
                "--cdp", cdp,
                "--id", request.sessionId(),
                "--i-accept-local-attach"
        );
        Path logFile = request.logFile();
        try {
            if (logFile != null) Files.createDirectories(logFile.getParent());
            ProcessBuilder builder = new ProcessBuilder(command);
            if (request.project() != null) builder.directory(request.project().toFile());
            if (request.environment() != null) {
                builder.environment().clear();
                builder.environment().putAll(request.environment());
            }
            if (request.descriptorFile() != null) {
                builder.environment().put(
                        DESCRIPTOR_ENV,
                        request.descriptorFile().toAbsolutePath().normalize().toString()
                );
            }
            builder.redirectErrorStream(true);
            if (logFile != null) {
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            } else {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }
            Process process = builder.start();
            note(request.project(), request.runId(), "start", command.get(0) + " " + request.sessionId());
            return new Outcome(Kind.STARTED, new Handle(process, command, request.project(), request.runId()), null);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not start direct-control provider: " + failure.getMessage(),
                    failure
            );
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
        String line = String.join("\t",
                Instant.now().toString(),
                "-",
                runId == null || runId.isBlank() ? "-" : one(runId),
                one(event),
                one(purpose),
                "-"
        );
        Path file = PickleballLocalLayout.root(project).resolve("agent-log");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException failure) {
            throw new IllegalStateException("Could not append the short log: " + failure.getMessage(), failure);
        }
    }

    private static String one(String value) {
        String cleaned = value == null ? "-" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.isEmpty() ? "-" : cleaned;
    }

    private static String blank(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    /** The provider process only. {@link #detach()} does not signal any other process. */
    public static final class Handle implements AutoCloseable {
        private final Process process;
        private final List<String> command;
        private final Path project;
        private final String runId;
        private boolean detached;

        private Handle(Process process, List<String> command, Path project, String runId) {
            this.process = process;
            this.command = List.copyOf(command);
            this.project = project;
            this.runId = runId;
        }

        public List<String> command() {
            return command;
        }

        public boolean alive() {
            return process.isAlive();
        }

        public long pid() {
            return process.pid();
        }

        public void detach() {
            if (detached) return;
            detached = true;
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            note(project, runId, "stop", "provider detach");
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
