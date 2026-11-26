package tools.dscode.launcher;

import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.control.protocol.PickleballArtifactLocator;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.PickleballLocalStore;
import tools.dscode.control.protocol.PickleballVersion;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.PrintStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

/**
 * Extracts the version-matched, controller-only Workbench payload from the
 * consumer's Pickleball dependency and always launches it in a separate JVM.
 *
 * <p>The outer Pickleball JAR is executable ({@code java -jar pickleball-<ver>.jar})
 * and this class is its {@code Main-Class}. It never merges the nested controller
 * classpath into this JVM.</p>
 *
 * <p>Agent-facing Discover/hint/export-guidance/confirm run in this consumer JVM
 * so they can reuse DiagnosticCli and wrap Maven. Isolate/session-start,
 * execute-step, status, events, and stop/kill are one-shot HTTP clients against
 * a detached controller session. UI, MCP, and sync still extract and
 * forward to the controller JAR.</p>
 */
public final class PickleballWorkbenchLauncher {
    /**
     * Safety cap for the opaque thin controller JAR. Fat-jar regression guard:
     * the nested payload is Workbench plus protocol only, so 32 MiB is enough.
     */
    static final long MAX_PAYLOAD_BYTES = 32L * 1024 * 1024;

    static final String WORKBENCH_MAIN_CLASS = "tools.dscode.workbench.WorkbenchApplication";
    static final String OPEN_BOOTSTRAP_ENV = "PKB_OPEN_BOOTSTRAP";
    static final String REEXEC_ENV = "PKB_PICKLEBALL_REEXEC";

    private PickleballWorkbenchLauncher() {
    }

    public static void main(String[] args) {
        QuietSlf4j.init();
        int engine = dispatchEngineIfAny(args, System.out, System.err);
        if (engine >= 0) {
            if (engine != 0) exitProcess(engine);
            return;
        }
        int domengine = dispatchDomEngineIfAny(args, System.out, System.err);
        if (domengine >= 0) {
            if (domengine != 0) exitProcess(domengine);
            return;
        }
        int logs = dispatchLogIfAny(args, System.out, System.err);
        if (logs >= 0) {
            if (logs != 0) exitProcess(logs);
            return;
        }
        int dashboard = dispatchDashboardIfAny(args, System.out, System.err);
        if (dashboard >= 0) {
            if (dashboard != 0) exitProcess(dashboard);
            return;
        }
        int cross = dispatchCrossIfAny(args, System.out, System.err);
        if (cross >= 0) {
            if (cross != 0) exitProcess(cross);
            return;
        }
        int store = dispatchStoreIfAny(args, System.out, System.err);
        if (store >= 0) {
            if (store != 0) exitProcess(store);
            return;
        }
        if (WorkbenchCliHelp.isHelpRequest(args)) {
            int code = WorkbenchCliHelp.answer(args, System.out, System.err);
            CrossStore.journal(args, code, System.err);
            if (code != 0) exitProcess(code);
            return;
        }
        dispatch(args);
    }

    /**
     * The native engine is a core CLI, not a Workbench verb. Returns {@code -1}
     * when {@code args} are not an {@code engine} invocation.
     */
    static int dispatchEngineIfAny(String[] args, PrintStream out, PrintStream err) {
        if (!tools.dscode.engine.EngineCli.handles(args)) return -1;
        return VerbDispatch.run("engine", VerbArgs.capture(args), out, err);
    }

    /** Test hook. Production resolves {@code DomEngineMain.dispatch} on the first domengine call, not in {@code <clinit>}. */
    static java.util.function.Function<java.util.List<String>, dev.domengine.cli.CliResult> domEngineCall;

    static void resetDomEngineCall() {
        domEngineCall = null;
    }

    private static dev.domengine.cli.CliResult invokeDomEngine(java.util.List<String> rest) {
        java.util.function.Function<java.util.List<String>, dev.domengine.cli.CliResult> call = domEngineCall;
        if (call == null) {
            call = dev.domengine.cli.DomEngineMain::dispatch;
        }
        return call.apply(rest);
    }

    /**
     * Classpath form for a thin jar. {@code java -jar} does not see Jackson.
     * This does not ask the caller to shade a fat jar.
     */
    static String missingDomEngine(Throwable missing) {
        String type = missing == null ? "NoClassDefFoundError" : missing.getClass().getSimpleName();
        return "error: java -jar cannot load domengine (" + type + ").\n"
                + "fix: java -cp <classpath> tools.dscode.launcher.PickleballWorkbenchLauncher domengine ...\n"
                + "The published jar stays thin.\n";
    }

    /**
     * The Java DOM engine is a core CLI. Returns {@code -1} when {@code args}
     * are not a {@code domengine} invocation. Workbench only forwards.
     */
    static int dispatchDomEngineIfAny(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0 || args[0] == null || !"domengine".equals(args[0].trim())) return -1;
        return VerbDispatch.run("domengine", VerbArgs.capture(args), out, err);
    }

    /** In-process domengine body. {@link VerbDispatch} calls this. It does not call {@link VerbDispatch}. */
    static int runDomEngine(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        java.util.List<String> rest = new java.util.ArrayList<>();
        if (args != null) {
            for (int index = 1; index < args.length; index++) {
                if (args[index] != null) rest.add(args[index]);
            }
        }
        dev.domengine.cli.CliResult result;
        try {
            result = invokeDomEngine(rest);
        } catch (NoClassDefFoundError | ExceptionInInitializerError missing) {
            stderr.print(missingDomEngine(missing));
            return 1;
        }
        if (result.stdout() != null && !result.stdout().isEmpty()) {
            String text = result.stdout();
            if (desktopQuery(rest)) text = tools.dscode.common.agentfast.ElementRecord.rewriteDesktopQuery(text, fieldsOf(rest));
            text = tools.dscode.common.agentfast.JsonReply.envelope(text, domEngineOp(rest), projectOf(rest));
            stdout.print(text);
        }
        if (result.stderr() != null && !result.stderr().isEmpty()) stderr.print(result.stderr());
        return result.exit();
    }

    private static boolean desktopQuery(java.util.List<String> rest) {
        return rest.size() >= 2 && "desktop".equals(rest.get(0)) && "query".equals(rest.get(1));
    }

    private static String domEngineOp(java.util.List<String> rest) {
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String token : rest) {
            if (token == null || token.startsWith("--")) continue;
            words.add(token);
            if (words.size() == 3) break;
        }
        return words.isEmpty() ? "domengine" : "domengine " + String.join(" ", words);
    }

    private static String fieldsOf(java.util.List<String> rest) {
        for (int index = 0; index < rest.size(); index++) {
            String token = rest.get(index);
            if (token == null) continue;
            if (token.startsWith("--fields=")) return token.substring("--fields=".length());
            if ("--fields".equals(token) && index + 1 < rest.size()) return rest.get(index + 1);
        }
        return null;
    }

    private static java.nio.file.Path projectOf(java.util.List<String> rest) {
        for (int index = 0; index < rest.size(); index++) {
            String token = rest.get(index);
            if (token == null) continue;
            if (token.startsWith("--project=")) return java.nio.file.Path.of(token.substring("--project=".length()));
            if ("--project".equals(token) && index + 1 < rest.size() && rest.get(index + 1) != null) {
                return java.nio.file.Path.of(rest.get(index + 1));
            }
        }
        return java.nio.file.Path.of("").toAbsolutePath().normalize();
    }

    /**
     * The log dashboard is a core CLI, not a Workbench verb. It forwards to
     * {@code domengine log} and does not rewrite {@code history.log}.
     */
    static int dispatchLogIfAny(String[] args, PrintStream out, PrintStream err) {
        if (!tools.dscode.common.composite.DomLogCli.handles(args)) return -1;
        String verb = args[0] == null ? "log" : args[0].trim();
        return VerbDispatch.run(verb, VerbArgs.capture(args), out, err);
    }

    /**
     * The static dashboard is a core CLI, not a Workbench verb.
     * Returns {@code -1} when {@code args} are not a {@code dashboard} invocation.
     */
    static int dispatchDashboardIfAny(String[] args, PrintStream out, PrintStream err) {
        if (!tools.dscode.store.dashboard.DashboardCli.handles(args)) return -1;
        return VerbDispatch.run("dashboard", VerbArgs.capture(args), out, err);
    }

    /**
     * Cross-cutting notes, journal, and links. Returns {@code -1} when
     * {@code args} are not a {@code cross} invocation. Dispatched before store.
     */
    static int dispatchCrossIfAny(String[] args, PrintStream out, PrintStream err) {
        if (!CrossStore.handles(args)) return -1;
        return VerbDispatch.run("cross", VerbArgs.capture(args), out, err);
    }

    /**
     * Pickleball's store commands are a core CLI, not a Workbench verb.
     * Returns {@code -1} when {@code args} are not a {@code store} invocation.
     * This is not the Store binary.
     */
    static int dispatchStoreIfAny(String[] args, PrintStream out, PrintStream err) {
        if (!tools.dscode.store.StoreCli.handles(args)) return -1;
        return VerbDispatch.run("store", VerbArgs.capture(args), out, err);
    }

    /** Journal one completed Workbench verb, then stop the process when it failed. */
    private static void finish(String[] args, int code) {
        CrossStore.journal(args, code, System.err);
        if (code != 0) exitProcess(code);
    }

    /**
     * Every verb except help. Help must not reference this method: its bytecode
     * names the agent and session command classes.
     */
    private static void dispatch(String[] args) {
        WorkbenchCommandLine.Parsed parsed;
        try {
            parsed = WorkbenchCommandLine.parse(args);
            for (String notice : WorkbenchCommandLine.aliasNotices()) System.err.println(notice);
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            finish(args, 2);
            return;
        }
        Integer rejected = rejectUnknown(parsed, System.err);
        if (rejected != null) {
            finish(args, rejected);
            return;
        }
        Path project = parsed.project() != null
                ? parsed.project()
                : PickleballLocalLayout.findProjectRoot(Path.of(""));
        try {
            Optional<Path> hop = reexecJar(project);
            if (hop.isPresent()) {
                int exitCode = reexec(hop.get(), args);
                if (exitCode != 0) exitProcess(exitCode);
                return;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            System.err.println("Pickleball Workbench launcher was interrupted.");
            finish(args, 1);
            return;
        } catch (IOException failure) {
            System.err.println("Could not hop to the pinned Pickleball jar: " + failure.getMessage());
            finish(args, 1);
            return;
        }

        if (!WorkbenchCommandLine.isAgentCoreCommand(parsed.command())
                || !"export-guidance".equals(parsed.command())) {
            AgentCoordination.warnMissingCurrentTree(project, System.err);
            PickleballLocalStore.ensureQuietly(project);
        }

        if (AgentHop.shouldHop(parsed.command(), args)) {
            try {
                java.util.OptionalInt hopped = controllerHop(args);
                if (hopped.isPresent()) {
                    finish(args, hopped.getAsInt());
                    return;
                }
            } catch (Exception failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                System.err.println("error: " + message);
                System.err.println("fix: controller-start");
                finish(args, 1);
                return;
            }
        }

        if (WorkbenchCommandLine.isAgentCoreCommand(parsed.command()) && !controllerUiWithoutOps(parsed.command(), args)) {
            int exitCode = VerbDispatch.run(parsed.command(), VerbArgs.capture(args), System.out, System.err);
            if (exitCode != 0) exitProcess(exitCode);
            return;
        }
        if (WorkbenchCommandLine.isSessionClientCommand(parsed.command())) {
            int exitCode = VerbDispatch.run(parsed.command(), VerbArgs.capture(args), System.out, System.err);
            if (exitCode != 0) exitProcess(exitCode);
            return;
        }
        if (controllerUiWithoutOps(parsed.command(), args)) {
            int exitCode = WorkbenchUiServer.open(project, System.out, System.err);
            finish(args, exitCode);
            return;
        }

        String[] forwarded = parsed.forwarded() != null ? parsed.forwarded() : normalizedArguments(args);
        if (parsed.project() == null) {
            project = projectRoot(forwarded);
            PickleballLocalStore.ensureQuietly(project);
        }
        try {
            Path controllerJar = extractEmbeddedPayload(project);
            Process process = new ProcessBuilder(command(controllerJar, forwarded))
                    .directory(project.toFile())
                    .inheritIO()
                    .start();
            int exitCode = process.waitFor();
            finish(args, exitCode);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            System.err.println("Pickleball Workbench launcher was interrupted.");
            finish(args, 1);
        } catch (RuntimeException | IOException failure) {
            System.err.println("Could not launch Pickleball Workbench: " + failure.getMessage());
            finish(args, 1);
        }
    }

    /**
     * Ask the persistent controller to run {@code args}. Empty means the
     * controller is down or unreachable, so the caller runs the verb here.
     * There is no start wait.
     */
    static java.util.OptionalInt controllerHop(String[] args) throws Exception {
        int exitCode = PkbMain.run(args);
        if (exitCode == PkbMain.HOP_MISS) return java.util.OptionalInt.empty();
        return java.util.OptionalInt.of(exitCode);
    }

    /**
     * Keep the real status when this process is Maven {@code exec:java}.
     * That plugin can rewrite {@code System.exit} into an exception and Maven
     * then exits 1. {@code Runtime.halt} is not rewritten, so a rewritten or
     * ignored {@code System.exit} still ends the JVM with {@code code}.
     * Apache {@code mvn.cmd} and stock {@code mvnw.cmd} map every non-zero
     * JVM status to 1. Linux {@code mvnw} returns {@code code}. A Windows
     * script that needs the exact code matches {@code error:} or uses
     * {@code java -cp}.
     */
    static void exitProcess(int code) {
        keepExitCode(code, System::exit, status -> Runtime.getRuntime().halt(status));
    }

    static void keepExitCode(int code, IntConsumer systemExit, IntConsumer halt) {
        try {
            systemExit.accept(code);
        } catch (Throwable blocked) {
            halt.accept(code);
            return;
        }
        halt.accept(code);
    }

    /**
     * Bare {@code ui} starts the Node renderer in this JVM and does not extract the controller.
     * {@code ui --ops} is the catalog batch and stays on {@link VerbDispatch}.
     */
    private static boolean controllerUiWithoutOps(String command, String[] args) {
        if (!"ui".equals(command)) return false;
        if (args == null) return true;
        for (String token : args) {
            if (token == null) continue;
            if ("--ops".equals(token) || token.startsWith("--ops=")) return false;
        }
        return true;
    }

    /**
     * A typo must not extract the controller or start a JVM. The message names
     * the closest command and the help usage.
     */
    static Integer rejectUnknown(WorkbenchCommandLine.Parsed parsed, PrintStream err) {
        if (parsed == null || WorkbenchCommandLine.isKnownCommand(parsed.command())) return null;
        err.print(WorkbenchCliHelp.unknown(parsed.command()));
        return 2;
    }

    /**
     * Open-script bootstrap: if {@code current.json} pins another complete
     * version whose jar is in Maven local / Gradle cache, hop to that jar.
     * Direct {@code java -jar pickleball-X.jar} (no bootstrap env) stays on X
     * so version testing can switch the pin.
     */
    static Optional<Path> reexecJar(Path projectRoot) {
        return reexecJar(
                projectRoot,
                PickleballVersion.running(PickleballWorkbenchLauncher.class),
                "1".equals(System.getenv(OPEN_BOOTSTRAP_ENV)),
                "1".equals(System.getenv(REEXEC_ENV)),
                PickleballArtifactLocator.Repositories.discover()
        );
    }

    static Optional<Path> reexecJar(
            Path projectRoot,
            String runningVersion,
            boolean bootstrap,
            boolean alreadyReexec,
            PickleballArtifactLocator.Repositories repositories
    ) {
        if (alreadyReexec || !bootstrap || projectRoot == null) return Optional.empty();
        Path pickleball = PickleballLocalLayout.root(projectRoot);
        var current = PickleballLocalLayout.readCurrent(pickleball);
        if (current.isPresent() && current.get().usable()) {
            String pinned = current.get().pickleballVersion();
            if (pinned.equals(runningVersion)) return Optional.empty();
            return PickleballArtifactLocator.find(pinned, repositories);
        }
        Optional<PickleballArtifactLocator.Located> latest = PickleballArtifactLocator.findLatest(repositories);
        if (latest.isEmpty()) return Optional.empty();
        if (latest.get().version().equals(runningVersion)) return Optional.empty();
        return Optional.of(latest.get().jar());
    }

    static int reexec(Path jar, String[] args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(jar.toAbsolutePath().normalize().toString());
        if (args != null) command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        builder.environment().put(REEXEC_ENV, "1");
        return builder.start().waitFor();
    }

    static Path extractEmbeddedPayload(Path projectRoot) throws IOException {
        ClassLoader loader = PickleballWorkbenchLauncher.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(ControlProtocol.EMBEDDED_WORKBENCH_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Pickleball artifact is missing " + ControlProtocol.EMBEDDED_WORKBENCH_RESOURCE
                );
            }
            return extractPayload(projectRoot, input);
        }
    }

    static Path extractPayload(Path projectRoot, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Workbench payload must not be empty.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("Embedded Workbench payload exceeds the safety limit.");
        }
        return extractPayload(projectRoot, new ByteArrayInputStream(payload));
    }

    static Path extractPayload(Path projectRoot, InputStream payload) throws IOException {
        if (payload == null) {
            throw new IllegalArgumentException("Workbench payload must not be empty.");
        }
        Path project = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(project)) {
            throw new IllegalArgumentException(
                    "Consumer project directory does not exist: " + project
            );
        }
        Path staging = PickleballLocalLayout.workbenchStateRoot(project).resolve("controller");
        Files.createDirectories(staging);
        Path temporary = Files.createTempFile(staging, "pickleball-workbench-", ".tmp");
        try {
            HashedCopy copy = copyAndHash(payload, temporary);
            if (copy.bytes == 0) {
                throw new IllegalArgumentException("Workbench payload must not be empty.");
            }
            Path directory = staging.resolve(copy.checksum);
            Path target = directory.resolve("pickleball-workbench.jar");
            Files.createDirectories(directory);

            if (Files.isRegularFile(target) && copy.checksum.equals(sha256File(target))) {
                return target;
            }

            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!copy.checksum.equals(sha256File(target))) {
                throw new IllegalStateException("Extracted Workbench payload failed checksum verification.");
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Single fork seam for ui/mcp/sync/isolate session-start. Builds
     * {@code java -cp <thinJar:libs...> tools.dscode.workbench.WorkbenchApplication <args>}.
     */
    static List<String> command(Path controllerJar, String[] args) {
        Path jar = controllerJar.toAbsolutePath().normalize();
        WorkbenchRuntimeLibs.Manifest manifest = WorkbenchRuntimeLibs.withLaunchClassifier(
                WorkbenchRuntimeLibs.readManifest(jar),
                WorkbenchRuntimeLibs.platformKey()
        );
        Path libCache = WorkbenchRuntimeLibs.libCacheForController(jar, manifest.version());
        List<Path> libs = WorkbenchRuntimeLibs.resolve(manifest, libCache);

        StringBuilder classpath = new StringBuilder(jar.toString());
        for (Path lib : libs) {
            classpath.append(java.io.File.pathSeparator).append(lib.toAbsolutePath().normalize());
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(classpath.toString());
        command.add(WORKBENCH_MAIN_CLASS);
        command.addAll(List.of(args));
        return List.copyOf(command);
    }

    static String[] normalizedArguments(String[] args) {
        return WorkbenchCommandLine.parse(args).forwarded();
    }

    private static Path projectRoot(String[] args) {
        if (args.length >= 2
                && WorkbenchCommandLine.isForwardedCommand(args[0])
                && !args[1].isBlank()
                && !args[1].startsWith("-")) {
            return Path.of(args[1]).toAbsolutePath().normalize();
        }
        return PickleballLocalLayout.findProjectRoot(Path.of(""));
    }

    static Path javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static HashedCopy copyAndHash(InputStream input, Path target) throws IOException {
        MessageDigest digest = sha256Digest();
        long written = 0;
        try (DigestInputStream digested = new DigestInputStream(input, digest);
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = digested.read(buffer)) >= 0) {
                written += read;
                if (written > MAX_PAYLOAD_BYTES) {
                    throw new IllegalStateException("Embedded Workbench payload exceeds the safety limit.");
                }
                output.write(buffer, 0, read);
            }
        }
        return new HashedCopy(written, HexFormat.of().formatHex(digest.digest()));
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream digested = new DigestInputStream(input, digest)) {
            digested.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private record HashedCopy(long bytes, String checksum) {
    }
}
