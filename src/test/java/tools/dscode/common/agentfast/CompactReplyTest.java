package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.launcher.WorkbenchAgentCommands;
import tools.dscode.launcher.WorkbenchSessionCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactReplyTest {
    @TempDir
    Path temp;

    @Test
    void oversizedInspectPageAndStepRepliesStayUnderTwoKib() throws Exception {
        Path html = temp.resolve("page.html");
        Files.writeString(html, "<html><body>" + "x".repeat(4000) + "</body></html>");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"inspect-page", temp.toString(), "--html=" + html},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String printed = stdout.toString(StandardCharsets.UTF_8);
        System.out.println("MEASURE compactBytes=" + printed.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(0, code, printed + stderr);
        assertTrue(printed.getBytes(StandardCharsets.UTF_8).length <= CompactReply.LIMIT, printed);
        JsonNode compact = new ObjectMapper().readTree(printed);
        assertTrue(compact.path("compact").asBoolean(false), printed);
        Path full = Path.of(compact.path("full").asText());
        String original = Files.readString(full);
        assertTrue(original.contains("x".repeat(4000)), "full file lost the page");
        assertTrue(Files.size(full) > CompactReply.LIMIT);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/commands", exchange -> {
            byte[] response = "{\"id\":\"step-big\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/commands/step-big", exchange -> {
            byte[] response = ("{\"status\":\"SUCCESS\",\"result\":{\"text\":\"" + "s".repeat(3000) + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path session = PickleballLocalLayout.cliSessionState(temp);
            Files.createDirectories(session.getParent());
            Files.writeString(session,
                    "{\"url\":\"http://127.0.0.1:" + port + "\",\"token\":\"t\",\"pid\":"
                            + ProcessHandle.current().pid() + ",\"mode\":\"cli-session\"}\n");
            Path stepFile = temp.resolve("step.txt");
            Files.writeString(stepFile, "Then the \"Save\" button is visible\n");
            ByteArrayOutputStream stepOut = new ByteArrayOutputStream();
            int step = WorkbenchSessionCommands.run(
                    new String[]{"execute-step", "--text-file=" + stepFile, temp.toString()},
                    new PrintStream(stepOut, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
            );
            String stepText = stepOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, step, stepText);
            String compactLine = "";
            for (String line : stepText.split("\n")) {
                if (line.contains("\"compact\":true")) compactLine = line;
            }
            assertFalse(compactLine.isBlank(), stepText);
            assertTrue(compactLine.getBytes(StandardCharsets.UTF_8).length <= CompactReply.LIMIT, compactLine);
            JsonNode stepNode = new ObjectMapper().readTree(compactLine);
            assertTrue(Files.readString(Path.of(stepNode.path("full").asText())).contains("s".repeat(3000)));
        } finally {
            server.stop(0);
        }

        String inspect = CompactReply.shrinkText(temp, "inspect", "phrase=Save\n");
        assertEquals("phrase=Save\n", inspect);

        Map<String, Object> shot = ScreenshotReply.write(temp.resolve("shot.png"), new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        assertEquals(4, shot.get("byteSize"));
        assertEquals("image/png", shot.get("mime"));
        assertFalse(shot.containsKey("base64"));
        assertEquals(4, Files.size(temp.resolve("shot.png")));
    }
}
