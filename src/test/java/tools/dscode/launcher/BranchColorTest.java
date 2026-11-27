package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.launcher.PlaywrightNode;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One six-color table. {@code workbench-ui/color.mjs} and the Java reader of
 * {@code workbench-ui/colors.json} return the same color for {@code main} and
 * {@code fork-a}. The page fork control posts {@code store branch fork}.
 */
class BranchColorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAIN = "#0f7b4c";
    private static final String FORK_A = "#1f6feb";

    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreScope.clear();
        System.clearProperty("pkb_journal");
        SqlConnections.closeProject(project);
    }

    @Test
    void mainAndForkAUseTheDocumentedColors() throws Exception {
        List<String> colors = readColors();
        assertEquals(6, colors.size(), colors.toString());
        assertEquals(MAIN, colorFor("main", colors));
        assertEquals(FORK_A, colorFor("fork-a", colors));
        String script = Files.readString(Path.of("workbench-ui/color.mjs"), StandardCharsets.UTF_8);
        assertTrue(script.contains("colors.json"), script);
        Path node = PlaywrightNode.nodeBinary();
        assertTrue(node != null && Files.isRegularFile(node), "driver-bundle node");
        Process process = new ProcessBuilder(
                node.toString(),
                "--input-type=module",
                "-e",
                "import { colorFor } from './workbench-ui/color.mjs';"
                        + "import fs from 'node:fs';"
                        + "const colors = JSON.parse(fs.readFileSync('workbench-ui/colors.json','utf8'));"
                        + "process.stdout.write(colorFor('main', colors) + '\\n' + colorFor('fork-a', colors) + '\\n');"
        ).redirectErrorStream(true).start();
        String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), printed);
        assertEquals(MAIN + "\n" + FORK_A + "\n", printed.replace("\r\n", "\n"));
        String page = Files.readString(Path.of("workbench-ui/pages/index.html"), StandardCharsets.UTF_8);
        assertTrue(page.contains("/color.mjs"), page);
        assertTrue(page.contains("branch"), page);
        assertTrue(page.contains("fork"), page);
        assertTrue(page.contains("discard"), page);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void thePageForkControlPostsBranchForkWithTheSession() throws Exception {
        System.setProperty("pkb_journal", "off");
        String session = SpineSession.create(project, "agent-1");
        Result recorded = dispatch("store", "history", "record", "run-page",
                "--status=passed", "--message=kept", "--session=" + session);
        assertEquals(0, recorded.code, recorded.out + recorded.err);
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String home = exchange(handle, "GET", "/?t=" + handle.token(), "");
            assertTrue(home.contains("id=\"fork\""), home);
            assertTrue(home.contains("\"branch\", \"fork\""), home);
            assertTrue(home.contains("\"branch\", \"discard\""), home);
            String posted = post(handle, "store", List.of(
                    "branch", "fork", "--from-run=run-page", "--name=fork-a", "--json"));
            assertEquals(0, code(posted), posted);
            assertTrue(posted.contains("fork-a"), posted);
            assertTrue(Files.isRegularFile(StorePaths.sandboxDatabase(project, "fork-a")));
            String pageSession = SpineSession.current(project);
            assertTrue(pageSession != null && !pageSession.isBlank(), "renderer session");
        }
    }

    private static List<String> readColors() throws Exception {
        return JSON.readTree(Files.readString(Path.of("workbench-ui/colors.json"), StandardCharsets.UTF_8))
                .valueStream()
                .map(node -> node.asText())
                .toList();
    }

    /** The same fold as {@code workbench-ui/color.mjs}. */
    static String colorFor(String name, List<String> colors) {
        int hash = 0;
        String text = name == null ? "" : name;
        for (int index = 0; index < text.length(); index++) {
            hash = (hash + text.charAt(index)) % colors.size();
        }
        return colors.get(hash);
    }

    private Result dispatch(String... tokens) {
        String[] argv = new String[tokens.length + 1];
        System.arraycopy(tokens, 0, argv, 0, tokens.length);
        argv[tokens.length] = "--project=" + project.toAbsolutePath();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VerbDispatch.run(
                tokens[0],
                VerbArgs.capture(argv),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        StoreScope.clear();
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

    private static int code(String response) {
        int at = response.indexOf("\"code\"");
        if (at < 0) return -1;
        int end = response.indexOf(':', at) + 1;
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
