package dev.domengine.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.LiveTree;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One named browser session. An attached session detaches on close and does not kill the browser.
 * A launched session owns the Chrome process.
 */
public final class BrowserSession implements AutoCloseable {
    public final String id;
    public final String cdp;
    public final boolean launched;
    public final boolean process;
    public final long pid;
    public final Path profile;
    public final List<String> allow;
    private final Process chrome;
    private final CdpConnection connection;
    private volatile boolean closed;
    private volatile boolean killedProcess;

    private BrowserSession(
            String id,
            String cdp,
            boolean launched,
            boolean process,
            long pid,
            Path profile,
            List<String> allow,
            Process chrome,
            CdpConnection connection
    ) {
        this.id = id;
        this.cdp = cdp;
        this.launched = launched;
        this.process = process;
        this.pid = pid;
        this.profile = profile;
        this.allow = allow;
        this.chrome = chrome;
        this.connection = connection;
    }

    public static BrowserSession attach(String id, String cdp, boolean process, List<String> allow) {
        String browser = normalize(cdp);
        Endpoint endpoint = discover(browser);
        CdpConnection connection = CdpConnection.connect(URI.create(endpoint.webSocket), Duration.ofSeconds(10));
        EngineLog.info("session", "attached " + id + " " + browser);
        return new BrowserSession(id, endpoint.http, false, process, 0, null, allow, null, connection);
    }

    public static BrowserSession reopen(String id, String cdp, boolean launched, boolean process, long pid, String profile) {
        Endpoint endpoint = discover(normalize(cdp));
        CdpConnection connection = CdpConnection.connect(URI.create(endpoint.webSocket), Duration.ofSeconds(10));
        EngineLog.info("session", "reopened " + id + " " + endpoint.http);
        Path dir = profile == null || profile.isBlank() ? null : Path.of(profile);
        return new BrowserSession(id, endpoint.http, launched, process, pid, dir, List.of(), null, connection);
    }

    public static BrowserSession launch(String id, String url, boolean headed, boolean process, Path profile, List<String> allow) {
        Path dir = profile;
        try {
            if (dir == null) dir = Files.createTempDirectory("domengine-profile-");
            else Files.createDirectories(dir);
        } catch (IOException failure) {
            throw new CdpException("profile: " + failure.getMessage());
        }
        String chrome = findChrome();
        if (chrome == null) {
            throw new CdpException("chrome not found on PATH (google-chrome, chromium, or chromium-browser)");
        }
        List<String> command = new ArrayList<>();
        command.add(chrome);
        command.add("--remote-debugging-port=0");
        command.add("--user-data-dir=" + dir.toAbsolutePath());
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("--no-sandbox");
        command.add("--disable-dev-shm-usage");
        command.add("--disable-gpu");
        if (!headed) command.add("--headless=new");
        command.add("about:blank");
        Process processHandle;
        try {
            processHandle = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException failure) {
            throw new CdpException("chrome launch: " + failure.getMessage());
        }
        int port = awaitPort(dir, processHandle);
        String http = "http://127.0.0.1:" + port;
        Endpoint endpoint = discover(http);
        CdpConnection connection = CdpConnection.connect(URI.create(endpoint.webSocket), Duration.ofSeconds(10));
        if (url != null && !url.isBlank()) {
            ObjectNode params = Jsons.object();
            params.put("url", url);
            connection.call("Page.navigate", params, Duration.ofSeconds(15));
        }
        EngineLog.info("session", "launched " + id + " pid=" + processHandle.pid());
        return new BrowserSession(id, endpoint.http, true, process, processHandle.pid(), dir, allow, processHandle, connection);
    }

    public CdpConnection connection() {
        if (closed || connection == null) throw new CdpException("session " + id + " is closed");
        return connection;
    }

    public LiveTree tree() {
        return LiveTree.read(connection());
    }

    public boolean isClosed() {
        return closed;
    }

    public ObjectNode record() {
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("id", id);
        node.put("cdp", cdp == null ? "" : cdp);
        node.put("launched", launched);
        node.put("process", process);
        if (pid > 0) node.put("pid", pid);
        if (profile != null) node.put("profile", profile.toString());
        var allowlist = node.putArray("allowlist");
        for (String host : allow) allowlist.add(host);
        return node;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (connection != null) connection.close();
        boolean killed = false;
        if (launched && chrome != null) {
            chrome.destroy();
            try {
                killed = chrome.waitFor(3, TimeUnit.SECONDS) || !chrome.isAlive();
                if (chrome.isAlive()) {
                    chrome.destroyForcibly();
                    killed = true;
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                chrome.destroyForcibly();
                killed = true;
            }
        } else if (launched && pid > 0) {
            try {
                ProcessHandle.of(pid).ifPresent(handle -> {
                    handle.destroy();
                    try {
                        handle.onExit().orTimeout(3, TimeUnit.SECONDS).join();
                    } catch (Exception ignored) {
                        handle.destroyForcibly();
                    }
                    if (handle.isAlive()) handle.destroyForcibly();
                });
                killed = ProcessHandle.of(pid).isEmpty() || ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
            } catch (Exception ignored) {
                killed = false;
            }
        }
        killedProcess = killed;
        EngineLog.info("session", "closed " + id + " killed=" + killed);
    }

    public boolean killedBrowser() {
        return killedProcess;
    }

    static String normalize(String cdp) {
        if (cdp == null || cdp.isBlank()) throw new CdpException("cdp endpoint is missing");
        String value = cdp.trim();
        if (value.startsWith("ws://") || value.startsWith("wss://")) return value;
        if (!value.contains("://")) value = "http://" + value;
        return value;
    }

    public static String findChrome() {
        String env = System.getenv("DOMENGINE_CHROME");
        if (env != null && !env.isBlank()) return env;
        for (String name : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome")) {
            String path = onPath(name);
            if (path != null) return path;
        }
        return null;
    }

    private static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    private static int awaitPort(Path profile, Process process) {
        Path file = profile.resolve("DevToolsActivePort");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new CdpException("chrome exited before DevToolsActivePort");
            if (Files.isRegularFile(file)) {
                try {
                    String line = Files.readString(file).lines().findFirst().orElse("");
                    return Integer.parseInt(line.trim());
                } catch (IOException | NumberFormatException failure) {
                    throw new CdpException("DevToolsActivePort: " + failure.getMessage());
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CdpException("chrome launch interrupted");
            }
        }
        throw new CdpException("timed out waiting for DevToolsActivePort");
    }

    static Endpoint discover(String cdp) {
        if (cdp.startsWith("ws://") || cdp.startsWith("wss://")) {
            String http = "http" + cdp.substring(2);
            int slash = http.indexOf("/devtools");
            if (slash > 0) http = http.substring(0, slash);
            return new Endpoint(http, cdp);
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = stripPath(cdp);
        try {
            HttpRequest version = HttpRequest.newBuilder(URI.create(base + "/json/version")).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> versionBody = client.send(version, HttpResponse.BodyHandlers.ofString());
            JsonNode versionJson = Jsons.MAPPER.readTree(versionBody.body());
            HttpRequest list = HttpRequest.newBuilder(URI.create(base + "/json/list")).GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> listBody = client.send(list, HttpResponse.BodyHandlers.ofString());
            JsonNode pages = Jsons.MAPPER.readTree(listBody.body());
            String pageSocket = null;
            if (pages.isArray()) {
                for (JsonNode page : pages) {
                    if ("page".equals(page.path("type").asText())) {
                        pageSocket = page.path("webSocketDebuggerUrl").asText(null);
                        if (pageSocket != null && !pageSocket.isBlank()) break;
                    }
                }
            }
            if (pageSocket == null || pageSocket.isBlank()) {
                pageSocket = versionJson.path("webSocketDebuggerUrl").asText("");
            }
            if (pageSocket.isBlank()) throw new CdpException("cdp endpoint has no page target");
            EngineLog.info("cdp", "discovered " + base);
            return new Endpoint(base, pageSocket);
        } catch (CdpException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CdpException("cdp discover interrupted");
        } catch (Exception failure) {
            throw new CdpException("cdp discover failed: " + failure.getMessage());
        }
    }

    private static String stripPath(String cdp) {
        String value = cdp;
        int scheme = value.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        int slash = value.indexOf('/', start);
        if (slash > 0) value = value.substring(0, slash);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.toLowerCase(Locale.ROOT).startsWith("http") ? value : "http://" + value;
    }

    public record Endpoint(String http, String webSocket) {
    }
}
