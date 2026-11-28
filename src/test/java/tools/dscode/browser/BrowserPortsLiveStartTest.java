package tools.dscode.browser;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 19: {@code browser ports} printed none while the live start Chrome had a debug port. */
class BrowserPortsLiveStartTest {
    @TempDir
    Path project;

    private HttpServer host;

    @AfterEach
    void stop() {
        if (host != null) host.stop(0);
        DebugPortOwner.resetForTests();
    }

    @Test
    void theLiveStartBrowserIsListedFromTheProjectsHostFile() throws Exception {
        host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        host.createContext("/health", exchange -> {
            boolean authorized = "Bearer t0k".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (authorized ? "{\"status\":\"ok\",\"cdp\":\"ws://127.0.0.1:9333/devtools/browser/x\"}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        host.start();
        String url = "http://127.0.0.1:" + host.getAddress().getPort();
        LiveHostFile.write(project, new LiveHostFile.Host(url, "t0k", host.getAddress().getPort(), ProcessHandle.current().pid(),
                0L, "s1", false, "chrome-headless", true, "", true, 0L, 0L));

        assertEquals("ws://127.0.0.1:9333/devtools/browser/x", BrowserPorts.liveStartCdp(project));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = BrowserPorts.execute(new String[]{"browser", "ports"}, project,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("9333 live-start"), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noLiveStartStillPrintsNone() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, BrowserPorts.execute(new String[]{"browser", "ports"}, project,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream())));
        assertEquals("none", out.toString(StandardCharsets.UTF_8).trim());
    }
}
