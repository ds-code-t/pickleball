package tools.dscode.studio.launcher;

import java.util.Arrays;

public final class PickleballMain {
    private PickleballMain() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            return 0;
        }

        if ("studio".equalsIgnoreCase(args[0])) {
            return StudioLauncher.launch(Arrays.copyOfRange(args, 1, args.length));
        }

        System.err.println("Unknown Pickleball command: " + args[0]);
        printUsage();
        return 2;
    }

    private static boolean isHelp(String argument) {
        return "help".equalsIgnoreCase(argument)
                || "--help".equalsIgnoreCase(argument)
                || "-h".equalsIgnoreCase(argument);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar pickleball-<version>.jar studio ui [workspace]");
        System.out.println("  java -jar pickleball-<version>.jar studio status [workspace]");
        System.out.println("  java -jar pickleball-<version>.jar studio serve [workspace] [--port=<port>] [--token=<token>]");
        System.out.println("  java -jar pickleball-<version>.jar studio exec <workspace> <command> [args...]");
        System.out.println("  java -jar pickleball-<version>.jar studio maven <workspace> <goal-or-option> [args...]");
        System.out.println("  java -jar pickleball-<version>.jar studio gradle <workspace> <task-or-option> [args...]");
        System.out.println("  java -jar pickleball-<version>.jar studio gradle-model [workspace]");
        System.out.println("  java -jar pickleball-<version>.jar studio outline <workspace> <source-file>");
    }
}
