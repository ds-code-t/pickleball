package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.PickleballLocalLayout;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchElementInspectTest {
    @TempDir
    Path tempDir;

    @Test
    void dumpAndFindUseTheDictionaryWithoutActing() throws Exception {
        String html = """
                <html><body>
                  <button id="save">Save</button>
                  <button hidden="hidden">Hide</button>
                  <a href="/login">Login</a>
                </body></html>
                """;
        List<WorkbenchElementInspect.Hit> dumped = WorkbenchElementInspect.dump(html);
        assertTrue(dumped.stream().anyMatch(hit -> hit.phrase().equals("the \"Save\" Button")), dumped.toString());
        assertTrue(dumped.stream().anyMatch(hit -> hit.phrase().equals("the \"Login\" Link")), dumped.toString());
        assertTrue(dumped.stream().allMatch(hit -> hit.xpath() != null && !hit.xpath().isBlank()), dumped.toString());
        assertTrue(dumped.stream().allMatch(hit -> hit.visibleCount() <= hit.rawMatchCount()));

        WorkbenchElementInspect.Hit found = WorkbenchElementInspect.find("Button", "Save", html);
        assertEqualsPhrase(found);
        assertFalse(found.xpath().isBlank(), found.xpath());
        assertTrue(found.rawMatchCount() >= found.visibleCount());

        Path file = tempDir.resolve("page.html");
        Files.writeString(file, html, StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save", "--html=" + file},
                new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, exit);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertOfflineFind(text, html, "Button", "Save");

        String html5 = """
                <!DOCTYPE html>
                <html><body>
                  <button id="save">Save</button>
                </body></html>
                """;
        Path html5File = tempDir.resolve("html5.html");
        Files.writeString(html5File, html5, StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream html5Out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream html5Err = new java.io.ByteArrayOutputStream();
        int html5Exit = WorkbenchAgentCommands.run(
                new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save", "--html=" + html5File},
                new java.io.PrintStream(html5Out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(html5Err, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, html5Exit, html5Err.toString(StandardCharsets.UTF_8));
        String html5Text = html5Out.toString(StandardCharsets.UTF_8);
        assertOfflineFind(html5Text, html5, "Button", "Save");

        java.io.ByteArrayOutputStream missingErr = new java.io.ByteArrayOutputStream();
        int missing = WorkbenchAgentCommands.run(
                new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save"},
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new java.io.PrintStream(missingErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(2, missing);
        assertTrue(missingErr.toString(StandardCharsets.UTF_8).contains("start a session first"));

        java.io.ByteArrayOutputStream pageErr = new java.io.ByteArrayOutputStream();
        int page = WorkbenchAgentCommands.run(
                new String[]{"inspect-page", tempDir.toString()},
                new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new java.io.PrintStream(pageErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(2, page);
        assertTrue(pageErr.toString(StandardCharsets.UTF_8).contains("start a session first"));
    }

    @Test
    void inspectPageWithNoCategoryPrintsEachHit() throws Exception {
        String html = """
                <!DOCTYPE html>
                <html><body>
                  <button id="save">Save</button>
                  <a href="/login">Login</a>
                </body></html>
                """;
        Path file = tempDir.resolve("page.html");
        Files.writeString(file, html, StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"inspect-page", tempDir.toString(), "--html=" + file},
                new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, exit, stderr.toString(StandardCharsets.UTF_8));
        String text = stdout.toString(StandardCharsets.UTF_8);
        List<PrintedHit> printed = parseHits(text);
        List<WorkbenchElementInspect.Hit> dumped = WorkbenchElementInspect.dump(html);
        assertEquals(dumped.size(), printed.size(), text);
        assertTrue(printed.size() >= 2, dumped.toString());
        for (int i = 0; i < dumped.size(); i++) {
            WorkbenchElementInspect.Hit hit = dumped.get(i);
            PrintedHit line = printed.get(i);
            assertEquals(hit.phrase(), line.phrase());
            assertFalse(line.xpath.isBlank(), line.xpath);
            assertEquals(xpathShape(hit.xpath()), xpathShape(line.xpath), line.xpath);
            assertEquals(0, hit.rawMatchCount(), hit.xpath());
            assertEquals(0, hit.visibleCount(), hit.xpath());
            assertEquals(0, line.raw);
            assertEquals(0, line.visible);
        }
        assertTrue(text.contains("phrase=the \"Save\" Button"), text);
        assertTrue(text.contains("phrase=the \"Login\" Link"), text);
        assertTrue(text.contains("xpath=//*[self::button or @role='button']"), text);
        assertTrue(text.contains("rawMatchCount=0"), text);
        assertTrue(text.contains("visibleCount=0"), text);
    }

    @Test
    void liveSessionInspectAndFindElementReturnTheWorkerHits() throws Exception {
        assertEquals(30L, WorkbenchAgentCommands.LIVE_QUERY_TIMEOUT_SECONDS);
        try (FakeQuerySession session = FakeQuerySession.start(tempDir)) {
            session.completeWith("{\"id\":\"cmd-1\",\"status\":\"SUCCESS\",\"result\":{\"status\":\"SUCCESS\",\"source\":\"live-session\",\"clicked\":false,\"hits\":[{\"phrase\":\"the \\\"Save\\\" Button\",\"xpath\":\"//button[@id='save']\",\"rawMatchCount\":2,\"visibleCount\":1}]}}");
            java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
            int found = WorkbenchAgentCommands.run(
                    new String[]{"find-element", tempDir.toString(), "--category=Button", "--text=Save"},
                    new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new java.io.PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            assertEquals(0, found, stderr.toString(StandardCharsets.UTF_8));
            String text = stdout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals("""
                    source=live-session
                    phrase=the "Save" Button
                    xpath=//button[@id='save']
                    rawMatchCount=2
                    visibleCount=1
                    """, text);
            assertTrue(session.lastPost().contains("\"op\":\"find-element\""), session.lastPost());
            assertTrue(session.lastPost().contains("\"category\":\"Button\""), session.lastPost());
            assertTrue(session.lastPost().contains("\"text\":\"Save\""), session.lastPost());

            session.completeWith("{\"id\":\"cmd-2\",\"status\":\"SUCCESS\",\"result\":{\"status\":\"SUCCESS\",\"source\":\"live-session\",\"hits\":[{\"phrase\":\"the \\\"Save\\\" Button\",\"xpath\":\"//button[@id='save']\",\"rawMatchCount\":1,\"visibleCount\":1},{\"phrase\":\"the \\\"Login\\\" Link\",\"xpath\":\"//a[@href='/login']\",\"rawMatchCount\":1,\"visibleCount\":1}]}}");
            java.io.ByteArrayOutputStream pageOut = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream pageErr = new java.io.ByteArrayOutputStream();
            int page = WorkbenchAgentCommands.run(
                    new String[]{"inspect-page", tempDir.toString()},
                    new java.io.PrintStream(pageOut, true, StandardCharsets.UTF_8),
                    new java.io.PrintStream(pageErr, true, StandardCharsets.UTF_8)
            );
            assertEquals(0, page, pageErr.toString(StandardCharsets.UTF_8));
            String pageText = pageOut.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals("""
                    source=live-session
                    phrase=the "Save" Button
                    xpath=//button[@id='save']
                    rawMatchCount=1
                    visibleCount=1
                    phrase=the "Login" Link
                    xpath=//a[@href='/login']
                    rawMatchCount=1
                    visibleCount=1
                    """, pageText);
            assertTrue(session.lastPost().contains("\"op\":\"inspect-page\""), session.lastPost());
            assertFalse(session.lastPost().contains("category"), session.lastPost());
        }
    }

    @Test
    void liveSessionPollTimeoutIsNotAnEmptySuccess() throws Exception {
        WorkbenchAgentCommands.setLiveQueryTimeoutMillisForTest(200L);
        try (FakeQuerySession session = FakeQuerySession.start(tempDir)) {
            session.stayQueued();
            java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
            int exit = WorkbenchAgentCommands.run(
                    new String[]{"inspect-page", tempDir.toString()},
                    new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new java.io.PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            assertEquals(WorkbenchAgentCommands.LIVE_QUERY_TIMEOUT_EXIT, exit);
            assertEquals(3, exit);
            String out = stdout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            String err = stderr.toString(StandardCharsets.UTF_8);
            assertEquals("status=TIMEOUT\n", out);
            assertFalse(out.contains("xpath="), out);
            assertFalse(out.contains("rawMatchCount="), out);
            assertTrue(err.contains("Workbench inspect-page TIMEOUT"), err);
            assertFalse(session.lastPost().contains("category"), session.lastPost());
        } finally {
            WorkbenchAgentCommands.resetLiveQueryTimeoutForTest();
        }
    }

    private static void assertOfflineFind(String text, String html, String category, String label) {
        List<PrintedHit> printed = parseHits(text);
        assertEquals(1, printed.size(), text);
        PrintedHit line = printed.get(0);
        WorkbenchElementInspect.Hit found = WorkbenchElementInspect.find(category, label, html);
        assertEquals("the \"" + label + "\" " + category, line.phrase);
        assertEquals(found.phrase(), line.phrase);
        assertFalse(line.xpath.isBlank(), line.xpath);
        assertTrue(line.xpath.startsWith("//*[self::button or @role='button']"), line.xpath);
        assertTrue(line.xpath.contains("= 'save'"), line.xpath);
        assertEquals(xpathShape(found.xpath()), xpathShape(line.xpath));
        assertEquals(0, found.rawMatchCount(), found.xpath());
        assertEquals(0, found.visibleCount(), found.xpath());
        assertEquals(0, line.raw);
        assertEquals(0, line.visible);
        assertTrue(text.contains("source=offline-html"), text);
        assertTrue(text.contains("rawMatchCount=0"), text);
        assertTrue(text.contains("visibleCount=0"), text);
    }

    /** XPathy assigns a fresh node id on every build. The expression around those ids is the dictionary. */
    private static String xpathShape(String xpath) {
        return xpath.replaceAll("\\d+>-?\\d+", "#");
    }

    private static List<PrintedHit> parseHits(String text) {
        String[] lines = text.split("\n", -1);
        assertTrue(lines.length > 0 && lines[0].equals("source=offline-html"), text);
        List<PrintedHit> hits = new java.util.ArrayList<>();
        int index = 1;
        while (index < lines.length && lines[index].isEmpty()) index++;
        while (index < lines.length && !lines[index].isEmpty()) {
            assertTrue(lines[index].startsWith("phrase="), text);
            String phrase = lines[index].substring("phrase=".length());
            index++;
            StringBuilder xpath = new StringBuilder();
            while (index < lines.length && !lines[index].startsWith("rawMatchCount=")) {
                if (!xpath.isEmpty()) xpath.append('\n');
                xpath.append(lines[index]);
                index++;
            }
            assertTrue(xpath.toString().startsWith("xpath="), text);
            String expression = xpath.substring("xpath=".length());
            assertTrue(index < lines.length && lines[index].startsWith("rawMatchCount="), text);
            int raw = Integer.parseInt(lines[index].substring("rawMatchCount=".length()));
            index++;
            assertTrue(index < lines.length && lines[index].startsWith("visibleCount="), text);
            int visible = Integer.parseInt(lines[index].substring("visibleCount=".length()));
            index++;
            hits.add(new PrintedHit(phrase, expression, raw, visible));
        }
        return hits;
    }

    private record PrintedHit(String phrase, String xpath, int raw, int visible) {
    }

    private static void assertEqualsPhrase(WorkbenchElementInspect.Hit found) {
        assertTrue(found.phrase().equals("the \"Save\" Button"), found.phrase());
    }

    private static final class FakeQuerySession implements AutoCloseable {
        private final HttpServer http;
        private final Path stateFile;
        private volatile String completed = "{\"id\":\"cmd-1\",\"status\":\"QUEUED\"}";
        private volatile boolean stayQueued;
        private volatile String lastPost = "";

        private FakeQuerySession(HttpServer http, Path stateFile) {
            this.http = http;
            this.stateFile = stateFile;
        }

        static FakeQuerySession start(Path project) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeQuerySession[] box = new FakeQuerySession[1];
            http.createContext("/health", exchange -> write(exchange, 200, "{\"status\":\"ok\"}"));
            http.createContext("/commands", exchange -> {
                FakeQuerySession current = box[0];
                String path = exchange.getRequestURI().getPath();
                if ("POST".equals(exchange.getRequestMethod()) && "/commands".equals(path)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (current != null) current.lastPost = body;
                    write(exchange, 200, "{\"ack\":true,\"id\":\"cmd-1\",\"status\":\"QUEUED\"}");
                    return;
                }
                if (current != null && current.stayQueued) {
                    write(exchange, 200, "{\"id\":\"cmd-1\",\"status\":\"QUEUED\"}");
                    return;
                }
                write(exchange, 200, current == null ? "{}" : current.completed);
            });
            http.start();
            Path stateFile = PickleballLocalLayout.cliSessionState(project);
            Files.createDirectories(stateFile.getParent());
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            String projectText = project.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
            Files.writeString(stateFile, """
                    {
                      "url": "%s",
                      "token": "test-token",
                      "pid": %d,
                      "project": "%s",
                      "mode": "cli-session"
                    }
                    """.formatted(url, ProcessHandle.current().pid(), projectText), StandardCharsets.UTF_8);
            FakeQuerySession session = new FakeQuerySession(http, stateFile);
            box[0] = session;
            return session;
        }

        void completeWith(String json) {
            completed = json.strip();
            stayQueued = false;
        }

        void stayQueued() {
            stayQueued = true;
        }

        String lastPost() {
            return lastPost;
        }

        @Override
        public void close() {
            http.stop(0);
            try {
                Files.deleteIfExists(stateFile);
            } catch (IOException ignored) {
            }
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
