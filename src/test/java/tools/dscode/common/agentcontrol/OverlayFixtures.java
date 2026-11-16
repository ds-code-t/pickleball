package tools.dscode.common.agentcontrol;

import com.sun.net.httpserver.HttpServer;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared live-test helpers for the overlay polish work: a loopback server for the
 * {@code src/test/resources/overlay} fixture pages and a headless Chrome. A missing
 * Chrome binary returns null so the caller can skip, the same rule as {@code LiveChromeCdpTest}.
 */
public final class OverlayFixtures implements AutoCloseable {
    private final HttpServer server;

    private OverlayFixtures(HttpServer server) {
        this.server = server;
    }

    public static OverlayFixtures start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isBlank()) name = "basic.html";
            byte[] body;
            try (InputStream in = OverlayFixtures.class.getResourceAsStream("/overlay/" + name)) {
                body = in == null ? null : in.readAllBytes();
            }
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String type = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : "text/html";
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return new OverlayFixtures(server);
    }

    public String url(String page) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + page;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Headless Chrome at 1280x800, or null when this machine has no Chrome binary. */
    public static ChromeDriver chrome() {
        String binary = chromeBinary();
        if (binary == null) return null;
        ChromeOptions options = new ChromeOptions();
        options.setBinary(binary);
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
                "--remote-allow-origins=*", "--window-size=1280,800", "--force-device-scale-factor=1");
        return new ChromeDriver(options);
    }

    public static String chromeBinary() {
        for (String pinned : new String[]{System.getenv("PKB_CHROME_BINARY"), System.getenv("CHROME_BINARY"),
                System.getProperty("pickleball.chrome.binary"), System.getenv("DOMENGINE_CHROME")}) {
            String found = executable(pinned);
            if (found != null) return found;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String name : new String[]{"google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome"}) {
                for (String entry : path.split(java.io.File.pathSeparator)) {
                    if (entry == null || entry.isBlank()) continue;
                    String found = executable(Path.of(entry).resolve(name).toString());
                    if (found != null) return found;
                }
            }
        }
        for (String known : new String[]{
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}) {
            String found = executable(known);
            if (found != null) return found;
        }
        return null;
    }

    private static String executable(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        Path file = Path.of(candidate.trim());
        return Files.isRegularFile(file) && Files.isExecutable(file) ? file.toString() : null;
    }
}
