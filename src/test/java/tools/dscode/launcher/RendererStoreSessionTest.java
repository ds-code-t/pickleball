package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderer {@code /store} path reaches {@link tools.dscode.store.StoreCli}
 * with a spine session. A {@code ui} lease held by someone else refuses a write.
 */
class RendererStoreSessionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GLUE = "src/test/java/sample/Steps.java";
    private static final String ORIGINAL = """
            package sample;
            import io.cucumber.java.en.Given;
            public class Steps {
              @Given("ready")
              public void ready() {}
            }
            """;

    @TempDir
    Path project;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void pagesLoadAndGlueRoundTripsWithASession() throws Exception {
        ByteArrayOutputStream helpOut = new ByteArrayOutputStream();
        ByteArrayOutputStream helpErr = new ByteArrayOutputStream();
        int help = VerbDispatch.run(
                "engine",
                VerbArgs.capture(new String[] {"engine", "help", project.toAbsolutePath().toString()}),
                new PrintStream(helpOut, true, StandardCharsets.UTF_8),
                new PrintStream(helpErr, true, StandardCharsets.UTF_8));
        String helpText = helpOut.toString(StandardCharsets.UTF_8) + helpErr.toString(StandardCharsets.UTF_8);
        assertEquals(0, help, helpText);
        assertTrue(helpText.contains("Usage: engine help"), helpText);
        assertFalse(helpText.contains("error: missing session"), helpText);
        ByteArrayOutputStream versionOut = new ByteArrayOutputStream();
        ByteArrayOutputStream versionErr = new ByteArrayOutputStream();
        int version = VerbDispatch.run(
                "domengine",
                VerbArgs.capture(new String[] {"domengine", "version", "--project=" + project.toAbsolutePath()}),
                new PrintStream(versionOut, true, StandardCharsets.UTF_8),
                new PrintStream(versionErr, true, StandardCharsets.UTF_8));
        String versionText = versionOut.toString(StandardCharsets.UTF_8) + versionErr.toString(StandardCharsets.UTF_8);
        assertEquals(0, version, versionText);
        assertTrue(versionText.contains("version"), versionText);
        assertFalse(versionText.contains("error: missing session"), versionText);
        ByteArrayOutputStream statusErr = new ByteArrayOutputStream();
        int status = VerbDispatch.run(
                "store",
                VerbArgs.capture(new String[] {"store", "status", project.toAbsolutePath().toString()}),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(statusErr, true, StandardCharsets.UTF_8));
        assertEquals(2, status, statusErr.toString(StandardCharsets.UTF_8));
        assertTrue(statusErr.toString(StandardCharsets.UTF_8).contains("error: missing session"), statusErr.toString(StandardCharsets.UTF_8));

        Files.writeString(project.resolve("store.properties"), "enabled=false\n", StandardCharsets.UTF_8);
        Path glue = project.resolve(GLUE);
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, ORIGINAL, StandardCharsets.UTF_8);
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            for (String path : List.of("/", "/scenarios", "/globals", "/data", "/sql")) {
                String page = exchange(handle, "GET", path + "?t=" + handle.token(), "");
                assertTrue(status(page).startsWith("HTTP/1.1 200"), path + " " + status(page));
            }
            String home = exchange(handle, "GET", "/?t=" + handle.token(), "");
            assertTrue(home.contains("id=\"monaco\""), "renderer page");
            String shown = post(handle, "store", List.of("glue", "show", "--path=" + GLUE, "--json"));
            assertTrue(code(shown) == 0, shown);
            assertTrue(shown.contains("ready"), shown);
            String saved = ORIGINAL.replace("ready", "saved");
            String put = post(handle, "store", List.of("glue", "put", "--path=" + GLUE, "--text=" + saved));
            assertTrue(code(put) == 0, put);
            String written = Files.readString(glue, StandardCharsets.UTF_8);
            assertTrue(written.contains("@Given(\"saved\")"), written);
            assertFalse(written.contains("@Given(\"ready\")"), written);
            String session = SpineSession.current(project);
            assertTrue(session != null && !session.isBlank(), "renderer session");
            assertTrue(Files.isRegularFile(project.resolve(".pickleball").resolve("workbench-ui").resolve("sessions").resolve(session + ".json")));
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void anotherHolderRefusesRendererWrites() throws Exception {
        Files.writeString(project.resolve("store.properties"), "enabled=false\n", StandardCharsets.UTF_8);
        Path glue = project.resolve(GLUE);
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, ORIGINAL, StandardCharsets.UTF_8);
        String id = SpineSession.create(project, "agent-1");
        Result taken = dispatch("lease", "take", "--target=ui", "--session=" + id, "--agent=holder-b");
        assertEquals(0, taken.code, taken.err + taken.out);
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String refused = post(handle, "store", List.of("glue", "put", "--path=" + GLUE, "--text=" + ORIGINAL.replace("ready", "blocked")));
            assertTrue(refused.contains("error:"), refused);
            assertTrue(refused.contains("fix:"), refused);
            assertTrue(code(refused) != 0, refused);
            assertTrue(Files.readString(glue).contains("@Given(\"ready\")"), Files.readString(glue));
            String read = post(handle, "store", List.of("glue", "show", "--path=" + GLUE, "--json"));
            assertTrue(code(read) == 0, read);
            assertTrue(read.contains("ready"), read);
            String refusedVar = post(handle, "store", List.of("var", "set", "pkb_marker", "blocked-value"));
            assertTrue(refusedVar.contains("error:"), refusedVar);
            assertTrue(refusedVar.contains("fix:"), refusedVar);
            assertTrue(code(refusedVar) != 0, refusedVar);
            String allowed = post(handle, "store", List.of(
                    "glue", "put", "--path=" + GLUE, "--agent=holder-b", "--text=" + ORIGINAL.replace("ready", "kept")));
            assertTrue(code(allowed) == 0, allowed);
            assertTrue(Files.readString(glue).contains("@Given(\"kept\")"), Files.readString(glue));
            String allowedVar = post(handle, "store", List.of("var", "set", "pkb_marker", "kept-value", "--agent=holder-b"));
            assertTrue(code(allowedVar) == 0, allowedVar);
            String properties = Files.readString(project.resolve("src/test/resources/pickleball.properties"));
            assertTrue(properties.contains("kept-value"), properties);
            assertFalse(properties.contains("blocked-value"), properties);
        }
    }

    private Result dispatch(String... tokens) {
        String[] argv = new String[tokens.length + 1];
        System.arraycopy(tokens, 0, argv, 0, tokens.length);
        argv[tokens.length] = project.toAbsolutePath().toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerbDispatch.run(
                tokens[0],
                VerbArgs.capture(argv),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private String post(WorkbenchUiServer.Handle handle, String verb, List<String> args) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("verb", verb);
        ArrayNode listed = body.putArray("args");
        for (String arg : args) listed.add(arg);
        return exchange(handle, "POST", "/store?t=" + handle.token(), JSON.writeValueAsString(body));
    }

    private static String exchange(WorkbenchUiServer.Handle handle, String method, String path, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1\r\n");
        if (!body.isEmpty()) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        head.append(body);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", handle.port()), 20000);
            socket.setSoTimeout(20000);
            socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            return response.toString(StandardCharsets.UTF_8);
        }
    }

    private static String status(String response) {
        int end = response.indexOf('\n');
        return end < 0 ? response : response.substring(0, end).trim();
    }

    private static int code(String response) {
        int at = response.indexOf("\"code\"");
        if (at < 0) return -1;
        int colon = response.indexOf(':', at);
        int end = colon + 1;
        while (end < response.length() && !Character.isDigit(response.charAt(end)) && response.charAt(end) != '-') end++;
        int start = end;
        if (end < response.length() && response.charAt(end) == '-') end++;
        while (end < response.length() && Character.isDigit(response.charAt(end))) end++;
        if (start == end) return -1;
        return Integer.parseInt(response.substring(start, end));
    }

    private record Result(int code, String out, String err) {
    }
}
