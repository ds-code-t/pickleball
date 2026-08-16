package tools.dscode.studio;

import tools.dscode.studio.mcp.StudioServer;
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

        if (args.length > 1 && ("serve".equalsIgnoreCase(args[0]) || "status".equalsIgnoreCase(args[0]))
                && isHelp(args[1])) {
            printUsage(out);
            return 0;
        }

        if (args.length > 0 && "serve".equalsIgnoreCase(args[0])) {
            return serve(args, out, err);
        }

        int workspaceIndex = args.length > 0 && "status".equalsIgnoreCase(args[0]) ? 1 : 0;
        Path workspace = workspaceIndex < args.length ? Path.of(args[workspaceIndex]) : Path.of(".");
        return status(workspace, out, err);
    }

    private static int status(Path workspace, PrintStream out, PrintStream err) {
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

    private static int serve(String[] args, PrintStream out, PrintStream err) {
        Path workspace = Path.of(".");
        int port = 0;
        String token = null;
        boolean workspaceSet = false;

        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (argument.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(argument.substring("--port=".length()));
                } catch (NumberFormatException error) {
                    err.println("Invalid Studio MCP port: " + argument);
                    return 2;
                }
            } else if (argument.startsWith("--token=")) {
                token = argument.substring("--token=".length());
            } else if (!workspaceSet) {
                workspace = Path.of(argument);
                workspaceSet = true;
            } else {
                err.println("Unexpected Studio argument: " + argument);
                return 2;
            }
        }

        try {
            new WorkspaceService().open(workspace);
        } catch (IllegalArgumentException error) {
            err.println(error.getMessage());
            return 2;
        }
        return StudioServer.start(workspace, port, token, out, err);
    }

    private static boolean isHelp(String argument) {
        return "help".equalsIgnoreCase(argument)
                || "--help".equalsIgnoreCase(argument)
                || "-h".equalsIgnoreCase(argument);
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  pickleball studio status [workspace]");
        out.println("  pickleball studio serve [workspace] [--port=<port>] [--token=<token>]");
    }
}
