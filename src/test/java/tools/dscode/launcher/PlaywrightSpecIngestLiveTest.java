package tools.dscode.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.store.StoreHydrator;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One local page, one spec, one passed step. A missing Chrome binary fails.
 * This test does not skip.
 */
class PlaywrightSpecIngestLiveTest {
    @TempDir
    Path project;

    private Process chrome;
    private HttpServer server;
    private String previousEndpoint;

    @AfterEach
    void stop() {
        if (previousEndpoint == null) System.clearProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        else System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, previousEndpoint);
        PlaywrightConnection.closeAll();
        if (chrome != null) PlaywrightCdpLiveTest.stopChrome(chrome);
        if (server != null) server.stop(0);
        ForeignStepHosts.install(null);
    }

    @Test
    void specOpensTheLocalPage() throws Exception {
        ForeignStepHosts.install(null);
        String binary = chromeBinary();
        if (binary == null) {
            fail("missing Chrome binary (looked for PKB_CHROME_BINARY, CHROME_BINARY, pickleball.chrome.binary, DOMENGINE_CHROME, google-chrome, google-chrome-stable, chromium, chromium-browser, chrome)");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] html = "<!doctype html><html><head><title>pw-live</title></head><body><h1>pw-live</h1></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Path spec = project.resolve("src/test/resources/features/live.spec.ts");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, """
                test.describe("live", () => {
                  test("opens the page", async ({ page }) => {
                    await page.goto("%s");
                    await expect(page).toHaveTitle("pw-live");
                  });
                });
                """.formatted(url), StandardCharsets.UTF_8);
        Path profile = project.resolve("chrome-profile");
        Files.createDirectories(profile);
        chrome = new ProcessBuilder(
                binary,
                "--headless=new",
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--no-first-run",
                "--no-default-browser-check",
                "--remote-debugging-port=0",
                "--remote-allow-origins=*",
                "--user-data-dir=" + profile,
                "about:blank")
                .redirectErrorStream(true)
                .redirectOutput(project.resolve("chrome.log").toFile())
                .start();
        String endpoint = endpoint(profile);
        previousEndpoint = System.getProperty(PlaywrightConnection.ENDPOINT_PROPERTY);
        System.setProperty(PlaywrightConnection.ENDPOINT_PROPERTY, endpoint);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        RunResult result = PickleballEngine.run(new FileScenarioSource(spec), EngineOptions.of().project(project));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertFalse(result.scenarios().isEmpty());
        List<StepResult> steps = result.scenarios().getFirst().steps();
        assertFalse(steps.isEmpty());
        for (StepResult step : steps) {
            assertEquals(Status.PASSED, step.status(), step.text() + " " + step.message());
        }
    }

    private String endpoint(Path profile) throws Exception {
        Path port = profile.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(port)) {
                List<String> lines = Files.readAllLines(port);
                if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                    return "http://127.0.0.1:" + lines.get(0).trim();
                }
            }
            if (!chrome.isAlive()) break;
            Thread.sleep(50);
        }
        String log = "";
        Path chromeLog = project.resolve("chrome.log");
        if (Files.isRegularFile(chromeLog)) log = Files.readString(chromeLog);
        throw new AssertionError("Chrome did not publish DevToolsActivePort\n" + log);
    }

    /**
     * Same order as {@code LiveChromeCdpTest.chromeBinary}. A missing binary is a failure.
     */
    private static String chromeBinary() {
        String pinned = firstExecutable(
                System.getenv("PKB_CHROME_BINARY"),
                System.getenv("CHROME_BINARY"),
                System.getProperty("pickleball.chrome.binary"));
        if (pinned != null) return pinned;
        String domengine = System.getenv("DOMENGINE_CHROME");
        if (domengine != null && !domengine.isBlank()) {
            String named = executableFile(domengine.trim());
            if (named != null) return named;
            return domengine.trim();
        }
        String onPath = firstOnPath(
                "google-chrome",
                "google-chrome-stable",
                "chromium",
                "chromium-browser",
                "chrome");
        if (onPath != null) return onPath;
        String installed = dev.domengine.session.BrowserSession.findChrome();
        if (installed != null) return installed;
        return executableFile("/opt/pw-browsers/chromium_headless_shell-1248/chrome-headless-shell-linux64/chrome-headless-shell");
    }

    private static String firstExecutable(String... candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            String found = executableFile(candidate);
            if (found != null) return found;
        }
        return null;
    }

    private static String firstOnPath(String... names) {
        String path = System.getenv("PATH");
        if (path == null || names == null) return null;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            for (String entry : path.split(java.io.File.pathSeparator)) {
                if (entry == null || entry.isBlank()) continue;
                String found = executableFile(Path.of(entry).resolve(name).toString());
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String executableFile(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        Path file = Path.of(candidate.trim());
        if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toString();
        return null;
    }
}
