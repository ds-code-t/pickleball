package tools.dscode.browser;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code browser ports} lists debug-port owners. This class does not own {@code spine}.
 */
public final class BrowserPorts {
    private BrowserPorts() {
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "browser".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: browser CLI expected the browser command");
            stderr.println("fix: browser help");
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
        if (!"ports".equals(tokens.get(0))) {
            stderr.println("error: unknown browser command " + tokens.get(0));
            stderr.println("fix: browser ports");
            return 2;
        }
        List<String> ports = DebugPortOwner.ports();
        if (ports.isEmpty()) stdout.println("none");
        else for (String line : ports) stdout.println(line);
        return 0;
    }

    public static String help() {
        return """
                Usage: browser ports
                Prints one debug port and its owner per line. spine sample prints the heap gate.
                """;
    }

    private static boolean isHelp(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }
}
