package tools.dscode.workbench;

import tools.dscode.control.bridge.ControlBridgeBrowserPageResult;
import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeServiceCallResult;
import tools.dscode.control.bridge.ControlBridgeValueResult;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;
import tools.dscode.workbench.worker.WorkbenchLiveSession;
import tools.dscode.workbench.worker.WorkbenchWorkerManager;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Entry point for the standalone Pickleball Workbench controller. */
public final class WorkbenchApplication {

    private WorkbenchApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage(out);
            return 0;
        }
        if (isVersion(args[0])) {
            out.println("Pickleball Workbench " + implementationVersion());
            return 0;
        }

        try {
            return switch (args[0]) {
                case "sync" -> sync(args, out);
                case "status" -> status(args, out);
                case "worker-check" -> workerCheck(args, out);
                case "live-check" -> liveCheck(args, out);
                default -> {
                    err.println("Unknown Workbench command: " + args[0]);
                    err.println("Run with --help for available commands.");
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("Workbench " + args[0] + " failed: " + failure.getMessage());
            return 1;
        }
    }

    private static int sync(String[] args, PrintStream out) {
        Path project = requiredProject(args, "sync");
        WorkbenchManifest manifest = new WorkbenchSynchronizer().sync(project);
        out.println("Synchronized " + manifest.projectType() + " project: " + manifest.projectRoot());
        out.println("Live output: " + manifest.liveOutput());
        out.println("Fingerprint: " + manifest.fingerprint());
        return 0;
    }

    private static int status(String[] args, PrintStream out) {
        Path project = requiredProject(args, "status");
        WorkbenchManifest manifest = WorkbenchManifest.read(project);
        out.println("Project: " + manifest.projectRoot());
        out.println("Type: " + manifest.projectType());
        out.println("Synchronized: " + manifest.synchronizedAt());
        out.println("Live output: " + manifest.liveOutput());
        out.println("Dependencies: " + manifest.dependencyClasspath().size());
        out.println("Fingerprint: " + manifest.fingerprint());
        return 0;
    }

    private static int workerCheck(String[] args, PrintStream out) {
        Path project = requiredProject(args, "worker-check");
        try (WorkbenchWorkerManager workers = new WorkbenchWorkerManager(project)) {
            WorkbenchWorkerStatus first = workers.startInteractive();
            requireInteractiveWorker(first, "initial");
            out.println("Worker started: pid=" + first.pid() + " scenario=" + first.scenarioId());

            WorkbenchWorkerStatus second = workers.restartInteractive();
            requireInteractiveWorker(second, "restarted");
            if (first.pid().equals(second.pid())) {
                throw new IllegalStateException("Restart did not create a fresh consumer JVM.");
            }
            out.println("Worker restarted without build: pid=" + second.pid() + " scenario=" + second.scenarioId());

            requireCleanStop(workers.stop());
            out.println("Worker stopped gracefully with exit=0.");
        }
        return 0;
    }

    private static int liveCheck(String[] args, PrintStream out) {
        Path project = requiredProject(args, "live-check");
        try (WorkbenchLiveSession live = new WorkbenchLiveSession(
                project,
                Map.of("pkb_browser", "CHROME_HEADLESS")
        )) {
            WorkbenchWorkerStatus started = live.start();
            requireInteractiveWorker(started, "live");
            out.println("Live worker: pid=" + started.pid() + " scenario=" + started.scenarioId());

            requireSuccess(live.executeStep("CONTROL API TEST STEP"), "consumer Gherkin step");

            requireSuccess(
                    live.mappingPut("OVERRIDE", "workbenchLiveValue", "first"),
                    "initial mapping write"
            );
            requireValue(live.mappingGet("OVERRIDE", "workbenchLiveValue"), "first", "mapping read");
            requireValue(live.mappingResolve("<workbenchLiveValue>"), "first", "mapping resolution");
            requireSuccess(
                    live.executeStep(", ensure \"<workbenchLiveValue>\" equals \"first\""),
                    "mapped Gherkin assertion"
            );

            ControlBridgeServiceCallResult service = live.serviceCall("%health-full-url");
            if (!"SUCCESS".equals(service.status())
                    || service.evidence() == null
                    || !Integer.valueOf(200).equals(service.evidence().statusCode())) {
                throw new IllegalStateException("Live service-call control did not return HTTP 200.");
            }

            requireSuccess(live.executeStep("navigate to: URL.home"), "browser navigation step");
            ControlBridgeBrowserPageResult page = live.browserPage();
            if (!"SUCCESS".equals(page.status())
                    || page.page() == null
                    || page.page().pageSource() == null
                    || !page.page().pageSource().contains("Pickleball Test Lab")) {
                throw new IllegalStateException("Live browser evidence did not contain the consumer home page.");
            }

            requireSuccess(
                    live.mappingPut("OVERRIDE", "workbenchLiveValue", "second"),
                    "second mapping write"
            );
            requireSuccess(
                    live.executeStep(", ensure \"<workbenchLiveValue>\" equals \"second\""),
                    "second mapped Gherkin assertion"
            );

            WorkbenchWorkerStatus after = live.status();
            requireInteractiveWorker(after, "post-operation");
            if (!Objects.equals(started.pid(), after.pid())
                    || !Objects.equals(started.runtimeId(), after.runtimeId())
                    || !Objects.equals(started.scenarioId(), after.scenarioId())) {
                throw new IllegalStateException("Live operations did not remain on one persistent worker context.");
            }

            out.println("Raw Gherkin, mapping, service-call, and browser operations reused one worker.");
            out.println("Persistent context: pid=" + after.pid() + " runtime=" + after.runtimeId());

            requireCleanStop(live.stop());
            out.println("Live worker stopped gracefully with exit=0.");
        }
        return 0;
    }

    private static void requireSuccess(ControlBridgeCallResult result, String label) {
        if (result == null || !"SUCCESS".equals(result.status())) {
            throw new IllegalStateException(label + " failed" + failureDetail(
                    result == null ? null : result.status(),
                    result == null || result.error() == null ? null : result.error().type(),
                    result == null || result.error() == null ? null : result.error().message()
            ));
        }
    }

    private static void requireSuccess(ControlBridgeValueResult result, String label) {
        if (result == null || !"SUCCESS".equals(result.status())) {
            throw new IllegalStateException(label + " failed" + failureDetail(
                    result == null ? null : result.status(),
                    result == null || result.error() == null ? null : result.error().type(),
                    result == null || result.error() == null ? null : result.error().message()
            ));
        }
    }

    private static String failureDetail(String status, String type, String message) {
        StringBuilder detail = new StringBuilder(" with status ").append(status == null ? "null" : status);
        if (type != null && !type.isBlank()) detail.append("; ").append(type);
        if (message != null && !message.isBlank()) detail.append(": ").append(message);
        return detail.append('.').toString();
    }

    private static void requireValue(ControlBridgeValueResult result, String expected, String label) {
        requireSuccess(result, label);
        String actual = result.value() == null ? null : result.value().text();
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(label + " expected '" + expected + "' but was '" + actual + "'.");
        }
    }

    private static void requireCleanStop(WorkbenchWorkerStatus stopped) {
        if (stopped.running()) {
            throw new IllegalStateException("Worker remained active after bounded shutdown.");
        }
        if (stopped.exitCode() == null || stopped.exitCode() != 0) {
            throw new IllegalStateException("Anchor worker exited with code " + stopped.exitCode() + ".");
        }
    }

    private static void requireInteractiveWorker(WorkbenchWorkerStatus status, String label) {
        if (!status.running() || !status.paused() || status.pid() == null || status.scenarioId() == null) {
            throw new IllegalStateException("The " + label + " worker did not reach a paused interactive anchor.");
        }
    }

    private static Path requiredProject(String[] args, String command) {
        if (args.length != 2 || args[1].isBlank()) {
            throw new IllegalArgumentException("Usage: pickleball-workbench " + command + " <project>");
        }
        return Path.of(args[1]);
    }

    private static boolean isHelp(String value) {
        return "help".equals(value) || "--help".equals(value) || "-h".equals(value);
    }

    private static boolean isVersion(String value) {
        return "version".equals(value) || "--version".equals(value) || "-V".equals(value);
    }

    private static String implementationVersion() {
        String version = WorkbenchApplication.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static void printUsage(PrintStream out) {
        out.println("Pickleball Workbench");
        out.println();
        out.println("Usage:");
        out.println("  java -jar pickleball-workbench-<version>.jar sync <project>");
        out.println("  java -jar pickleball-workbench-<version>.jar status <project>");
        out.println("  java -jar pickleball-workbench-<version>.jar worker-check <project>");
        out.println("  java -jar pickleball-workbench-<version>.jar live-check <project>");
        out.println("  java -jar pickleball-workbench-<version>.jar --version");
        out.println();
        out.println("sync uses the selected project wrapper and materializes .pickleball/workbench.");
        out.println("worker-check starts, restarts, and gracefully stops direct consumer workers without rebuilding.");
        out.println("live-check exercises raw Gherkin and live runtime operations on one persistent worker.");
    }
}
