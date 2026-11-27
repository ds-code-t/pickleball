package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ui page} goes through {@link VerbDispatch}. A generated page posts to
 * {@code /store} with its session token, and the bridge still refuses a verb
 * that is not in the catalog.
 */
class GeneratedPageAccessTest {
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    @TempDir
    Path project;

    @Test
    void uiPageRejectsAnExternalOrComputedUrl() throws Exception {
        String id = SpineSession.create(project, "agent-1");
        Capture external = page(id, "external", "<script>fetch(\"https://evil.example/store\")</script>");
        assertEquals(2, external.code, external.err);
        assertTrue(external.err.contains("outside the catalog"), external.err);
        Capture computed = page(id, "computed", "<script>fetch(base + \"/store\")</script>");
        assertEquals(2, computed.code, computed.err);
        assertTrue(computed.err.contains("outside the catalog"), computed.err);
        Capture socket = page(id, "socket", "<script>new WebSocket(\"ws://evil.example\")</script>");
        assertEquals(2, socket.code, socket.err);
        Capture relative = page(id, "relative", "<script>fetch(\"//evil.example/store\")</script>");
        assertEquals(2, relative.code, relative.err);
        Capture allowed = page(id, "store",
                "<script>fetch(\"/store\" + location.search, {method:\"POST\"})</script>");
        assertEquals(0, allowed.code, allowed.err);
        Path written = project.resolve(".pickleball").resolve("workbench-ui").resolve("generated")
                .resolve(id).resolve("store.html");
        assertTrue(Files.isRegularFile(written), "ui page did not write the catalog page");
    }

    @Test
    void generatedPageSendsASameOriginContentSecurityPolicy() throws Exception {
        String id = SpineSession.create(project, "agent-1");
        Capture allowed = page(id, "store", "<p>catalog</p>");
        assertEquals(0, allowed.code, allowed.err);
        String sessionToken = sessionToken(id);
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String home = exchange(handle.port(), request("GET", "/?t=" + handle.token(), "127.0.0.1", ""), 8000);
            assertTrue(status(home).startsWith("HTTP/1.1 200"), status(home));
            assertFalse(home.toLowerCase(java.util.Locale.ROOT).contains("content-security-policy"), preview(home));
            String generated = exchange(
                    handle.port(),
                    request("GET", "/ui/" + id + "/store?t=" + sessionToken, "127.0.0.1", ""),
                    8000);
            assertTrue(status(generated).startsWith("HTTP/1.1 200"), preview(generated));
            String lower = generated.toLowerCase(java.util.Locale.ROOT);
            assertTrue(lower.contains("content-security-policy"), preview(generated));
            int header = lower.indexOf("content-security-policy");
            int lineEnd = lower.indexOf("\n", header);
            String policy = lineEnd < 0 ? lower.substring(header) : lower.substring(header, lineEnd);
            assertTrue(policy.contains("connect-src 'self'"), policy);
            assertTrue(policy.contains("script-src 'self'"), policy);
            assertTrue(policy.contains("img-src 'self'"), policy);
            assertTrue(policy.contains("frame-src 'self'"), policy);
            assertFalse(policy.contains("http:"), policy);
            assertFalse(policy.contains("https:"), policy);
        }
    }

    @Test
    void sessionTokenPostsToStoreAndAnUnknownVerbStaysOut() throws Exception {
        String id = SpineSession.create(project, "agent-1");
        String sessionToken = sessionToken(id);
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String home = exchange(handle.port(), request("GET", "/?t=" + sessionToken, "127.0.0.1", ""), 5000);
            assertTrue(status(home).startsWith("HTTP/1.1 401"), status(home));

            String unknown = exchange(handle.port(), request(
                    "POST",
                    "/store?t=" + sessionToken,
                    "127.0.0.1",
                    "{\"verb\":\"no-such-verb\",\"args\":[]}"), 20000);
            assertTrue(status(unknown).startsWith("HTTP/1.1 200"), preview(unknown));
            assertTrue(unknown.contains("unknown verb"), preview(unknown));

            String shown = exchange(handle.port(), request(
                    "POST",
                    "/store?t=" + sessionToken,
                    "127.0.0.1",
                    "{\"verb\":\"spine\",\"args\":[\"session\",\"show\",\"--session=" + id + "\"]}"), 20000);
            assertTrue(status(shown).startsWith("HTTP/1.1 200"), preview(shown));
            assertTrue(shown.contains(id), preview(shown));

            String stranger = exchange(handle.port(), request(
                    "POST",
                    "/store?t=not-the-token",
                    "127.0.0.1",
                    "{\"verb\":\"spine\",\"args\":[\"session\",\"show\"]}"), 5000);
            assertTrue(status(stranger).startsWith("HTTP/1.1 401"), status(stranger));
        }
    }

    private Capture page(String id, String name, String html) throws Exception {
        Path file = project.resolve(name + ".html");
        Files.writeString(file, html, StandardCharsets.UTF_8);
        String[] args = new String[] {
                "ui", "page",
                "--session=" + id,
                "--name=" + name,
                "--html-file=" + file.toAbsolutePath(),
                project.toAbsolutePath().toString()
        };
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = VerbDispatch.run(
                "ui",
                VerbArgs.capture(args),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private String sessionToken(String id) throws Exception {
        Path file = project.resolve(".pickleball").resolve("workbench-ui").resolve("sessions").resolve(id + ".json");
        Matcher matcher = TOKEN.matcher(Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "session token");
        return matcher.group(1);
    }

    private static String request(String method, String path, String host, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append("\r\n");
        if (!body.isEmpty()) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        head.append(body);
        return head.toString();
    }

    private static String exchange(int port, String head, int timeoutMs) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(body);
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static String status(String response) {
        int end = response.indexOf('\n');
        return end < 0 ? response : response.substring(0, end).trim();
    }

    private static String preview(String response) {
        if (response == null) return "null";
        int end = Math.min(response.length(), 400);
        return "len=" + response.length() + " " + response.substring(0, end);
    }

    private record Capture(int code, String out, String err) {
    }
}
