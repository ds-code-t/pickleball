package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HCR-50/51: {@code cdp} and every {@code pw} verb hung on the live start session (connectOverCDP, no timeout, no
 * error) and had to be killed after 45 s to 3 min. Each call is now bounded and fails with the stuck stage.
 */
class PwCdpTimeoutTest {
    @TempDir
    Path project;

    /** Accepts TCP and never answers, like a DevTools socket that never completes the handshake. */
    private static ServerSocket silent(List<Socket> held) throws Exception {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    held.add(server.accept());
                } catch (Exception closed) {
                    return;
                }
            }
        }, "silent-cdp");
        accept.setDaemon(true);
        accept.start();
        return server;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void cdpAndPwFailWithATimeoutInsteadOfHanging() throws Exception {
        List<Socket> held = new ArrayList<>();
        try (ServerSocket server = silent(held)) {
            String target = "--target=browser:cdp=ws://127.0.0.1:" + server.getLocalPort() + "/devtools/browser/hang";
            for (String[] call : new String[][]{
                    {"cdp", "--method=Runtime.evaluate", "--params={\"expression\":\"1\"}", target, "--timeout-ms=2000"},
                    {"pw", "page", "list", target, "--timeout-ms=2000"}}) {
                long started = System.nanoTime();
                PwCommandLiveTest.Capture capture = PwCommandLiveTest.agentIn(project, call);
                long ms = (System.nanoTime() - started) / 1_000_000;
                assertEquals(1, capture.code(), capture.out() + capture.err());
                assertTrue(capture.err().contains("timed out after 2000 ms at connect"), capture.err());
                assertTrue(capture.err().contains("fix:"), capture.err());
                assertTrue(ms < 12_000, call[0] + " took " + ms + " ms; the bound is 2000 ms");
            }
        } finally {
            for (Socket socket : held) socket.close();
        }
    }

    @Test
    void boundDefaultsTo30sAndFollowsTheStepWait() {
        assertEquals(30_000, PwCommand.deadlineMs(Map.of(), "page"));
        assertEquals(60_000, PwCommand.deadlineMs(Map.of(), "run"));
        assertEquals(5_000, PwCommand.deadlineMs(Map.of("timeout-ms", "5000"), "cdp"));
        assertEquals(60_000 + PwCommand.STEP_GRACE_MS, PwCommand.deadlineMs(Map.of("wait-ms", "60000"), "wait"));
    }
}
