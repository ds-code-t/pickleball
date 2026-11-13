package tools.dscode.common.agentsurface;

import java.nio.file.Path;
import java.util.Map;

/** Temporary CLI until the launcher routes popup-open, popup-status, and popup-close. */
public final class PopupCli {
    private PopupCli() {
    }

    public static void main(String[] args) {
        int code = execute(args);
        if (code != 0) System.exit(code);
    }

    public static int execute(String[] args) {
        if (args == null || args.length == 0) {
            System.err.println("usage: popup-open|popup-status|popup-close --project=<dir> --run-id=<id>");
            return 2;
        }
        String command = args[0];
        Path project = Path.of("").toAbsolutePath();
        String runId = "run";
        for (String arg : args) {
            if (arg.startsWith("--project=")) project = Path.of(arg.substring("--project=".length()));
            else if (arg.startsWith("--run-id=")) runId = arg.substring("--run-id=".length());
        }
        try {
            return switch (command) {
                case "popup-open" -> {
                    System.out.println(PopupStatus.json(PopupHost.open(project, runId)));
                    yield 0;
                }
                case "popup-status" -> {
                    System.out.println(PopupStatus.json(PopupStatus.read(project, runId)));
                    yield 0;
                }
                case "popup-close" -> {
                    PopupLedger.append(project, runId, "close", "", "", "", "", "", "");
                    System.out.println("{\"ok\":true,\"op\":\"popup-close\"}");
                    yield 0;
                }
                default -> {
                    System.err.println("unknown popup command: " + command);
                    yield 2;
                }
            };
        } catch (Exception failure) {
            System.err.println(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return 1;
        }
    }
}
