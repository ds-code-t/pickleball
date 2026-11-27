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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Object EXPLODE = new Object();
    private static final String ASSET_PREFIX = "META-INF/pickleball/workbench-ui/";

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
        Path assets = assets(project);
        if (assets == null || !Files.isRegularFile(assets.resolve("server.mjs"))) {
            throw new IOException("error: workbench renderer assets are missing\nfix: run the workbenchUi Gradle task");
        }
        String token = token();
        String session = tools.dscode.common.agentcontrol.SpineSession.ensure(project);
        HttpServer bridge = bridge(project, token, session);
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
        builder.environment().put("PKB_UI_PROJECT", project.toAbsolutePath().normalize().toString());
        builder.environment().put("NODE_PATH", assets.resolve("node_modules").toString());
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

    private static HttpServer bridge(Path project, String token, String session) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> bridge(exchange, project, token, session));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static void bridge(HttpExchange exchange, Path project, String token, String session) throws IOException {
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
            if (flags.get("session") == null || flags.get("session").isBlank()) {
                flags.put("session", session == null ? "" : session);
            }
            String agent = flags.get("agent");
            if (agent == null || agent.isBlank()) agent = flags.get("agent-id");
            if (rendererMutation(verb, positionals)) {
                try {
                    LeaseHold.require(project, flags.get("session"), "ui", agent);
                } catch (IllegalArgumentException failure) {
                    String message = failure.getMessage() == null ? "error: lease refused\nfix: lease release\n" : failure.getMessage();
                    if (!message.endsWith("\n")) message = message + "\n";
                    ObjectNode refused = JSON.createObjectNode();
                    refused.put("code", 2);
                    refused.put("out", "");
                    refused.put("err", message);
                    reply(exchange, 200, JSON.writeValueAsString(refused));
                    return;
                }
            }
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

    private static final Set<String> STORE_WRITES = Set.of(
            "put", "set", "use", "remove", "delete", "add", "enable", "choose",
            "hydrate", "extract", "merge", "promote", "create", "drop", "prune",
            "retain", "record", "undo", "redo", "rebuild", "compile",
            "scenario-add", "scenario-modify", "scenario-delete",
            "step-add", "step-modify", "step-delete");

    /** A renderer write. Reads stay open while another holder has the {@code ui} lease. */
    private static boolean rendererMutation(String verb, List<String> positionals) {
        if (verb == null) return false;
        if ("point".equals(verb) || "ask-answer".equals(verb)) return true;
        if ("engine".equals(verb) && positionals != null && positionals.size() > 1
                && "map".equals(positionals.get(0)) && "set".equals(positionals.get(1))) {
            return true;
        }
        if (!"store".equals(verb) || positionals == null || positionals.isEmpty()) return false;
        String family = positionals.get(0);
        if ("enable".equals(family) || "tag".equals(family) || "retain".equals(family)
                || "hydrate".equals(family) || "extract".equals(family)) {
            return true;
        }
        String action = positionals.size() > 1 ? positionals.get(1) : "";
        if ("branch".equals(family)) {
            return "fork".equals(action) || "discard".equals(action) || "promote".equals(action) || "link".equals(action);
        }
        return STORE_WRITES.contains(action);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }

    static Path assets(Path project) throws IOException {
        URL resource = WorkbenchUiServer.class.getClassLoader().getResource(ASSET_PREFIX + "server.mjs");
        if (resource != null && "file".equals(resource.getProtocol())) {
            try {
                return Path.of(resource.toURI()).getParent();
            } catch (URISyntaxException failure) {
                return null;
            }
        }
        if (resource != null && "jar".equals(resource.getProtocol())) {
            Path exploded = explode(project, resource);
            if (exploded != null) return exploded;
        }
        Path local = Path.of("workbench-ui");
        if (Files.isRegularFile(local.resolve("server.mjs"))) return local.toAbsolutePath().normalize();
        return null;
    }

    /**
     * Copies {@code META-INF/pickleball/workbench-ui/} out of the jar that contains it.
     * The directory name is the SHA-256 of that jar. A matching marker is reused.
     */
    private static Path explode(Path project, URL resource) throws IOException {
        Path jarPath = jarFile(resource);
        if (jarPath == null || !Files.isRegularFile(jarPath)) return null;
        String hash = sha256(jarPath);
        Path root = project.resolve(".pickleball").resolve("workbench-ui").resolve("assets");
        Files.createDirectories(root);
        Path dest = root.resolve(hash);
        Path marker = dest.resolve(".pkb-ui-assets");
        Path lock = root.resolve(hash + ".lock");
        synchronized (EXPLODE) {
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (FileLock ignored = channel.lock()) {
                    if (Files.isRegularFile(marker)
                            && hash.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())
                            && Files.isRegularFile(dest.resolve("server.mjs"))) {
                        return dest;
                    }
                    deleteTree(dest);
                    Files.createDirectories(dest);
                    try (JarFile jar = new JarFile(jarPath.toFile())) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName().replace('\\', '/');
                            if (!name.startsWith(ASSET_PREFIX)) continue;
                            String relative = name.substring(ASSET_PREFIX.length());
                            if (relative.isEmpty()) continue;
                            Path out = safe(dest, relative);
                            if (entry.isDirectory() || relative.endsWith("/")) {
                                Files.createDirectories(out);
                                continue;
                            }
                            if (out.getParent() != null) Files.createDirectories(out.getParent());
                            try (InputStream in = jar.getInputStream(entry)) {
                                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                    if (!Files.isRegularFile(dest.resolve("server.mjs"))) return null;
                    Files.writeString(marker, hash + "\n", StandardCharsets.UTF_8);
                    return dest;
                }
            }
        }
    }

    private static Path jarFile(URL resource) {
        String external = resource.toExternalForm();
        int bang = external.indexOf("!/");
        if (!external.startsWith("jar:") || bang < 0) return null;
        try {
            return Path.of(URI.create(external.substring("jar:".length(), bang)));
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Path safe(Path dest, String relative) throws IOException {
        Path out = dest.resolve(relative).normalize();
        if (!out.startsWith(dest.normalize())) {
            throw new IOException("error: workbench renderer asset path is outside the tree\nfix: run the workbenchUi Gradle task");
        }
        return out;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("error: SHA-256 is not available\nfix: use a Java 21 runtime", failure);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        byte[] raw = digest.digest();
        StringBuilder text = new StringBuilder(raw.length * 2);
        for (byte value : raw) text.append(String.format("%02x", value));
        return text.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path nodeBinary() {
        return PlaywrightNode.nodeBinary();
    }

    private static String token() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }
}
