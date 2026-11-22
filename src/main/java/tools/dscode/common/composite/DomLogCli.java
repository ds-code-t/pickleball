package tools.dscode.common.composite;

import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pickleball {@code log} command. It only forwards to {@code domengine log}
 * and adds the engine log directory plus {@code .pickleball}, so
 * {@code history.log} is visible. It does not trim or rewrite that file.
 */
public final class DomLogCli {
    private DomLogCli() {
    }

    public static boolean handles(String[] args) {
        if (args == null || args.length == 0 || args[0] == null) return false;
        String command = args[0].trim();
        return "log".equals(command) || "log-tail".equals(command);
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: log CLI expected the log command");
            stderr.println("fix: log list");
            return 2;
        }
        boolean logTail = "log-tail".equals(args[0].trim());
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        Path project = project(tokens);
        String verb = "";
        List<String> rest = new ArrayList<>();
        boolean dir = false;
        boolean follow = false;
        Integer timeoutSeconds = null;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("--project=")) continue;
            if ("--project".equals(token)) {
                index++;
                continue;
            }
            if ("--follow".equals(token) || token.startsWith("--follow=")) {
                follow = true;
                continue;
            }
            if (token.startsWith("--timeout=")) {
                timeoutSeconds = timeoutSeconds(token.substring("--timeout=".length()));
                continue;
            }
            if ("--timeout".equals(token)) {
                if (index + 1 < tokens.size()) timeoutSeconds = timeoutSeconds(tokens.get(++index));
                else timeoutSeconds = -1;
                continue;
            }
            if (verb.isEmpty() && !token.startsWith("--")) {
                verb = token;
                continue;
            }
            if (token.startsWith("--dir")) dir = true;
            rest.add(token);
        }
        if (logTail && verb.isEmpty()) verb = "tail";
        if (verb.isEmpty() || "help".equals(verb) || "--help".equals(verb) || "-h".equals(verb)) {
            stdout.print(help());
            return 0;
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            stderr.println("error: log --timeout is seconds from 0 to 30");
            stderr.println("fix: log tail --follow --timeout=0");
            return 2;
        }
        List<String> argv = new ArrayList<>();
        argv.add("log");
        argv.add(verb);
        argv.addAll(rest);
        if (!dir) {
            argv.add("--dir");
            argv.add(project.resolve("composite-logs").toString());
            argv.add("--dir");
            argv.add(project.resolve(".pickleball").toString());
        }
        int waitSeconds = follow ? (timeoutSeconds == null ? 0 : timeoutSeconds) : 0;
        long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
        CliResult result;
        while (true) {
            result = DomEngineMain.run(argv.toArray(String[]::new));
            boolean content = result.stdout() != null && !result.stdout().isBlank();
            if (result.exit() != 0 || waitSeconds == 0 || content || System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stdout.print(result.stdout());
        stderr.print(result.stderr());
        return result.exit();
    }

    public static String help() {
        return """
                Usage: log list [--project=<dir>] [--dir=<path>]
                Usage: log tail [--project=<dir>] [--file=<name>] [--lines=40] [--follow] [--timeout=<seconds>]
                Usage: log-tail [--project=<dir>] [--file=<name>] [--lines=40] [--follow] [--timeout=<seconds>]
                Usage: log show [--project=<dir>] [--file=<name>]
                Reads composite-logs and .pickleball/*.log, including history.log.
                history.log keeps its own 10 MB trim. This command does not rewrite it.
                --follow reads the current file and returns in this process. It does not leave a process.
                With --follow, --timeout is seconds (default 0, one read, max 30).
                Workbench only forwards this command.
                """;
    }

    private static Integer timeoutSeconds(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int seconds = Integer.parseInt(raw.trim());
            if (seconds < 0) return -1;
            return Math.min(seconds, 30);
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static Path project(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("--project=")) {
                String value = token.substring("--project=".length());
                if (!value.isBlank()) return Path.of(value).toAbsolutePath().normalize();
            }
            if ("--project".equals(token) && index + 1 < tokens.size() && tokens.get(index + 1) != null) {
                return Path.of(tokens.get(index + 1)).toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}
