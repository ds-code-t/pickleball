package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.ChatChannel;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code chat --follow} emits each new message through {@link VerbDispatch}
 * while the call is still open.
 */
class ChatFollowStreamTest {
    @TempDir
    Path project;

    @Test
    void followEmitsAMessageAsItArrives() throws Exception {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        String[] argv = new String[] {
                "chat", "--follow", "--timeout=2", "--channel=gap6",
                project.toAbsolutePath().toString()
        };
        AtomicInteger code = new AtomicInteger(-1);
        Thread follower = new Thread(() -> code.set(VerbDispatch.run("chat", VerbArgs.capture(argv), out, err)), "chat-follow");
        follower.start();
        try {
            Thread.sleep(200);
            ChatChannel.send(project, "session", "gap6", "gap6-live-alpha", "", "", "agent-1");
            long deadline = System.nanoTime() + 700_000_000L;
            while (System.nanoTime() < deadline && !outBytes.toString(StandardCharsets.UTF_8).contains("gap6-live-alpha")) {
                Thread.sleep(40);
            }
            String streamed = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(streamed.contains("gap6-live-alpha"), streamed + errBytes.toString(StandardCharsets.UTF_8));
            assertTrue(streamed.contains("\"op\":\"chat\"") || streamed.contains("\"op\": \"chat\""), streamed);
        } finally {
            follower.interrupt();
            follower.join(5_000);
        }
        assertTrue(code.get() == 0 || code.get() == 1, errBytes.toString(StandardCharsets.UTF_8));
    }
}
