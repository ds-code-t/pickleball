package tools.dscode.workbench;

import java.io.PrintStream;

/** Entry point for the standalone Pickleball Workbench controller. */
public final class WorkbenchApplication {

    private WorkbenchApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
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

        err.println("Workbench command is not available in the foundation phase: " + args[0]);
        err.println("Run with --help for the currently available commands.");
        return 2;
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
        out.println("  java -jar pickleball-workbench-<version>.jar --help");
        out.println("  java -jar pickleball-workbench-<version>.jar --version");
        out.println();
        out.println("Runtime, synchronization, MCP, and UI commands are added in later implementation phases.");
    }
}
