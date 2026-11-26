package tools.dscode.launcher;

import tools.dscode.control.protocol.speed.ProcessTree;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.runtime.ResourceGate;
import tools.dscode.engine.runtime.WarmProcessFile;
import tools.dscode.engine.runtime.WarmSpine;
import tools.dscode.store.StoreGlueCompiler;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Warm-spine verbs: {@code spine start}, {@code spine stop}, {@code spine warm status}, and {@code spine sample}.
 * This class does not own a session verb.
 */
public final class SpineCli {
    private SpineCli() {
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "spine".equals(args[0].trim());
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0 && "hold".equals(args[0])) {
            Thread.sleep(Long.MAX_VALUE);
            return;
        }
        int code = execute(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public static String help() {
        return """
                Usage: spine start [--project=<dir>]
                       spine stop [--project=<dir>]
                       spine warm status [--project=<dir>]
                       spine sample [--json] [--project=<dir>]
                spine start is opt-in. It writes .pickleball/spine/warm.json with pid and started-at.
                pkb_warm defaults to off. CI and mvn test stay cold. Do not run spine start in CI.
                store run without a running warm JVM stays cold.
                """;
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: spine CLI expected the spine command");
            stderr.println("fix: spine help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        if (tokens.isEmpty() || isHelp(tokens.get(0))) {
            stdout.print(help());
            return 0;
        }
        Path project = project(tokens);
        String verb = tokens.get(0);
        try {
            return switch (verb) {
                case "start" -> start(project, stdout, stderr);
                case "stop" -> stop(project, stdout, stderr);
                case "warm" -> status(project, stdout);
                case "sample" -> sample(project, tokens, stdout);
                default -> unknown(verb, stderr);
            };
        } catch (IllegalStateException failure) {
            String message = failure.getMessage() == null ? "spine command failed" : failure.getMessage();
            for (String line : message.split("\\R")) stderr.println(line);
            if (!message.contains("fix:")) stderr.println("fix: spine warm status");
            return 1;
        } catch (Exception failure) {
            stderr.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            stderr.println("fix: spine warm status");
            return 1;
        }
    }

    private static int start(Path project, PrintStream stdout, PrintStream stderr) throws Exception {
        if (WarmProcessFile.alive(project)) {
            WarmProcessFile.Record record = WarmProcessFile.read(project).orElseThrow();
            stdout.println("warm=on");
            stdout.println("pid=" + record.pid());
            return 0;
        }
        StoreGlueCompiler.IncrementalDecision first = StoreGlueCompiler.warmStart(project, true);
        if (first.rebuild()) {
            EngineLog.log(project, "host", "warm", "rebuild");
        }
        long pid = spawn(project);
        WarmProcessFile.write(project, pid, Instant.now());
        EngineLog.log(project, "host", "warm", "start");
        stdout.println("warm=on");
        stdout.println("pid=" + pid);
        if ("wrapper".equals(first.tool())) stdout.println("compile=" + first.commandLine());
        return 0;
    }

    private static int sample(Path project, List<String> tokens, PrintStream stdout) {
        ResourceGate.Report report = ResourceGate.report(project, "sample");
        if (tokens.stream().anyMatch("--json"::equals)) stdout.println(ResourceGate.json(report));
        else {
            ResourceGate.Reading reading = report.reading();
            stdout.println("heapUsed=" + reading.heapUsed());
            stdout.println("heapMax=" + reading.heapMax());
            stdout.println("nonHeapUsed=" + reading.nonHeapUsed());
            stdout.println("gcTimeMs=" + reading.gcTimeMs());
            stdout.println("threadCount=" + reading.threadCount());
            stdout.println("decision=" + report.decision().wire());
        }
        return 0;
    }

    private static int stop(Path project, PrintStream stdout, PrintStream stderr) {
        WarmSpine.shared().stopChildren();
        Optional<WarmProcessFile.Record> record = WarmProcessFile.read(project);
        if (record.isEmpty()) {
            stdout.println("warm=off");
            stdout.println("pid=");
            return 0;
        }
        long pid = record.get().pid();
        if (pid > 0 && pid != ProcessHandle.current().pid()) ProcessTree.kill(pid);
        if (WarmProcessFile.processAlive(pid)) {
            stderr.println("error: warm JVM " + pid + " did not stop");
            stderr.println("fix: spine stop");
            return 1;
        }
        WarmProcessFile.delete(project);
        EngineLog.log(project, "host", "warm", "stop");
        stdout.println("warm=off");
        stdout.println("pid=");
        return 0;
    }

    private static int status(Path project, PrintStream stdout) {
        Optional<WarmProcessFile.Record> record = WarmProcessFile.read(project);
        boolean on = record.isPresent() && WarmProcessFile.processAlive(record.get().pid());
        stdout.println(on ? "warm=on" : "warm=off");
        stdout.println("pid=" + (on ? Long.toString(record.get().pid()) : ""));
        return 0;
    }

    static long spawn(Path project) throws IOException, InterruptedException {
        Path dir = WarmProcessFile.directory(project);
        Files.createDirectories(dir);
        Path args = dir.resolve("java.args");
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            throw new IllegalStateException("error: spine start has no classpath\nfix: run spine start from the project JVM");
        }
        String quoted = classpath.replace("\"", "\\\"");
        Files.writeString(args, "-cp\n\"" + quoted + "\"\ntools.dscode.launcher.SpineCli\nhold\n", StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "@" + args.toAbsolutePath().normalize());
        builder.directory(project.toFile());
        builder.redirectOutput(dir.resolve("warm.out.log").toFile());
        builder.redirectError(dir.resolve("warm.err.log").toFile());
        Process process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) break;
            if (process.pid() > 0) {
                Thread.sleep(50);
                if (process.isAlive()) return process.pid();
            }
            Thread.sleep(50);
        }
        String logged = "";
        Path err = dir.resolve("warm.err.log");
        if (Files.isRegularFile(err)) logged = Files.readString(err);
        throw new IllegalStateException("error: warm JVM did not stay up\nfix: read .pickleball/spine/warm.err.log\n" + logged);
    }

    private static int unknown(String verb, PrintStream stderr) {
        stderr.println("error: unknown spine command " + verb);
        stderr.println("fix: spine help");
        return 2;
    }

    private static boolean isHelp(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }

    static Path project(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("--project=")) {
                return Path.of(token.substring("--project=".length())).toAbsolutePath().normalize();
            }
            if ("--project".equals(token) && index + 1 < tokens.size()) {
                return Path.of(tokens.get(index + 1)).toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}
