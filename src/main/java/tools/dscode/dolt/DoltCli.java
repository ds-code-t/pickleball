package tools.dscode.dolt;

import tools.dscode.dolt.install.DoltInstaller;
import tools.dscode.dolt.server.DoltServer;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pickleball {@code dolt} commands. These are Pickleball's layer backed by the
 * real Dolt database. They are not the Dolt binary's own {@code dolt} CLI.
 * Workbench, MCP, and Gradle only forward here.
 */
public final class DoltCli {
    private DoltCli() {
    }

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "dolt".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: Dolt CLI expected the dolt command");
            stderr.println("fix: pickleball dolt help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        String command = tokens.isEmpty() ? "help" : tokens.get(0);
        if (command.startsWith("--")) command = "help";
        return switch (command) {
            case "help", "--help", "-h" -> {
                stdout.print(help());
                yield 0;
            }
            case "install" -> DoltInstaller.cli(tokens, stdout, stderr);
            case "server" -> DoltServer.cli(tokens, stdout, stderr);
            case "migrate" -> tools.dscode.dalton.DaltonMigrate.cli(tokens, stdout, stderr);
            default -> {
                stderr.println("error: Unknown Dolt command: " + command);
                stderr.println("fix: pickleball dolt help");
                yield 2;
            }
        };
    }

    public static String help() {
        return """
                Usage: pickleball dolt install [--check] [--force] [--dir=<path>] [--project=<dir>]
                Usage: pickleball dolt server start|stop|status [--json] [--project=<dir>]
                Usage: pickleball dolt migrate [--dry-run] [--remove-legacy] [--project=<dir>]
                Pickleball dolt commands are Pickleball's layer. They are not the Dolt binary.
                """;
    }
}
