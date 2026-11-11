package tools.dscode.dalton;

import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Fast path for {@code dalton run}. A warm JVM holds the cached classpath and
 * executes with the same engine as a normal run, with {@code pkb_dalton=true}
 * so feature bytes come from the database. Java glue is fingerprinted by size
 * and modification time. A change compiles once and restarts that JVM.
 */
public final class DaltonRun {
    public static final String CACHE = "classpath.cache";
    public static final String FINGERPRINT = "glue.fingerprint";
    public static final String WARM = "warm.txt";

    private DaltonRun() {
    }

    public record Plan(String fingerprint, boolean compile, boolean restart, List<String> compileCommand) {
        public Plan {
            compileCommand = compileCommand == null ? List.of() : List.copyOf(compileCommand);
        }

        public String line() {
            return "fingerprint=" + fingerprint + " compile=" + compile + " restart=" + restart;
        }
    }

    public record Outcome(int exit, String out, String err, boolean reused, long millis, Plan plan) {
    }

    public static Plan plan(Path project) throws java.io.IOException {
        Path root = root(project);
        String fingerprint = glueFingerprint(root);
        String saved = readIfExists(root.resolve(".pickleball").resolve("dalton").resolve(FINGERPRINT));
        boolean compile = saved == null || !saved.equals(fingerprint);
        boolean alive = workerAlive(root, fingerprint);
        return new Plan(fingerprint, compile, compile || !alive, compileCommand(root));
    }

    public static Outcome execute(Path project, List<String> dashD, boolean dryRun) throws Exception {
        Path root = root(project);
        long started = System.nanoTime();
        Plan plan = plan(root);
        if (dryRun) {
            return new Outcome(0, "dry-run " + plan.line() + "\n", "", false, elapsed(started), plan);
        }
        if (plan.compile()) {
            if (plan.compileCommand().isEmpty()) {
                return new Outcome(1, "", "error: glue changed and this project has no mvnw or gradlew\n"
                        + "fix: compile the tests, then run dalton run again\n", false, elapsed(started), plan);
            }
            int compiled = compile(root, plan.compileCommand());
            if (compiled != 0) {
                return new Outcome(compiled, "", "error: glue compile failed\nfix: run the project wrapper and read its log\n",
                        false, elapsed(started), plan);
            }
            Files.createDirectories(state(root));
            Files.writeString(state(root).resolve(FINGERPRINT), glueFingerprint(root), StandardCharsets.UTF_8);
            stop(root);
            plan = plan(root);
        } else {
            Files.createDirectories(state(root));
            Files.writeString(state(root).resolve(FINGERPRINT), plan.fingerprint(), StandardCharsets.UTF_8);
        }
        Path cache = state(root).resolve(CACHE);
        if (!Files.isRegularFile(cache)) {
            String built = writeClasspath(root);
            if (built == null) {
                return new Outcome(1, "",
                        "error: Dalton run has no classpath cache\n"
                                + "fix: run the project wrapper once so the Workbench classpath cache exists, or set pkb_dalton_run_classpath\n",
                        false, elapsed(started), plan);
            }
        }
        boolean reused = workerAlive(root, Files.readString(state(root).resolve(FINGERPRINT)).trim());
        if (!reused) startWorker(root);
        List<String> properties = runProperties(dashD);
        String response = send(root, runRequest(root, properties), Duration.ofMinutes(10));
        int exit = exitCode(response);
        String body = body(response);
        DaltonLog.log(root, "run", "worker", (reused ? "reused " : "started ") + "exit=" + exit);
        return new Outcome(exit, body, "", reused, elapsed(started), plan);
    }

    public static void stop(Path project) {
        Path root = root(project);
        Path file = state(root).resolve(WARM);
        if (!Files.isRegularFile(file)) return;
        try {
            Warm warm = readWarm(file);
            try {
                send(root, "STOP\n", Duration.ofSeconds(3));
            } catch (Exception ignored) {
                // The process may already be gone.
            }
            ProcessHandle.of(warm.pid()).ifPresent(handle -> {
                if (handle.pid() != ProcessHandle.current().pid()) handle.destroy();
            });
        } catch (Exception ignored) {
            // Stopping a missing worker is not a run failure.
        }
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // The next start overwrites it.
        }
    }

    public static String glueFingerprint(Path project) throws java.io.IOException {
        Path root = root(project);
        MessageDigest digest = sha256();
        stamp(digest, root, List.of("src/main/java", "src/test/java"), ".java");
        stamp(digest, root, List.of(
                "target/classes",
                "target/test-classes",
                "build/classes/java/main",
                "build/classes/java/test"
        ), ".class");
        return HexFormat.of().formatHex(digest.digest());
    }

    public static List<String> compileCommand(Path project) {
        Path root = root(project);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            Path wrapper = root.resolve(windows ? "mvnw.cmd" : "mvnw");
            if (Files.isRegularFile(wrapper)) return List.of(wrapper.toString(), "-q", "test-compile");
            return List.of(windows ? "mvn.cmd" : "mvn", "-q", "test-compile");
        }
        Path gradle = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(gradle)) return List.of(gradle.toString(), "testClasses", "--no-daemon");
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return List.of(windows ? "gradle.bat" : "gradle", "testClasses", "--no-daemon");
        }
        return List.of();
    }

    public static List<String> selection(String tags, String name, String glue, List<String> rawDashD) {
        List<String> extra = new ArrayList<>();
        if (tags != null && !tags.isBlank()) extra.add("-Dpkb_tags=" + tags);
        if (name != null && !name.isBlank()) extra.add("-Dpkb_name=" + name);
        if (glue != null && !glue.isBlank()) extra.add("-Dpkb_glue=" + glue);
        if (rawDashD != null) {
            for (String token : rawDashD) {
                if (token != null && token.startsWith("-D") && !token.isBlank()) extra.add(token);
            }
        }
        return extra;
    }

    public static List<String> runProperties(List<String> extra) {
        List<String> properties = new ArrayList<>();
        if (extra != null) {
            for (String token : extra) {
                if (token == null || !token.startsWith("-D") || token.isBlank()) continue;
                if (token.startsWith("-Dpkb_dalton=") || token.startsWith("-Djunit.platform.discovery.includeEngines=")) {
                    continue;
                }
                properties.add(token);
            }
        }
        properties.add("-Dpkb_dalton=true");
        properties.add(ConsumerMavenTestRunner.PLATFORM_CUCUMBER_ENGINE);
        return List.copyOf(properties);
    }

    static String writeClasspath(Path project) throws java.io.IOException {
        Path root = root(project);
        String override = System.getProperty("pkb_dalton_run_classpath");
        List<String> entries = new ArrayList<>();
        if (override != null && !override.isBlank()) {
            for (String part : override.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) entries.add(part);
            }
        }
        Path workbench = tools.dscode.control.protocol.PickleballLocalLayout.workbenchStateRoot(root).resolve(CACHE);
        if (entries.isEmpty() && Files.isRegularFile(workbench)) {
            String text = Files.readString(workbench, StandardCharsets.UTF_8).trim();
            if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) text = text.substring(1, text.length() - 1);
            for (String part : text.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) entries.add(part);
            }
        }
        if (entries.isEmpty()) return null;
        Path file = state(root).resolve(CACHE);
        Files.createDirectories(file.getParent());
        String joined = String.join(java.io.File.pathSeparator, entries);
        if (joined.indexOf(' ') >= 0) joined = "\"" + joined + "\"";
        Files.writeString(file, joined + "\n", StandardCharsets.UTF_8);
        return joined;
    }

    private static void startWorker(Path root) throws Exception {
        stop(root);
        Path cache = state(root).resolve(CACHE);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-cp",
                "@" + cache.toAbsolutePath().normalize(),
                "tools.dscode.dalton.DaltonRunWorker",
                state(root).toString()
        );
        builder.directory(root.toFile());
        builder.redirectOutput(state(root).resolve("warm.out.log").toFile());
        builder.redirectError(state(root).resolve("warm.err.log").toFile());
        builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (workerAlive(root, Files.readString(state(root).resolve(FINGERPRINT)).trim())) return;
            Thread.sleep(50);
        }
        String err = "";
        Path log = state(root).resolve("warm.err.log");
        if (Files.isRegularFile(log)) err = Files.readString(log);
        throw new IllegalStateException("warm worker did not start. " + err);
    }

    private static boolean workerAlive(Path root, String fingerprint) {
        Path file = state(root).resolve(WARM);
        if (!Files.isRegularFile(file)) return false;
        try {
            Warm warm = readWarm(file);
            if (!fingerprint.equals(warm.fingerprint())) return false;
            if (ProcessHandle.of(warm.pid()).isEmpty()) return false;
            String pong = send(root, "PING\n", Duration.ofSeconds(2));
            return pong.startsWith("PONG");
        } catch (Exception failure) {
            return false;
        }
    }

    private static String send(Path root, String request, Duration timeout) throws Exception {
        Warm warm = readWarm(state(root).resolve(WARM));
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), warm.port())) {
            socket.setSoTimeout((int) timeout.toMillis());
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.print(request);
            writer.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
                if ("END".equals(line) || "PONG".equals(line) || "BYE".equals(line)) break;
            }
            return body.toString();
        }
    }

    private static String runRequest(Path project, List<String> properties) {
        StringBuilder request = new StringBuilder();
        request.append("RUN\n");
        for (String property : properties) request.append(property).append('\n');
        request.append(".\n");
        request.append(project.toAbsolutePath().normalize()).append('\n');
        return request.toString();
    }

    private static int compile(Path root, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        if (!process.waitFor(20, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return 1;
        }
        return process.exitValue();
    }

    private static int exitCode(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("EXIT ")) {
                try {
                    return Integer.parseInt(line.substring(5).trim());
                } catch (NumberFormatException failure) {
                    return 1;
                }
            }
        }
        return 1;
    }

    private static String body(String response) {
        StringBuilder body = new StringBuilder();
        boolean seen = false;
        for (String line : response.split("\\R", -1)) {
            if (line.startsWith("EXIT ")) {
                seen = true;
                continue;
            }
            if (!seen || "END".equals(line)) continue;
            body.append(line).append('\n');
        }
        return body.toString();
    }

    private static void stamp(MessageDigest digest, Path root, List<String> directories, String suffix) throws java.io.IOException {
        for (String relative : directories) {
            Path directory = root.resolve(relative);
            if (!Files.isDirectory(directory)) continue;
            try (var walk = Files.walk(directory)) {
                List<Path> files = walk.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(suffix))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    String key = root.relativize(file).toString().replace('\\', '/');
                    digest.update(key.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 1);
                }
            }
        }
    }

    private static Warm readWarm(Path file) throws java.io.IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 3) throw new java.io.IOException("warm file is short");
        long pid = Long.parseLong(lines.get(0).trim());
        int port = Integer.parseInt(lines.get(1).trim());
        return new Warm(pid, port, lines.get(2).trim());
    }

    private static String readIfExists(Path file) throws java.io.IOException {
        if (!Files.isRegularFile(file)) return null;
        String text = Files.readString(file, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? null : text;
    }

    private static Path state(Path root) {
        return root.resolve(".pickleball").resolve("dalton");
    }

    private static Path root(Path project) {
        return project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Warm(long pid, int port, String fingerprint) {
    }
}
