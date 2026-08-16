package tools.dscode.studio;

import tools.dscode.studio.workspace.WorkspaceInfo;
import tools.dscode.studio.workspace.WorkspaceService;

import java.io.PrintStream;
import java.nio.file.Path;

public final class StudioApplication {
    private StudioApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && isHelp(args[0])) {
            printUsage(out);
            return 0;
        }

        int workspaceIndex = args.length > 0 && "status".equalsIgnoreCase(args[0]) ? 1 : 0;
        Path workspace = workspaceIndex < args.length ? Path.of(args[workspaceIndex]) : Path.of(".");

        try {
            WorkspaceInfo info = new WorkspaceService().open(workspace);
            out.println("Pickleball Studio foundation ready");
            out.println("Workspace: " + info.root());
            out.println("Maven: " + info.mavenProject());
            out.println("Gradle: " + info.gradleProject());
            out.println("Git: " + info.gitRepository());
            return 0;
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return 2;
        }
    }

    private static boolean isHelp(String argument) {
        return "help".equalsIgnoreCase(argument)
                || "--help".equalsIgnoreCase(argument)
                || "-h".equalsIgnoreCase(argument);
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: pickleball studio [status] [workspace]");
    }
}
