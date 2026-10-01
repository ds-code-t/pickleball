package tools.dscode.workbench;

import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ExampleRowSelector;
import tools.dscode.workbench.catalog.ConsumerFeatureCatalog;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;
import tools.dscode.workbench.player.GherkinPlayPlan;
import tools.dscode.workbench.player.LiveScenarioPlayer;
import tools.dscode.workbench.player.WorkbenchSaveResult;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One implementation of the session actions the Workbench window and the
 * command queue both call. The window is a client: when it is installed, the
 * command asks that window to show the existing playback. Headless sessions
 * use the same coordinator and services with no Swing.
 */
public final class WorkbenchSessionActions {
    public static final Set<String> OPS = Set.of(
            "open-scenario",
            "example",
            "play",
            "from-here",
            "pause",
            "execute-step",
            "insert-step",
            "update-step",
            "diagnostic-run",
            "save",
            "refresh",
            "session-sync",
            "worker-start",
            "worker-restart",
            "worker-stop",
            "stop"
    );

    private final WorkbenchServices services;
    private volatile Window window;
    private volatile String selectedDiagnosticRun = "";

    public WorkbenchSessionActions(WorkbenchServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    public static boolean known(String op) {
        return op != null && OPS.contains(op);
    }

    public void installWindow(Window window) {
        this.window = window;
    }

    public String selectedDiagnosticRun() {
        return selectedDiagnosticRun;
    }

    public Map<String, Object> dispatch(String op, Map<String, String> args) {
        Map<String, String> values = args == null ? Map.of() : args;
        return WorkbenchCallContext.callAs(WorkbenchLeaseHolder.AGENT, () -> handle(op, values));
    }

    public Map<String, Object> openScenario(String feature, String name, String example) {
        Opened opened = resolve(services.projectRoot(), feature, name, example);
        if (window != null) {
            show(client -> client.openResolved(opened));
            return accepted("open-scenario", "LOADED");
        }
        services.loadPickerScenario(
                opened.lines(),
                opened.file(),
                opened.name(),
                opened.startLine(),
                opened.endLine(),
                opened.exampleRow(),
                opened.exampleLabel(),
                opened.exampleSelector()
        );
        LinkedHashMap<String, Object> payload = accepted("open-scenario", "LOADED");
        payload.put("scenario", opened.name());
        payload.put("exampleRow", services.playback().origin().exampleRow());
        payload.put("example", opened.exampleSelector());
        return payload;
    }

    public Map<String, Object> example(String selector) {
        if (ExampleRowSelector.isInactive(selector)) {
            throw new IllegalArgumentException("example selector must not be blank.");
        }
        ExampleRowSelector.parse(selector);
        if (window != null) {
            show(client -> client.selectExample(selector.strip()));
            return accepted("example", selector.strip());
        }
        services.selectExample(selector.strip());
        LinkedHashMap<String, Object> payload = accepted("example", selector.strip());
        payload.put("exampleRow", services.playback().origin().exampleRow());
        return payload;
    }

    public Map<String, Object> play() {
        return play(false, null);
    }

    public Map<String, Object> fromHere(String stepId) {
        return play(true, stepId);
    }

    public Map<String, Object> pause() {
        if (window != null) {
            show(Window::pause);
            return accepted("pause", "PAUSED");
        }
        services.playback().pause();
        return accepted("pause", "PAUSED");
    }

    public Map<String, Object> stopPlayback() {
        if (window != null) {
            show(Window::stopPlayback);
            return accepted("stop", "STOPPED");
        }
        services.playback().stop();
        return accepted("stop", "STOPPED");
    }

    public Map<String, Object> executeStep(String text) {
        if (window != null) {
            show(client -> client.executeStep(text));
            return accepted("execute-step", "STARTED");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("execute-step text must not be blank.");
        }
        ControlBridgeCallResult result = services.executeStep(text, "");
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        String status = result != null && "SUCCESS".equals(result.status()) ? "SUCCESS" : "FAILED";
        payload.put("status", status);
        payload.put("op", "execute-step");
        payload.put("playback", status);
        return payload;
    }

    public Map<String, Object> insertStep(String text) {
        if (window != null) {
            show(client -> client.insertStep(text));
            return accepted("insert-step", "INSERTED");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("insert-step text must not be blank.");
        }
        LiveScenarioPlayer.Line inserted = services.playback().insertAndMaybeContinue(text);
        LinkedHashMap<String, Object> payload = accepted("insert-step", "INSERTED");
        payload.put("text", inserted.text());
        return payload;
    }

    public Map<String, Object> updateStep(String text) {
        if (window != null) {
            show(client -> client.updateStep(text));
            return accepted("update-step", "UPDATED");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("update-step text must not be blank.");
        }
        LiveScenarioPlayer.Line updated = services.player().updateSelectedStep(text);
        LinkedHashMap<String, Object> payload = accepted("update-step", "UPDATED");
        payload.put("text", updated.text());
        return payload;
    }

    public Map<String, Object> diagnosticRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("diagnostic-run requires a run id.");
        }
        String id = runId.strip();
        selectedDiagnosticRun = id;
        if (window != null) {
            show(client -> client.diagnosticRun(id));
            LinkedHashMap<String, Object> payload = accepted("diagnostic-run", "SHOWN");
            payload.put("run", id);
            return payload;
        }
        Object document = services.diagnosticRun(id);
        LinkedHashMap<String, Object> payload = accepted("diagnostic-run", "SHOWN");
        payload.put("run", id);
        payload.put("document", document == null ? "" : document.toString());
        return payload;
    }

    public Map<String, Object> save() {
        if (window != null) {
            WorkbenchSaveResult[] result = new WorkbenchSaveResult[1];
            show(client -> result[0] = client.presentSave());
            if (result[0] == null) return saveMap(WorkbenchSaveResult.cancelled("Save cancelled."));
            return saveMap(result[0]);
        }
        return saveMap(services.requestSave());
    }

    public Map<String, Object> refresh() {
        if (window != null) {
            show(Window::refreshStatus);
            return accepted("refresh", "REFRESHED");
        }
        return workerMap("refresh", services.workerStatus());
    }

    public Map<String, Object> sessionSync() {
        if (window != null) {
            show(Window::syncProject);
            return accepted("session-sync", "STARTED");
        }
        services.synchronize();
        return accepted("session-sync", "DONE");
    }

    public Map<String, Object> workerStart() {
        if (window != null) {
            show(Window::startWorker);
            return accepted("worker-start", "STARTED");
        }
        return workerMap("worker-start", services.startWorker());
    }

    public Map<String, Object> workerRestart() {
        if (window != null) {
            show(Window::restartWorker);
            return accepted("worker-restart", "STARTED");
        }
        return workerMap("worker-restart", services.restartWorker());
    }

    public Map<String, Object> workerStop() {
        if (window != null) {
            show(client -> {
                client.stopPlayback();
                client.stopWorker();
            });
            return accepted("worker-stop", "STOPPED");
        }
        services.playback().stop();
        return workerMap("worker-stop", services.stopWorker());
    }

    public static Opened resolve(Path projectRoot, String feature, String name, String example) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        if ((feature == null || feature.isBlank()) && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("open-scenario requires a feature path or name and a scenario name.");
        }
        String selector = example == null ? "" : example.strip();
        if (!ExampleRowSelector.isInactive(selector)) {
            ExampleRowSelector.parse(selector);
        }
        ConsumerFeatureCatalog catalog = ConsumerFeatureCatalog.scan(projectRoot, null);
        ConsumerFeatureCatalog.ScenarioEntry chosen = null;
        for (ConsumerFeatureCatalog.ScenarioEntry entry : catalog.candidateScenarios()) {
            if (!featureMatches(entry, feature) || !nameMatches(entry, name)) continue;
            if (chosen == null || (entry.exampleRow() == 0 && chosen.exampleRow() != 0)) {
                chosen = entry;
            }
        }
        if (chosen == null) {
            throw new IllegalArgumentException(
                    "No scenario matched feature '" + (feature == null ? "" : feature.strip())
                            + "' name '" + (name == null ? "" : name.strip()) + "'."
            );
        }
        List<String> lines = readFeature(chosen.file(), chosen.lines());
        int row = chosen.exampleRow();
        if (ExampleRowSelector.isInactive(selector) && row > 0) {
            selector = Integer.toString(row);
        }
        return new Opened(
                chosen.file(),
                chosen.name(),
                chosen.startLine(),
                chosen.endLine(),
                lines,
                ExampleRowSelector.isInactive(selector) ? row : 0,
                chosen.exampleLabel(),
                selector
        );
    }

    private Map<String, Object> handle(String op, Map<String, String> args) {
        if (!known(op)) {
            throw new IllegalArgumentException("Unknown session command: " + op);
        }
        return switch (op) {
            case "open-scenario" -> openScenario(args.get("feature"), args.get("name"), args.get("example"));
            case "example" -> example(first(args, "example", "text"));
            case "play" -> play();
            case "from-here" -> fromHere(first(args, "fromHere", "text"));
            case "pause" -> pause();
            case "execute-step" -> executeStep(args.get("text"));
            case "insert-step" -> insertStep(args.get("text"));
            case "update-step" -> updateStep(args.get("text"));
            case "diagnostic-run" -> diagnosticRun(first(args, "run", "text"));
            case "save" -> save();
            case "refresh" -> refresh();
            case "session-sync" -> sessionSync();
            case "worker-start" -> workerStart();
            case "worker-restart" -> workerRestart();
            case "worker-stop" -> workerStop();
            case "stop" -> stopPlayback();
            default -> throw new IllegalArgumentException("Unknown session command: " + op);
        };
    }

    private Map<String, Object> play(boolean fromHere, String stepId) {
        if (window != null) {
            show(client -> {
                if (fromHere) client.playFromHere(stepId);
                else client.playFromStart();
            });
            return accepted(fromHere ? "from-here" : "play", "STARTED");
        }
        if (fromHere && stepId != null && !stepId.isBlank()) {
            try {
                services.playback().seek(Long.parseLong(stepId.strip()));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("from-here expects a numeric step id.");
            }
        }
        return headlessPlay(fromHere);
    }

    private Map<String, Object> headlessPlay(boolean fromHere) {
        String op = fromHere ? "from-here" : "play";
        try {
            if (fromHere) services.playback().playFromHere();
            else services.playback().playFromStart();
            int executed = 0;
            int guard = Math.max(1, services.playback().playPlan().steps().size()) + 2;
            while (services.playback().running() && executed < guard) {
                GherkinPlayPlan.Step step = services.playback().nextPlanStep().orElse(null);
                if (step == null) break;
                int before = services.playback().planIndex();
                ControlBridgeCallResult result = services.executeStep(step.executeText(), "");
                executed++;
                if (result == null || !"SUCCESS".equals(result.status())) {
                    LinkedHashMap<String, Object> failed = failed(op, result == null ? "no result" : result.status());
                    failed.put("steps", executed);
                    return failed;
                }
                if (services.playback().running() && services.playback().planIndex() == before) {
                    LinkedHashMap<String, Object> failed = failed(op, "playback did not advance");
                    failed.put("steps", executed);
                    return failed;
                }
            }
            LinkedHashMap<String, Object> payload = accepted(op, "FINISHED");
            payload.put("steps", executed);
            return payload;
        } catch (RuntimeException failure) {
            return failed(op, failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private void show(Consumer<Window> call) {
        Window client = window;
        if (client == null) return;
        WorkbenchLeaseHolder holder = WorkbenchCallContext.current();
        Runnable job = () -> WorkbenchCallContext.runAs(holder, () -> {
            client.beginCommand();
            try {
                call.accept(client);
            } finally {
                client.endCommand();
            }
        });
        if (SwingUtilities.isEventDispatchThread()) {
            job.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(job);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating the Workbench window.", interrupted);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        }
    }

    private static String first(Map<String, String> args, String primary, String fallback) {
        String value = args.get(primary);
        if (value != null && !value.isBlank()) return value;
        return args.get(fallback);
    }

    private static boolean featureMatches(ConsumerFeatureCatalog.ScenarioEntry entry, String feature) {
        if (feature == null || feature.isBlank()) return true;
        String wanted = feature.strip();
        if (entry.featureName().equalsIgnoreCase(wanted)) return true;
        String norm = wanted.replace('\\', '/');
        String path = entry.file().toString().replace('\\', '/');
        String relative = entry.relativePath() == null ? "" : entry.relativePath().replace('\\', '/');
        return path.equalsIgnoreCase(norm)
                || path.toLowerCase(Locale.ROOT).endsWith("/" + norm.toLowerCase(Locale.ROOT))
                || relative.equalsIgnoreCase(norm)
                || relative.toLowerCase(Locale.ROOT).endsWith(norm.toLowerCase(Locale.ROOT));
    }

    private static boolean nameMatches(ConsumerFeatureCatalog.ScenarioEntry entry, String name) {
        if (name == null || name.isBlank()) return true;
        return entry.name().equalsIgnoreCase(name.strip());
    }

    private static List<String> readFeature(Path file, List<String> fallback) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                return Files.readAllLines(file);
            } catch (java.io.IOException ignored) {
                // Fall back to the catalog body.
            }
        }
        return new ArrayList<>(fallback == null ? List.of() : fallback);
    }

    private static LinkedHashMap<String, Object> accepted(String op, String playback) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "SUCCESS");
        payload.put("op", op);
        payload.put("playback", playback);
        return payload;
    }

    private static LinkedHashMap<String, Object> failed(String op, String message) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "FAILED");
        payload.put("op", op);
        payload.put("message", message == null ? "" : message);
        return payload;
    }

    private static Map<String, Object> workerMap(String op, WorkbenchWorkerStatus status) {
        LinkedHashMap<String, Object> payload = accepted(op, status != null && status.running() ? "RUNNING" : "STOPPED");
        payload.put("running", status != null && status.running());
        return payload;
    }

    private static Map<String, Object> saveMap(WorkbenchSaveResult result) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result != null && result.written() ? "SUCCESS" : "FAILED");
        payload.put("op", "save");
        payload.put("save", result == null ? "CANCELLED" : result.status());
        payload.put("message", result == null ? "" : result.message());
        return payload;
    }

    public record Opened(
            Path file,
            String name,
            int startLine,
            int endLine,
            List<String> lines,
            int exampleRow,
            String exampleLabel,
            String exampleSelector
    ) {
    }

    /** Swing callbacks. They show the existing window behavior and do not call back into the command. */
    public interface Window {
        void beginCommand();

        void endCommand();

        void openResolved(Opened opened);

        void selectExample(String selector);

        void playFromStart();

        void playFromHere(String stepId);

        void pause();

        void stopPlayback();

        void executeStep(String text);

        void insertStep(String text);

        void updateStep(String text);

        void diagnosticRun(String runId);

        WorkbenchSaveResult presentSave();

        void syncProject();

        void refreshStatus();

        void startWorker();

        void restartWorker();

        void stopWorker();
    }
}
