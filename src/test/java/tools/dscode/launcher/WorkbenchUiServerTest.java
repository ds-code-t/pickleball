package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchUiServerTest {
    @Test
    void homeListsScenariosBeforeRawSqlAndRejectsABadToken(@TempDir Path project) throws Exception {
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String home = exchange(handle.port(), request("GET", "/?t=" + handle.token(), "127.0.0.1"));
            String status = status(home);
            assertTrue(status.startsWith("HTTP/1.1 200"), status);
            int scenarios = home.indexOf("Scenarios");
            int sql = home.indexOf("Raw SQL");
            assertTrue(scenarios >= 0 && sql > scenarios, home);

            String withPort = exchange(handle.port(), request("GET", "/?t=" + handle.token(), "127.0.0.1:" + handle.port()));
            assertTrue(status(withPort).startsWith("HTTP/1.1 200"), status(withPort));

            String denied = exchange(handle.port(), request("GET", "/?t=not-the-token", "127.0.0.1"));
            assertTrue(status(denied).startsWith("HTTP/1.1 401"), status(denied));

            String foreign = exchange(handle.port(), request("GET", "/?t=" + handle.token(), "example.com"));
            assertTrue(status(foreign).startsWith("HTTP/1.1 403"), status(foreign));

            String code = exchange(handle.port(), request("GET", "/scenarios?t=" + handle.token(), "127.0.0.1"));
            assertTrue(status(code).startsWith("HTTP/1.1 200"), status(code));
            assertTrue(code.contains("monaco"), code);
        }
    }

    private static String request(String method, String path, String host) {
        return method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
    }

    private static String exchange(int port, String head) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(body);
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static String status(String response) {
        int end = response.indexOf('\n');
        return end < 0 ? response : response.substring(0, end).trim();
    }
}
