package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.bidi.HasBiDi;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.AgentDriverHooks;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.control.protocol.LiveBridgeFile;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bidi script} and {@code bidi intercept} from a separate CLI process
 * reach the JVM that holds the Selenium BiDi driver. No browser is downloaded.
 */
class BidiCliSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INTERCEPT_ID = Pattern.compile("intercept\\s+(\\S+)");

    @TempDir
    Path project;

    private HttpServer server;
    private ExecutorService executor;
    private WebDriver driver;

    @AfterEach
    void stop() {
        System.clearProperty("pkb_selenium_bidi");
        SeleniumBidiCommands.resetForTests();
        if (driver != null) {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // The browser is already gone.
            }
            driver = null;
        }
        LiveBridgeFile.delete(project);
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void cliScriptAndInterceptReachTheHolderJvm() throws Exception {
        String chrome = BrowserSession.findChrome();
        String edge = chrome == null ? BrowserSession.findEdge() : null;
        String binary = chrome != null ? chrome : edge;
        if (binary == null) {
            throw new AssertionError("local Chrome or Edge is required for bidi CLI session");
        }
        String browser = chrome != null ? "chrome" : "edge";
        System.setProperty("pkb_selenium_bidi", "on");
        CopyOnWriteArrayList<String> proofs = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/match", exchange -> text(exchange, 201, "original-match"));
        server.createContext("/other", exchange -> text(exchange, 200, "original-other"));
        server.createContext("/proof", exchange -> {
            proofs.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            text(exchange, 200, "ok");
        });
        server.createContext("/v1/agent/command", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer token-test".equals(auth)) {
                bytes(exchange, 401, "{\"exit\":1,\"body\":{\"error\":\"unauthorized\"}}");
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            Map<String, String> args = new LinkedHashMap<>();
            request.path("args").fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue().asText("")));
            List<String> extra = new ArrayList<>();
            request.path("extraArgs").forEach(node -> extra.add(node.asText("")));
            Path root = Path.of(request.path("project").asText(project.toString()));
            AgentControl.Result result = AgentControl.execute(
                    root,
                    request.path("runId").asText(""),
                    request.path("op").asText(""),
                    args,
                    extra,
                    request.path("agentId").asText("")
            );
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("exit", result.exit());
            payload.put("body", result.body());
            bytes(exchange, 200, JSON.writeValueAsString(payload));
        });
        server.start();
        int port = server.getAddress().getPort();
        String origin = "http://127.0.0.1:" + port;

        com.fasterxml.jackson.databind.node.ObjectNode config = JSON.createObjectNode();
        config.put("browser", browser);
        config.put("project", project.toAbsolutePath().normalize().toString());
        com.fasterxml.jackson.databind.node.ObjectNode options = config.putObject("driver").putObject("options");
        options.put("binary", binary);
        com.fasterxml.jackson.databind.node.ArrayNode launch = options.putArray("args");
        launch.add("--headless=new");
        launch.add("--disable-gpu");
        launch.add("--no-first-run");
        launch.add("--user-data-dir=" + project.resolve("chrome-profile").toAbsolutePath().normalize());
        driver = DriverConstruction.createLocalDriver(config);
        if (!(driver instanceof HasBiDi)) {
            throw new AssertionError("local " + browser + " did not open a Selenium BiDi session");
        }
        driver.get(origin + "/other");
        AgentDriverHooks.ready(driver);
        LiveBridgeFile.write(project, new LiveBridgeFile.Bridge(
                "127.0.0.1", port, "token-test", ProcessHandle.current().pid(), "bidi-cli"
        ));

        Capture ran = cli("bidi", "script", "--source=" + post(origin, "script-ran"));
        assertEquals(0, ran.code, ran.out + ran.err);
        assertEquals(List.of("script-ran"), List.copyOf(proofs), ran.out + ran.err);

        Capture added = cli("bidi", "intercept", "--url=" + origin + "/match", "--body=replaced");
        assertEquals(0, added.code, added.out + added.err);
        Matcher id = INTERCEPT_ID.matcher(added.out);
        assertTrue(id.find(), added.out + added.err);
        Capture filtered = cli("bidi", "script", "--source=" + report(origin));
        assertEquals(0, filtered.code, filtered.out + filtered.err);
        assertEquals("201 replaced|200 original-other", proofs.get(1), proofs.toString());

        Capture removed = cli("bidi", "intercept", "off", "--id=" + id.group(1));
        assertEquals(0, removed.code, removed.out + removed.err);
        Capture restored = cli("bidi", "script", "--source=" + report(origin));
        assertEquals(0, restored.code, restored.out + restored.err);
        assertEquals("201 original-match|200 original-other", proofs.get(2), proofs.toString());
    }

    /** {@code script.evaluate} runs an expression. The call is an immediately invoked function. */
    private static String post(String origin, String body) {
        return "(() => { var xhr = new XMLHttpRequest(); xhr.open('POST', '" + origin
                + "/proof', false); xhr.send('" + body + "'); return xhr.status; })()";
    }

    private static String report(String origin) {
        return "(() => { function grab(path) { var xhr = new XMLHttpRequest(); xhr.open('GET', '"
                + origin + "' + path, false); xhr.send(); return xhr.status + ' ' + xhr.responseText; }"
                + " var xhr = new XMLHttpRequest(); xhr.open('POST', '" + origin
                + "/proof', false); xhr.send(grab('/match') + '|' + grab('/other')); return xhr.responseText; })()";
    }

    private Capture cli(String... args) throws Exception {
        Path argFile = Files.createTempFile(project, "bidi-cli-", ".args");
        List<String> lines = new ArrayList<>();
        lines.add("-Dfile.encoding=UTF-8");
        lines.add("-Dpkb_selenium_bidi=on");
        lines.add("-Dpkb_store=false");
        lines.add("-cp");
        lines.add(quote(System.getProperty("java.class.path")));
        lines.add("tools.dscode.launcher.PickleballWorkbenchLauncher");
        Files.write(argFile, lines, StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("@" + argFile.toAbsolutePath().normalize());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outThread = new Thread(() -> copy(process.getInputStream(), out), "bidi-cli-out");
        Thread errThread = new Thread(() -> copy(process.getErrorStream(), err), "bidi-cli-err");
        outThread.start();
        errThread.start();
        int code = process.waitFor();
        outThread.join();
        errThread.join();
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void copy(java.io.InputStream input, ByteArrayOutputStream sink) {
        try {
            input.transferTo(sink);
        } catch (java.io.IOException failure) {
            sink.writeBytes(failure.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void text(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        bytes(exchange, status, body);
    }

    private static void bytes(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\\' || ch == '"') quoted.append('\\');
            quoted.append(ch);
        }
        return quoted.append('"').toString();
    }

    private record Capture(int code, String out, String err) {
    }
}
