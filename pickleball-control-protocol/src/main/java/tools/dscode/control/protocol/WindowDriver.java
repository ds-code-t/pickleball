package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which run the one Workbench window is showing, and which single agent may
 * drive that live session. The file sits beside other Workbench state, not
 * inside a run directory. Closing the window clears the driver and does not
 * delete run data or the short log.
 */
public final class WindowDriver {
    public static final String FILE = "window-driver.json";

    private static final Pattern OPEN = Pattern.compile("\"open\"\\s*:\\s*(true|false)");
    private static final Pattern LIVE = fieldPattern("liveRunId");
    private static final Pattern VIEWED = fieldPattern("viewedRunId");
    private static final Pattern DRIVER = fieldPattern("driverAgent");

    private WindowDriver() {
    }

    public record State(boolean open, String liveRunId, String viewedRunId, String driverAgent) {
        public static State closed() {
            return new State(false, null, null, null);
        }
    }

    public record Decision(RunView.Loaded loaded, State state, boolean driving, boolean readOnly) {
    }

    public static Path file(Path project) {
        return PickleballLocalLayout.workbenchStateRoot(project).resolve(FILE);
    }

    public static State read(Path project) {
        Path path = file(project);
        if (!Files.isRegularFile(path)) return State.closed();
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return State.closed();
        }
    }

    /**
     * Point the one window at {@code runId}. Reads that run's directory and
     * does not start a test. A second agent can view another run but does not
     * become a second driver of the live session.
     */
    public static Decision show(Path project, String runId, String agentId, boolean claim) {
        RunView.Loaded loaded = RunView.load(project, runId);
        State before = read(project);
        String live = before.liveRunId();
        String driver = before.driverAgent();
        String agent = blankToNull(agentId);
        boolean driving = false;
        boolean readOnly;
        if (!before.open()) {
            live = loaded.runId();
            if (claim && agent != null) {
                driver = agent;
                driving = true;
                readOnly = false;
            } else {
                driver = null;
                driving = false;
                readOnly = false;
            }
        } else if (claim && agent != null) {
            if (driver == null || driver.equals(agent)) {
                if (live == null || live.equals(loaded.runId())) {
                    if (live == null) live = loaded.runId();
                    driver = agent;
                    driving = true;
                    readOnly = false;
                } else {
                    driving = false;
                    readOnly = true;
                }
            } else {
                driving = false;
                readOnly = true;
            }
        } else {
            readOnly = live != null && !live.equals(loaded.runId());
        }
        State next = new State(true, live, loaded.runId(), driver);
        write(project, next);
        return new Decision(loaded, next, driving, readOnly);
    }

    public static State markOpen(Path project) {
        State before = read(project);
        State next = new State(true, before.liveRunId(), before.viewedRunId(), before.driverAgent());
        write(project, next);
        return next;
    }

    /**
     * The window is gone. The live run id is remembered only so a later open
     * can see it; the driver is cleared. Run directories and the short log
     * are not touched.
     */
    public static void closeWindow(Path project) {
        State before = read(project);
        write(project, new State(false, before.liveRunId(), before.viewedRunId(), null));
    }

    public static Optional<String> refuseSecondDriver(Path project, String agentId) {
        String agent = blankToNull(agentId);
        if (agent == null) return Optional.empty();
        State state = read(project);
        if (!state.open() || state.driverAgent() == null || state.driverAgent().equals(agent)) {
            return Optional.empty();
        }
        String live = state.liveRunId() == null ? "the open window" : state.liveRunId();
        return Optional.of(
                "A second agent does not become a second driver of the live session " + live + "."
        );
    }

    public static void claimVacant(Path project, String agentId) {
        String agent = blankToNull(agentId);
        if (agent == null) return;
        State state = read(project);
        if (!state.open() || state.driverAgent() != null) return;
        write(project, new State(true, state.liveRunId(), state.viewedRunId(), agent));
    }

    static State parse(String json) {
        if (json == null || json.isBlank()) return State.closed();
        Matcher open = OPEN.matcher(json);
        boolean shown = open.find() && Boolean.parseBoolean(open.group(1));
        return new State(shown, text(LIVE, json), text(VIEWED, json), text(DRIVER, json));
    }

    private static void write(Path project, State state) {
        Path target = file(project);
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(temp, toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not record the Workbench window driver: " + failure.getMessage(), failure);
        }
    }

    private static String toJson(State state) {
        return "{\n"
                + "  \"open\": " + state.open() + ",\n"
                + "  \"liveRunId\": " + jsonString(state.liveRunId()) + ",\n"
                + "  \"viewedRunId\": " + jsonString(state.viewedRunId()) + ",\n"
                + "  \"driverAgent\": " + jsonString(state.driverAgent()) + "\n"
                + "}\n";
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Pattern fieldPattern(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*(null|\"([^\"]*)\")");
    }

    private static String text(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) return null;
        String value = matcher.group(2);
        return value == null || value.isBlank() ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
