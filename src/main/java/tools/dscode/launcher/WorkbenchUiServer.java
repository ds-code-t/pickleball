package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.common.agentsurface.PopupBrowserLocator;
import tools.dscode.common.agentsurface.PopupLauncher;
import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.engine.EngineLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the workbench renderer. {@link #start} does not open a browser.
 * {@link #open} is the {@code ui} verb and uses the popup browser locator.
 */
public final class WorkbenchUiServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    private WorkbenchUiServer() {
    }

    public record Handle(int port, String token, Process node, HttpServer bridge, Path project) implements AutoCloseable {
        @Override
        public void close() {
            if (bridge != null) bridge.stop(0);
            if (node == null) return;
            node.destroyForcibly();
            try {
                node.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static Handle start(Path project) throws IOException {
        Path assets = assets();
        if (assets == null || !Files.isRegularFile(assets.resolve("server.mjs"))) {
            throw new IOException("error: workbench renderer assets are missing\nfix: run the workbenchUi Gradle task");
        }
        String token = token();
        HttpServer bridge = bridge(project, token);
        int bridgePort = bridge.getAddress().getPort();
        Path node = nodeBinary();
        if (node == null) {
            bridge.stop(0);
            throw new IOException("error: Node from the Playwright driver-bundle was not found\n"
                    + "fix: the driver-bundle Node is required; a browser is not downloaded");
        }
        Path directory = project.resolve(".pickleball").resolve("workbench-ui");
        Files.createDirectories(directory);
        Path hostFile = directory.resolve("host.json");
        Files.deleteIfExists(hostFile);
        ProcessBuilder builder = new ProcessBuilder(node.toString(), assets.resolve("server.mjs").toString());
        builder.directory(assets.toFile());
        builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        builder.environment().put("PKB_UI_TOKEN", token);
        builder.environment().put("PKB_UI_ROOT", assets.toString());
        builder.environment().put("PKB_UI_HOST", hostFile.toString());
        builder.environment().put("PKB_UI_BRIDGE_PORT", Integer.toString(bridgePort));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(hostFile)) {
                String text = Files.readString(hostFile, StandardCharsets.UTF_8);
                Matcher port = PORT.matcher(text);
                Matcher found = TOKEN.matcher(text);
                if (port.find() && found.find()) {
                    int listen = Integer.parseInt(port.group(1));
                    EngineLog.log(project, "host", "ui", "listen port=" + listen);
                    return new Handle(listen, found.group(1), process, bridge, project);
                }
            }
            if (!process.isAlive()) break;
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                bridge.stop(0);
                process.destroyForcibly();
                throw new IOException("error: workbench renderer start was interrupted\nfix: pickleball ui");
            }
        }
        bridge.stop(0);
        process.destroyForcibly();
        throw new IOException("error: workbench renderer did not listen\nfix: pickleball ui");
    }

    /**
     * Bare {@code ui}. No Chromium writes {@code state=closed} and returns 0.
     * A located browser opens beside the page. This method is not used by the server test.
     */
    public static int open(Path project, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        Handle handle;
        try {
            handle = start(project);
        } catch (IOException failure) {
            stderr.println(failure.getMessage());
            return 1;
        }
        PopupBrowserLocator.Hit hit = PopupBrowserLocator.locate();
        if (hit.path().isEmpty()) {
            stderr.println("warn: no-chromium");
            writeState(handle, "closed");
            handle.close();
            return 0;
        }
        String url = "http://127.0.0.1:" + handle.port() + "/?t=" + handle.token();
        Path profile = handle.project().resolve(".pickleball").resolve("workbench-ui").resolve("profile");
        try {
            List<String> command = PopupLauncher.argv(hit.path().get(), url, profile, 1100, 800, 960, 40, false);
            PopupLauncher.start(command, handle.project().resolve(".pickleball").resolve("workbench-ui").resolve("browser.log"));
        } catch (IOException failure) {
            stderr.println("error: " + failure.getMessage());
            stderr.println("fix: pickleball ui");
            handle.close();
            return 1;
        }
        stdout.println(url);
        Runtime.getRuntime().addShutdownHook(new Thread(handle::close, "pkb-ui-close"));
        try {
            return handle.node().waitFor();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            handle.close();
            return 1;
        }
    }

    private static void writeState(Handle handle, String state) {
        Path file = handle.project().resolve(".pickleball").resolve("workbench-ui").resolve("host.json");
        String body = "{\"port\":" + handle.port() + ",\"token\":\"" + handle.token() + "\",\"state\":\"" + state + "\"}\n";
        try {
            Files.writeString(file, body, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The CLI still returns. The window is closed.
        }
    }

    private static HttpServer bridge(Path project, String token) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> bridge(exchange, project, token));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void bridge(HttpExchange exchange, Path project, String token) throws IOException {
        try {
            String remote = exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!"127.0.0.1".equals(remote)) {
                reply(exchange, 403, "{\"code\":2,\"out\":\"\",\"err\":\"forbidden\\n\"}");
                return;
            }
            String header = exchange.getRequestHeaders().getFirst("X-Pkb-Ui-Token");
            if (header == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 401, "{\"code\":2,\"out\":\"\",\"err\":\"unauthorized\\n\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode node = JSON.readTree(body.isBlank() ? "{}" : body);
            String verb = node.path("verb").asText("");
            if (!VerbCatalog.covers(verb)) {
                reply(exchange, 404, "{\"code\":2,\"out\":\"\",\"err\":\"error: unknown verb\\nfix: pickleball help\\n\"}");
                return;
            }
            List<String> positionals = new ArrayList<>();
            Map<String, String> flags = new LinkedHashMap<>();
            boolean namedProject = false;
            JsonNode listed = node.path("args");
            if (listed.isArray()) {
                for (JsonNode item : listed) {
                    String arg = item.asText("");
                    if (arg.startsWith("--project=") || "--project".equals(arg)) namedProject = true;
                    if (arg.startsWith("--") && arg.length() > 2) {
                        String flagBody = arg.substring(2);
                        int equals = flagBody.indexOf('=');
                        if (equals < 0) flags.put(flagBody, "true");
                        else flags.put(flagBody.substring(0, equals), flagBody.substring(equals + 1));
                    } else if (!arg.isBlank()) {
                        positionals.add(arg);
                    }
                }
            }
            flags.put(VerbArgs.ARGS, String.join("\n", positionals));
            if (!namedProject) flags.put("project", project.toAbsolutePath().normalize().toString());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int code = VerbDispatch.run(
                    verb,
                    flags,
                    new PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            ObjectNode response = JSON.createObjectNode();
            response.put("code", code);
            response.put("out", stdout.toString(StandardCharsets.UTF_8));
            response.put("err", stderr.toString(StandardCharsets.UTF_8));
            reply(exchange, 200, JSON.writeValueAsString(response));
        } catch (RuntimeException failure) {
            reply(exchange, 500, "{\"code\":1,\"out\":\"\",\"err\":\"error: ui bridge failed\\nfix: pickleball ui\\n\"}");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    static Path assets() {
        URL resource = WorkbenchUiServer.class.getClassLoader().getResource("META-INF/pickleball/workbench-ui/server.mjs");
        if (resource != null && "file".equals(resource.getProtocol())) {
            try {
                return Path.of(resource.toURI()).getParent();
            } catch (URISyntaxException failure) {
                return null;
            }
        }
        Path local = Path.of("workbench-ui");
        if (Files.isRegularFile(local.resolve("server.mjs"))) return local.toAbsolutePath().normalize();
        return null;
    }

    private static Path nodeBinary() {
        Optional<Path> driver = PlaywrightNode.locate();
        if (driver.isEmpty()) return null;
        List<String> command = PlaywrightNode.command(driver.get(), List.of());
        if (command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) return null;
        Path node = Path.of(command.get(0));
        return Files.isRegularFile(node) ? node : null;
    }

    private static String token() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }
}
