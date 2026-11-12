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
        return args != null && args.length > 0 && args[0] != null && "log".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: log CLI expected the log command");
            stderr.println("fix: log list");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        Path project = project(tokens);
        String verb = "";
        List<String> rest = new ArrayList<>();
        boolean dir = false;
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("--project=")) continue;
            if ("--project".equals(token)) {
                index++;
                continue;
            }
            if (verb.isEmpty() && !token.startsWith("--")) {
                verb = token;
                continue;
            }
            if (token.startsWith("--dir")) dir = true;
            rest.add(token);
        }
        if (verb.isEmpty() || "help".equals(verb) || "--help".equals(verb) || "-h".equals(verb)) {
            stdout.print(help());
            return 0;
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
        CliResult result = DomEngineMain.run(argv.toArray(String[]::new));
        stdout.print(result.stdout());
        stderr.print(result.stderr());
        return result.exit();
    }

    public static String help() {
        return """
                Usage: log list [--project=<dir>] [--dir=<path>]
                Usage: log tail [--project=<dir>] [--file=<name>] [--lines=40]
                Usage: log show [--project=<dir>] [--file=<name>]
                Reads composite-logs and .pickleball/*.log, including history.log.
                history.log keeps its own 10 MB trim. This command does not rewrite it.
                Workbench only forwards this command.
                """;
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
