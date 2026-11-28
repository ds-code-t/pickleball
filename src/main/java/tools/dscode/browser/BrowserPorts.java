package tools.dscode.browser;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
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
        return execute(args, null, out, err);
    }

    /**
     * The live {@code start} browser of {@code project} runs in another JVM with its own debug port, so it is
     * listed from the project's live-host file as {@code <port> live-start}.
     */
    public static int execute(String[] args, Path project, PrintStream out, PrintStream err) {
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
        if (tokens.isEmpty() || tokens.stream().anyMatch(BrowserPorts::isHelp)) {
            stdout.print(help());
            return 0;
        }
        if (!"ports".equals(tokens.get(0))) {
            stderr.println("error: unknown browser command " + tokens.get(0));
            stderr.println("fix: browser ports");
            return 2;
        }
        List<String> ports = new ArrayList<>(DebugPortOwner.ports());
        String live = liveStartCdp(project);
        int livePort = portOf(live);
        if (livePort > 0 && ports.stream().noneMatch(line -> line.startsWith(livePort + " "))) ports.add(livePort + " live-start");
        if (ports.isEmpty()) stdout.println("none");
        else for (String line : ports) stdout.println(line);
        return 0;
    }

    /** The browser websocket of the project's live {@code start} session, from its {@code /health}; null when none answers. */
    public static String liveStartCdp(Path project) {
        if (project == null) return null;
        try {
            var host = tools.dscode.control.protocol.LiveHostFile.read(project).orElse(null);
            if (host == null || host.url() == null || host.url().isBlank()) return null;
            String base = host.url().endsWith("/") ? host.url().substring(0, host.url().length() - 1) : host.url();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + host.token())
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            String cdp = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).path("cdp").asText("");
            return cdp.startsWith("ws://") || cdp.startsWith("wss://") ? cdp : null;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    static int portOf(String ws) {
        if (ws == null || ws.isBlank()) return -1;
        try {
            return URI.create(ws).getPort();
        } catch (RuntimeException failure) {
            return -1;
        }
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
