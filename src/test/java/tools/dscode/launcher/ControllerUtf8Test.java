package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Non-ASCII survives the controller socket and a non-UTF-8 console stream. */
class ControllerUtf8Test {
    private static final String TEXT = "caf\u00e9 \u540d\u524d";

    @TempDir
    Path project;

    @Test
    void escapedReplyDecodesCafeAndName() {
        assertEquals("caf\u00e9", PkbMain.stringField("{\"out\":\"caf\\u00e9\"}", "out"));
        assertEquals("\u540d\u524d", PkbMain.stringField("{\"out\":\"\\u540d\\u524d\"}", "out"));
        String request = PkbMain.request("token", "", java.util.List.of("chat-send", "--text=" + TEXT));
        assertFalse(request.contains("\u00e9"), request);
        assertFalse(request.contains("\u540d"), request);
        assertTrue(request.contains("\\u00e9"), request);
        assertTrue(request.contains("\\u540d"), request);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void chatSendRoundTripsNonAsciiThroughTheController() throws Exception {
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        try {
            Capture started = capture(() -> PkbMain.run(new String[]{"controller-start", project.toString()}));
            assertEquals(0, started.code, started.err + started.out);
            assertTrue(started.out.contains("running=true"), started.out);

            Capture sent = capture(() -> PkbMain.run(new String[]{
                    "chat-send", project.toString(), "--text=" + TEXT, "--run-id=utf8"
            }));
            assertEquals(0, sent.code, sent.err + sent.out);
            assertTrue(sent.out.contains("caf\u00e9"), sent.out);
            assertTrue(sent.out.contains("\u540d\u524d"), sent.out);
            assertFalse(sent.out.contains("\uFFFD"), sent.out);

            Path chat = project.resolve(".pickleball").resolve("runs").resolve("utf8").resolve("agent").resolve("chat").resolve("run.jsonl");
            String stored = Files.readString(chat, StandardCharsets.UTF_8);
            assertTrue(stored.contains("caf\u00e9"), stored);
            assertTrue(stored.contains("\u540d\u524d"), stored);

            Capture history = capture(() -> PkbMain.run(new String[]{
                    "chat-history", project.toString(), "--run-id=utf8"
            }));
            assertEquals(0, history.code, history.err + history.out);
            assertTrue(history.out.contains("caf\u00e9"), history.out);
            assertTrue(history.out.contains("\u540d\u524d"), history.out);
        } finally {
            try {
                capture(() -> PkbMain.run(new String[]{"controller-stop", project.toString()}));
            } catch (Exception ignored) {
                // The controller is already gone.
            }
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    /** The console stream is ISO-8859-1. A correct reply still arrives as UTF-8 bytes. */
    private static Capture capture(Call call) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.ISO_8859_1));
        System.setErr(new PrintStream(err, true, StandardCharsets.ISO_8859_1));
        try {
            int code = call.run();
            return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    @FunctionalInterface
    private interface Call {
        int run() throws Exception;
    }

    private record Capture(int code, String out, String err) {
    }
}
