package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupBridgeTest {
    @TempDir
    Path project;

    @Test
    void tokenHostCspSsePollChatAndInput() throws Exception {
        try (PopupBridge bridge = PopupBridge.start(project, "r1", "abc")) {
            HttpResult denied = request(bridge, "/health", "GET", null, "nope", "127.0.0.1:" + bridge.port());
            assertEquals(403, denied.status);
            HttpResult host = raw(bridge.port(), "GET /health HTTP/1.1\r\nHost: localhost:" + bridge.port()
                    + "\r\nX-Pkb-Popup-Token: abc\r\nConnection: close\r\n\r\n");
            assertEquals(403, host.status);
            HttpResult ok = request(bridge, "/health", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, ok.status);
            assertTrue(ok.csp.contains("default-src 'none'"));
            assertEquals("nosniff", ok.nosniff);
            HttpResult shell = request(bridge, "/", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, shell.status);
            assertTrue(shell.body.contains("assets/agent-surface-popup.js?t=abc"), shell.body);
            HttpResult css = request(bridge, "/assets/surface/html.css", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, css.status);
            assertEquals(tools.dscode.browser.SurfaceAssets.css("html"), css.body);
            HttpResult script = request(bridge, "/assets/surface/overlay.js", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, script.status);
            assertEquals(tools.dscode.browser.SurfaceAssets.js("overlay"), script.body);
            PopupLedger.append(project, "r1", "append", "instructions", "h1", "", "<p>one</p>", "", "");
            PopupLedger.append(project, "r1", "append", "instructions", "h2", "", "<p>two</p>", "", "");
            HttpResult events = request(bridge, "/events", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertTrue(events.body.contains("\"seq\":1"), events.body);
            assertTrue(events.body.contains("\"seq\":2"), events.body);
            HttpResult resumed = request(bridge, "/events", "GET", null, "abc", "127.0.0.1:" + bridge.port(), "1");
            assertTrue(resumed.body.contains("\"seq\":2"), resumed.body);
            assertTrue(!resumed.body.contains("\"seq\":1") || resumed.body.indexOf("\"seq\":2") < resumed.body.indexOf("\"seq\":1"));
            HttpResult poll = request(bridge, "/poll?after=0", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, poll.status);
            HttpResult pollAgain = request(bridge, "/poll?after=1", "GET", null, "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, pollAgain.status);
            HttpResult chat = request(bridge, "/chat", "POST", "{\"text\":\"hello\"}", "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, chat.status);
            String chatFile = Files.readString(project.resolve(".pickleball/runs/r1/agent/chat/run.jsonl"));
            assertTrue(chatFile.contains("hello"), chatFile);
            HttpResult input = request(bridge, "/input", "POST", "{\"type\":\"click\",\"id\":\"go\",\"value\":\"\"}", "abc", "127.0.0.1:" + bridge.port());
            assertEquals(200, input.status);
            String eventsFile = Files.readString(project.resolve(".pickleball/runs/r1/agent/overlay-events.jsonl"));
            assertTrue(eventsFile.contains("click"), eventsFile);
        }
    }

    private HttpResult raw(int port, String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] bytes = socket.getInputStream().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            int status = 0;
            int space = text.indexOf(' ');
            if (space > 0 && text.startsWith("HTTP/")) {
                int end = text.indexOf(' ', space + 1);
                if (end > space) status = Integer.parseInt(text.substring(space + 1, end));
            }
            return new HttpResult(status, text, header(text, "Content-Security-Policy"), header(text, "X-Content-Type-Options"));
        }
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return "";
    }

    private HttpResult request(PopupBridge bridge, String path, String method, String body, String token, String host) throws Exception {
        return request(bridge, path, method, body, token, host, null);
    }

    private HttpResult request(PopupBridge bridge, String path, String method, String body, String token, String host, String lastEvent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + bridge.port() + path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("X-Pkb-Popup-Token", token);
        connection.setRequestProperty("Host", host);
        if (lastEvent != null) connection.setRequestProperty("Last-Event-ID", lastEvent);
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(path.startsWith("/events") ? 4000 : 2000);
        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        byte[] bytes;
        try (var input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            bytes = input == null ? new byte[0] : input.readAllBytes();
        }
        return new HttpResult(
                status,
                new String(bytes, StandardCharsets.UTF_8),
                String.valueOf(connection.getHeaderField("Content-Security-Policy")),
                String.valueOf(connection.getHeaderField("X-Content-Type-Options"))
        );
    }

    private record HttpResult(int status, String body, String csp, String nosniff) {
    }
}
