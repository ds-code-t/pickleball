package tools.dscode.workbench;

import tools.dscode.control.protocol.ControlBridgeBrowserPageResult;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeServiceCallResult;
import tools.dscode.control.protocol.ControlBridgeStepOverrideResult;
import tools.dscode.control.protocol.ControlBridgeValueResult;
import tools.dscode.workbench.mcp.WorkbenchMcpServer;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSynchronizer;
import tools.dscode.workbench.ui.WorkbenchUi;
import tools.dscode.workbench.worker.WorkbenchLiveSession;
import tools.dscode.workbench.worker.WorkbenchWorkerManager;
import tools.dscode.workbench.worker.WorkbenchWorkerStatus;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/** Entry point for the standalone Pickleball Workbench controller. */
public final class WorkbenchApplication {

    private WorkbenchApplication() {
    }

    public static void main(String[] args) {
        try {
            WorkbenchRuntimeBoundary.verify();
        } catch (IllegalStateException isolationFailure) {
            System.err.println("Workbench controller isolation failed: " + isolationFailure.getMessage());
            System.exit(1);
            return;
        }

        if (args.length > 0 && "mcp".equals(args[0])) {
            int exitCode = runMcpProcess(args, System.out, System.err);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }

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
                case "isolate" -> isolate(args, out, err, stdinIsInteractiveTty());
                case "ui" -> ui(args);
                case "mcp" -> throw new IllegalArgumentException(
                        "MCP mode must be launched through the Workbench executable."
                );
                case "export-guidance", "hint", "discover-hint", "discover", "confirm" -> {
                    err.println("Workbench " + args[0]
                            + " runs through PickleballWorkbenchLauncher in the consumer JVM, not this controller JAR.");
                    yield 2;
                }
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

    static int runMcpProcess(String[] args, PrintStream protocolOut, PrintStream err) {
        try {
            Path project = requiredProject(args, "mcp");

            // MCP stdio owns the original stdout stream. Redirect ordinary JVM stdout
            // before constructing SDK/controller services so diagnostics cannot corrupt it.
            System.setOut(err);
            CountDownLatch inputClosed = new CountDownLatch(1);
            InputStream input = new EofSignalingInputStream(System.in, inputClosed);
            WorkbenchMcpServer server = new WorkbenchMcpServer(project, input, protocolOut);
            Runtime.getRuntime().addShutdownHook(new Thread(
                    server::close,
                    "pickleball-workbench-mcp-shutdown"
            ));

            try {
                inputClosed.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                err.println("Workbench mcp interrupted.");
                return 1;
            } finally {
                server.close();
            }
            return 0;
        } catch (RuntimeException failure) {
            err.println("Workbench mcp failed: " + failure.getMessage());
            return 1;
        }
    }

    private static final class EofSignalingInputStream extends FilterInputStream {
        private final CountDownLatch inputClosed;

        private EofSignalingInputStream(InputStream input, CountDownLatch inputClosed) {
            super(input);
            this.inputClosed = inputClosed;
        }

        @Override
        public int read() throws IOException {
            try {
                return signal(super.read());
            } catch (IOException failure) {
                inputClosed.countDown();
                throw failure;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                return signal(super.read(buffer, offset, length));
            } catch (IOException failure) {
                inputClosed.countDown();
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            inputClosed.countDown();
            super.close();
        }

        private int signal(int read) {
            if (read < 0) inputClosed.countDown();
            return read;
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

    private static int ui(String[] args) {
        Path project = requiredProject(args, "ui");
        WorkbenchUi.launch(project);
        return 0;
    }

    static int isolate(String[] args, PrintStream out, PrintStream err, boolean interactiveStdin) {
        IsolateArgs parsed = isolateArgs(args);
        Map<String, String> workerProperties;
        try {
            workerProperties = tools.dscode.workbench.discover.LastDiscoverSnapshot.workerSystemProperties(
                    parsed.project(), parsed.tags(), parsed.name()
            );
        } catch (RuntimeException failure) {
            err.println(failure.getMessage());
            err.println("Workbench CLI isolate failed. Do not register IDE MCP.");
            return 1;
        }

        boolean once = Boolean.getBoolean("pickleball.workbench.isolate.once");
        if (!once && !interactiveStdin) {
            err.println("Live isolate holds a paused worker for execute_step.");
            err.println("That needs an already-running Workbench (pre-attached workbench_* tools or an interactive TTY session).");
            err.println("Maven one-shot isolate is not the no-MCP agent path. Do not register IDE MCP. Do not start the GUI.");
            out.println("NEXT: confirm --tags/--name for the no-MCP path.");
            return 2;
        }

        try {
            new WorkbenchSynchronizer().sync(parsed.project());
            try (WorkbenchLiveSession live = new WorkbenchLiveSession(parsed.project(), workerProperties)) {
                WorkbenchWorkerStatus started = live.start();
                requireInteractiveWorker(started, "isolate");
                out.println("Workbench isolate worker: pid=" + started.pid()
                        + " scenario=" + started.scenarioId());
                out.println("Replayed pkb_runvars=" + workerProperties.get("pkb_runvars"));
                out.println("Isolate stays one paused scenario. Do not start the GUI.");
                if (once) {
                    requireCleanStop(live.stop());
                    return 0;
                }
                try {
                    System.in.read();
                } catch (IOException ignored) {
                    // stdin closed; stop the worker
                }
                requireCleanStop(live.stop());
            }
            return 0;
        } catch (RuntimeException failure) {
            err.println("Workbench CLI isolate failed: " + failure.getMessage());
            err.println("Do not register IDE MCP.");
            return 1;
        }
    }

    static boolean stdinIsInteractiveTty() {
        if (System.console() == null) return false;
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", "test -t 0");
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    private record IsolateArgs(Path project, String tags, String name) {
    }

    private static IsolateArgs isolateArgs(String[] args) {
        if (args.length < 2 || args[1].isBlank() || args[1].startsWith("-")) {
            throw new IllegalArgumentException(
                    "Usage: pickleball-workbench isolate <project> [--tags <expr>] [--name <expr>]"
            );
        }
        String tags = null;
        String name = null;
        for (int index = 2; index < args.length; index++) {
            String token = args[index];
            if (token.startsWith("--tags=")) {
                tags = token.substring("--tags=".length());
            } else if ("--tags".equals(token) && index + 1 < args.length) {
                tags = args[++index];
            } else if (token.startsWith("--name=")) {
                name = token.substring("--name=".length());
            } else if ("--name".equals(token) && index + 1 < args.length) {
                name = args[++index];
            } else if (name != null && !token.startsWith("-")) {
                name = name + " " + token;
            }
        }
        return new IsolateArgs(Path.of(args[1]), tags, name);
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

            requireStepOverrideSuccess(
                    live.compileStepOverride(
                            "workbench-live-generated",
                            "^WORKBENCH LIVE OVERRIDE ([A-Za-z]+)$",
                            overrideSource("first-")
                    ),
                    "first Step Override compilation"
            );
            requireSuccess(
                    live.executeStep("WORKBENCH LIVE OVERRIDE alpha"),
                    "first override-only execution"
            );
            requireValue(
                    live.mappingGet("OVERRIDE", "workbenchStepOverrideValue"),
                    "first-alpha",
                    "first Step Override effect"
            );

            requireStepOverrideSuccess(
                    live.compileStepOverride(
                            "workbench-live-generated",
                            "^WORKBENCH LIVE OVERRIDE ([A-Za-z]+)$",
                            overrideSource("second-")
                    ),
                    "replacement Step Override compilation"
            );
            requireSuccess(
                    live.executeStep("WORKBENCH LIVE OVERRIDE beta"),
                    "replacement override-only execution"
            );
            requireValue(
                    live.mappingGet("OVERRIDE", "workbenchStepOverrideValue"),
                    "second-beta",
                    "replacement Step Override effect"
            );
            if (live.stepOverrides().size() != 1) {
                throw new IllegalStateException("Workbench did not retain exactly one replacement Step Override.");
            }
            if (!live.removeStepOverride("workbench-live-generated")) {
                throw new IllegalStateException("Workbench could not remove the generated Step Override.");
            }
            requireStatus(
                    live.executeStep("WORKBENCH LIVE OVERRIDE gamma"),
                    "FAILED",
                    "override removal fallback"
            );

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

            out.println("Raw Gherkin, mapping, service-call, browser, and Step Override operations reused one worker.");
            out.println("Persistent context: pid=" + after.pid() + " runtime=" + after.runtimeId());

            requireCleanStop(live.stop());
            out.println("Live worker stopped gracefully with exit=0.");
        }
        return 0;
    }

    private static String overrideSource(String prefix) {
        return """
                package tools.dscode.workbench.generated;
                import tools.dscode.control.api.MappingControl;
                import tools.dscode.control.override.StepOverrideContext;
                import tools.dscode.control.override.StepOverrideHandler;

                public final class {{CLASS_NAME}} implements StepOverrideHandler {
                    public Object execute(StepOverrideContext context) {
                        MappingControl.put(
                            "OVERRIDE",
                            "workbenchStepOverrideValue",
                            "%s" + context.captures().getFirst()
                        );
                        return null;
                    }
                }
                """.formatted(prefix);
    }

    private static void requireStepOverrideSuccess(
            ControlBridgeStepOverrideResult result,
            String label
    ) {
        if (result == null || !"SUCCESS".equals(result.status()) || result.override() == null) {
            throw new IllegalStateException(label + " failed" + failureDetail(
                    result == null ? null : result.status(),
                    result == null || result.error() == null ? null : result.error().type(),
                    result == null || result.error() == null ? null : result.error().message()
            ));
        }
    }

    private static void requireSuccess(ControlBridgeCallResult result, String label) {
        requireStatus(result, "SUCCESS", label);
    }

    private static void requireStatus(
            ControlBridgeCallResult result,
            String expectedStatus,
            String label
    ) {
        if (result == null || !expectedStatus.equals(result.status())) {
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
        out.println("  java -jar pickleball-workbench-<version>.jar isolate <project> [--tags <expr>] [--name <expr>]");
        out.println("  java -jar pickleball-workbench-<version>.jar mcp <project>");
        out.println("  java -jar pickleball-workbench-<version>.jar ui <project>");
        out.println("  java -jar pickleball-workbench-<version>.jar --version");
        out.println();
        out.println("Agent-facing Discover/hint/export-guidance/confirm run through PickleballWorkbenchLauncher.");
        out.println("sync uses the selected project wrapper and materializes .pickleball/workbench.");
        out.println("worker-check starts, restarts, and gracefully stops direct consumer workers without rebuilding.");
        out.println("live-check exercises raw Gherkin, Step Override, and live runtime operations on one persistent worker.");
        out.println("isolate holds a paused worker from the last Discover snapshot when stdin is an interactive TTY, or when pickleball.workbench.isolate.once is set.");
        out.println("Non-TTY Maven one-shot isolate does not hold a worker; NEXT is confirm --tags/--name. Live isolate needs an already-running Workbench (pre-attached workbench_* or interactive session). Do not start ui for agents.");
        out.println("mcp serves the same Workbench services over protocol-only stdio; optional host wiring, not an agent setup step.");
        out.println("ui opens the thin Swing Workbench over the same controller services and writes a localhost agent-attach endpoint to .pickleball/workbench/attach.json.");
    }
}
