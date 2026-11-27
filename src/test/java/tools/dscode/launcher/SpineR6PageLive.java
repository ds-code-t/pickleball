package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.ChromeProcess;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real browser on the Node renderer page. Clicks go through Chrome, and the
 * page posts to the {@link WorkbenchUiServer} bridge.
 */
final class SpineR6PageLive implements AutoCloseable {
    static final String TITLE = "document.getElementById('title').textContent";
    static final String ACCENT = "getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CDP = Duration.ofSeconds(15);

    final Path project;
    final String session;
    private final WorkbenchUiServer.Handle renderer;
    private final BrowserSession browser;
    private final CdpConnection cdp;

    private SpineR6PageLive(Path project, String session, WorkbenchUiServer.Handle renderer, BrowserSession browser) {
        this.project = project;
        this.session = session;
        this.renderer = renderer;
        this.browser = browser;
        this.cdp = browser.connection();
    }

    static SpineR6PageLive open(Path project) throws Exception {
        String session = SpineSession.current(project);
        if (session == null || session.isBlank()) session = SpineSession.create(project, "agent-1");
        WorkbenchUiServer.Handle renderer = WorkbenchUiServer.start(project);
        String binary = BrowserSession.findChrome();
        if (binary == null) binary = BrowserSession.findEdge();
        assertNotNull(binary, "locally installed Chrome or Edge is required");
        BrowserSession browser = null;
        try {
            browser = BrowserSession.launch(
                    "r6-fixback",
                    "http://127.0.0.1:" + renderer.port() + "/?t=" + renderer.token(),
                    false,
                    true,
                    ChromeProcess.newProfile(),
                    List.of(),
                    List.of("--window-size=1600,1200"),
                    binary);
            return new SpineR6PageLive(project, session, renderer, browser);
        } catch (RuntimeException failure) {
            if (browser != null) browser.close();
            renderer.close();
            throw failure;
        }
    }

    void settleOnMain() throws Exception {
        String main = BranchColorTest.colorFor("main", colors());
        awaitEquals(TITLE, "main");
        awaitEquals(ACCENT, main);
        Thread.sleep(500);
        String title = eval(TITLE);
        if (!"main".equals(title)) {
            throw new AssertionError("title moved to " + title + " before the click\n" + dump());
        }
    }

    void setValue(String id, String value) throws Exception {
        String seen = eval("(function(){var node=document.getElementById(" + JSON.writeValueAsString(id) + ");"
                + "if(!node) return 'missing';"
                + "node.focus(); node.value=" + JSON.writeValueAsString(value) + ";"
                + "node.dispatchEvent(new Event('input',{bubbles:true}));"
                + "node.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return node.value;})()");
        if (!value.equals(seen)) throw new AssertionError("set " + id + " saw " + seen + "\n" + dump());
    }

    void click(String id) throws Exception {
        String point = eval("(function(){var node=document.getElementById(" + JSON.writeValueAsString(id) + ");"
                + "if(!node) return 'missing';"
                + "node.scrollIntoView({block:'center',inline:'center'});"
                + "var r=node.getBoundingClientRect();"
                + "if(r.width<1||r.height<1) return 'hidden';"
                + "return (r.x+r.width/2)+','+(r.y+r.height/2);})()");
        int comma = point.indexOf(',');
        if (comma < 0) throw new AssertionError("click " + id + " -> " + point + "\n" + dump());
        double x = Double.parseDouble(point.substring(0, comma));
        double y = Double.parseDouble(point.substring(comma + 1));
        cdp.call("Input.dispatchMouseEvent", mouse("mousePressed", x, y), CDP);
        cdp.call("Input.dispatchMouseEvent", mouse("mouseReleased", x, y), CDP);
    }

    void leave() {
        ObjectNode params = Jsons.object();
        params.put("url", "about:blank");
        cdp.call("Page.navigate", params, CDP);
    }

    void awaitEquals(String expression, String expected) throws Exception {
        String last = "";
        for (int attempt = 0; attempt < 80; attempt++) {
            last = eval(expression);
            if (expected.equals(last)) return;
            Thread.sleep(250);
        }
        throw new AssertionError(expression + " stayed " + last + " wanted " + expected + "\n" + dump());
    }

    void awaitContains(String expression, String needle) throws Exception {
        String last = "";
        for (int attempt = 0; attempt < 80; attempt++) {
            last = eval(expression);
            if (last.contains(needle)) return;
            Thread.sleep(250);
        }
        throw new AssertionError(expression + " stayed " + last + " wanted " + needle + "\n" + dump());
    }

    void awaitGone(Path database) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (!Files.isRegularFile(database)) return;
            Thread.sleep(200);
        }
        throw new AssertionError("empty fork sandbox still present after the page left: " + database);
    }

    void awaitStays(Path database) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (!Files.isRegularFile(database)) {
                throw new AssertionError("fork sandbox was removed: " + database);
            }
            Thread.sleep(200);
        }
        assertTrue(Files.isRegularFile(database), "fork sandbox stays");
    }

    String parentHead() throws Exception {
        Path database = StorePaths.mainDatabase(project);
        if (!Files.isRegularFile(database)) return "missing";
        try (Connection connection = SqliteOpen.open(database)) {
            String through = "0";
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM schema_meta WHERE `key` = 'delta_cursor'")) {
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getString(1) != null) through = rows.getString(1).trim();
                }
            }
            String count = "0";
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM head_rows")) {
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) count = Integer.toString(rows.getInt(1));
                }
            }
            return through + "/" + count;
        }
    }

    void awaitParentHeadChanges(String before) throws Exception {
        String last = before;
        for (int attempt = 0; attempt < 80; attempt++) {
            last = parentHead();
            if (!before.equals(last)) return;
            Thread.sleep(250);
        }
        throw new AssertionError("parent head stayed " + last + "\n" + dump());
    }

    String eval(String expression) throws Exception {
        ObjectNode params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode value = cdp.call("Runtime.evaluate", params, CDP);
        if (value.has("exceptionDetails")) throw new AssertionError(value.toString());
        JsonNode result = value.path("result").path("value");
        if (result.isMissingNode() || result.isNull()) return "";
        return result.isTextual() ? result.asText("") : result.toString();
    }

    String dump() {
        try {
            return eval("["
                    + "document.getElementById('title').textContent,"
                    + "document.getElementById('map-body').textContent,"
                    + "document.getElementById('scenario-body').textContent,"
                    + "document.getElementById('tree-body').innerText"
                    + "].join('\\n---\\n')");
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    @Override
    public void close() {
        if (browser != null) browser.close();
        if (renderer != null) renderer.close();
    }

    static void finishedRun(Path project, String session, String runId) throws Exception {
        Capture recorded = verb(project, session, "store", "history", "record", runId,
                "--status=passed", "--scenario=Ada", "--message=kept");
        assertEquals(0, recorded.code, recorded.out + recorded.err);
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE runs SET commit_sha = ?, head_id = ? WHERE run_id = ?")) {
            statement.setString(1, "abc123def");
            statement.setString(2, "head-9");
            statement.setString(3, runId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    static String accent(String name) throws Exception {
        return BranchColorTest.colorFor(name, colors());
    }

    static List<String> treeBranches(Path project, String session, String file) throws Exception {
        Capture tree = verb(project, session, "store", "branch", "tree", "--file=" + file, "--json");
        assertEquals(0, tree.code, tree.out + tree.err);
        JsonNode root = JSON.readTree(tree.out);
        JsonNode rows = root.path("rows");
        JsonNode items = root.path("items");
        if (items.isArray() && !items.isEmpty()) rows = items.get(0).path("rows");
        List<String> branches = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) branches.add(row.path("branch").asText(""));
        }
        return branches;
    }

    static Capture verb(Path project, String session, String... args) {
        StoreScope.clear();
        String[] argv = new String[args.length + 2];
        System.arraycopy(args, 0, argv, 0, args.length);
        argv[args.length] = "--session=" + session;
        argv[args.length + 1] = "--project=" + project.toAbsolutePath();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try {
            code = VerbDispatch.run(
                    args[0],
                    VerbArgs.capture(argv),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
        } finally {
            StoreScope.clear();
        }
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<String> colors() throws Exception {
        List<String> colors = new ArrayList<>();
        for (JsonNode node : JSON.readTree(Files.readString(Path.of("workbench-ui/colors.json"), StandardCharsets.UTF_8))) {
            colors.add(node.asText());
        }
        return colors;
    }

    private static ObjectNode mouse(String type, double x, double y) {
        ObjectNode node = Jsons.object();
        node.put("type", type);
        node.put("x", x);
        node.put("y", y);
        node.put("button", "left");
        node.put("clickCount", 1);
        return node;
    }

    record Capture(int code, String out, String err) {
    }
}
