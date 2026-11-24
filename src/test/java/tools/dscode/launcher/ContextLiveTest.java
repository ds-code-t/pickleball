package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.browser.PlaywrightCdpLiveTest;
import tools.dscode.browser.PlaywrightConnection;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E7 live (fresh Chrome per test): contexts, tabs, history, print, storage, storage-state, downloads, uploads, file dialogs, drop, dialogs. */
class ContextLiveTest {
    static final String PAGE = "<!doctype html><title>ctx</title><input type=file id=f multiple><div id=zone style='width:200px;height:80px'>zone</div>"
            + "<a id=dl href='/dl'>download</a><script>"
            + "window.names='';document.getElementById('f').addEventListener('change',e=>{window.names=[...e.target.files].map(f=>f.name).join(',')});"
            + "const z=document.getElementById('zone');z.addEventListener('dragover',e=>e.preventDefault());"
            + "z.addEventListener('drop',e=>{e.preventDefault();window.dropped=[...e.dataTransfer.files].map(f=>f.name).join(',')+'|'+e.dataTransfer.getData('text/plain')});"
            + "window.dialogs=(ms)=>{setTimeout(()=>{window.c=confirm('sure?');window.p=prompt('name?','x');alert('hi');window.done=1},ms||10);return 1};"
            + "</script>";
    @TempDir
    static Path temp;
    static final List<Process> started = new ArrayList<>();
    static final List<Path> projects = new ArrayList<>();
    static HttpServer server;
    static String base;
    static String endpoint;

    @BeforeAll
    static void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.equals("/dl") ? "report body" : path.startsWith("/page") ? "<!doctype html><title>" + path + "</title>" : PAGE).getBytes(StandardCharsets.UTF_8);
            if (path.equals("/dl")) {
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"report.txt\"");
            } else exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @BeforeEach
    void chrome(TestInfo info) throws Exception {
        endpoint = PlaywrightCdpLiveTest.startChrome(temp.resolve("profile-" + info.getTestMethod().orElseThrow().getName()), started);
    }

    @AfterEach
    void stopAll() {
        for (Path project : projects) PwCommandLiveTest.agentIn(project, "browser-events", "--stop");
        projects.clear();
        PlaywrightConnection.closeAll();
        started.forEach(PlaywrightCdpLiveTest::stopChrome);
        started.clear();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    private static Path project(String name) {
        Path project = temp.resolve(name);
        projects.add(project);
        return project;
    }

    static JsonNode ok(Path project, String... args) throws Exception {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--target=browser:cdp=" + endpoint);
        return BrowserEventsLiveTest.ok(project, all.toArray(String[]::new));
    }

    static PwCommandLiveTest.Capture run(Path project, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--target=browser:cdp=" + endpoint);
        return PwCommandLiveTest.agentIn(project, all.toArray(String[]::new));
    }

    static String eval(Path project, String expr) throws Exception {
        return ok(project, "pw", "page", "eval", "--expr=" + expr).path("value").asText();
    }

    static void go(Path project, String path) throws Exception {
        ok(project, "pw", "page", "goto", "--url=" + base + path);
    }

    static void handledAlert(Path project, String level) throws Exception {
        row(project, "dialog", r -> "handled".equals(r.path("subkind").asText()) && "alert".equals(r.path("method").asText())
                && level.equals(r.path("level").asText()));
        Thread.sleep(300);
    }

    static JsonNode row(Path project, String kind, Predicate<JsonNode> want) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        List<JsonNode> seen = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            JsonNode reply = BrowserEventsLiveTest.ok(project, "browser-events", "--since=0", "--kinds=" + kind, "--wait-ms=500", "--limit=500");
            JsonNode rows = reply.has("rows") ? reply.get("rows") : reply.path("items");
            for (JsonNode r : rows) {
                seen.add(r);
                if (want.test(r)) return r;
            }
        }
        throw new AssertionError("no " + kind + " row in " + seen);
    }

    @Test
    void covers_command_context_new() throws Exception {
        Path p = project("contexts");
        go(p, "/");
        ok(p, "storage", "--set={\"cookies\":[{\"name\":\"iso\",\"value\":\"default-only\",\"url\":\"" + base + "\"}]}", "--kind=cookies");
        JsonNode made = ok(p, "context-new");
        String context = made.path("id").asText();
        assertEquals("cdp", made.path("via").asText(), made.toString());
        JsonNode tab = ok(p, "tab-new", "--context=" + context, "--url=" + base + "/page-iso");
        String tabId = tab.path("id").asText();
        JsonNode isolated = ok(p, "storage", "--get", "--kind=cookies", "--context=" + context);
        assertEquals(0, isolated.path("cookies").size(), "a cookie set in the default context is absent in the new one: " + isolated);
        assertEquals(1, ok(p, "storage", "--get", "--kind=cookies").path("cookies").size());
        JsonNode list = ok(p, "context-list");
        assertTrue(list.toString().contains(context) && list.toString().contains(tabId), list.toString());
        assertTrue(Files.isDirectory(p.resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_context_list() throws Exception {
        Path p = project("list");
        go(p, "/");
        JsonNode list = ok(p, "context-list");
        assertEquals("default", list.path("contexts").path(0).path("id").asText(), list.toString());
        assertTrue(list.path("contexts").path(0).path("tabs").toString().contains(base), list.toString());
        assertTrue(Files.isDirectory(p.resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_context_close() throws Exception {
        Path p = project("close");
        String context = ok(p, "context-new").path("id").asText();
        ok(p, "tab-new", "--context=" + context, "--url=" + base + "/page-x");
        assertEquals(context, ok(p, "context-close", "--id=" + context).path("id").asText());
        assertFalse(ok(p, "context-list").toString().contains(context));
        PwCommandLiveTest.Capture bad = run(p, "context-close", "--id=default");
        assertEquals(1, bad.code());
        assertTrue(Files.isDirectory(p.resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_tab_new() throws Exception {
        Path p = project("tabs");
        go(p, "/");
        String id = ok(p, "tab-new", "--url=" + base + "/page-two").path("id").asText();
        assertTrue(ok(p, "context-list").toString().contains(id));
        assertEquals("cdp", ok(p, "tab-activate", "--id=" + id).path("via").asText());
        assertTrue(Files.isDirectory(p.resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_tab_activate() throws Exception {
        Path p = project("activate");
        go(p, "/");
        String first = ok(p, "context-list").path("contexts").path(0).path("tabs").path(0).path("id").asText();
        String second = ok(p, "tab-new", "--url=" + base + "/page-b").path("id").asText();
        ok(p, "tab-activate", "--id=" + second);
        ok(p, "tab-activate", "--id=" + first);
        PwCommandLiveTest.Capture missing = run(p, "tab-activate");
        assertEquals(1, missing.code());
        assertTrue(Files.isDirectory(p.resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_page_history() throws Exception {
        Path p = project("history");
        go(p, "/page-a");
        go(p, "/page-b");
        ok(p, "page-history", "--back");
        Thread.sleep(400);
        assertEquals("/page-a", eval(p, "location.pathname"));
        ok(p, "page-history", "--forward");
        Thread.sleep(400);
        assertEquals("/page-b", eval(p, "location.pathname"));
        ok(p, "page-history", "--reload=hard");
        assertTrue(Files.isDirectory(p.resolve(".pickleball/proto")));
    }

    @Test
    void covers_command_print() throws Exception {
        Path p = project("print");
        go(p, "/");
        JsonNode printed = ok(p, "print", "--out=out/page.pdf");
        byte[] pdf = Files.readAllBytes(p.resolve("out/page.pdf"));
        assertTrue(pdf.length > 500 && new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"), printed.toString());
    }

    @Test
    void covers_command_storage() throws Exception {
        Path p = project("storage");
        go(p, "/");
        ok(p, "storage", "--set={\"cookies\":[{\"name\":\"sid\",\"value\":\"cookie-secret-1\",\"url\":\"" + base + "\"}],\"local\":{\"lk\":\"lv\"},\"session\":{\"sk\":\"sv\"},"
                + "\"idb\":{\"appdb\":{\"items\":{\"one\":\"first\"}}},\"opfs\":{\"note.txt\":\"opfs text\"}}");
        JsonNode got = ok(p, "storage", "--get");
        assertEquals("[redacted]", got.path("cookies").path(0).path("value").asText(), got.toString());
        assertFalse(got.toString().contains("cookie-secret-1"));
        assertEquals("lv", got.path("local").path("lk").asText(), got.toString());
        assertEquals("sv", got.path("session").path("sk").asText(), got.toString());
        assertEquals("first", got.path("idb").path("appdb").path("items").path("one").asText(), got.toString());
        assertEquals("opfs text", got.path("opfs").path("note.txt").asText(), got.toString());
        JsonNode revealed = ok(p, "storage", "--get", "--kind=cookies", "--reveal");
        assertEquals("cookie-secret-1", revealed.path("cookies").path(0).path("value").asText());
        assertFalse(Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("cookie-secret-1"));
        ok(p, "storage", "--clear");
        JsonNode empty = ok(p, "storage", "--get");
        assertEquals(0, empty.path("cookies").size(), empty.toString());
        assertEquals(0, empty.path("local").size());
        assertEquals(0, empty.path("session").size());
        assertEquals(0, empty.path("idb").size(), empty.toString());
        assertEquals(0, empty.path("opfs").size(), empty.toString());
    }

    @Test
    void covers_command_storage_state() throws Exception {
        Path p = project("state");
        go(p, "/");
        ok(p, "storage", "--set={\"cookies\":[{\"name\":\"sid\",\"value\":\"keep-me\",\"url\":\"" + base + "\"}],\"local\":{\"theme\":\"dark\"}}");
        JsonNode saved = ok(p, "storage-state", "--save=state/session.json");
        Path file = p.resolve("state/session.json");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "owner-only");
        assertFalse(saved.toString().contains("keep-me"));
        JsonNode state = BrowserEventsLiveTest.JSON.readTree(file.toFile());
        assertEquals("keep-me", state.path("cookies").path(0).path("value").asText());
        assertEquals("dark", state.path("origins").path(0).path("localStorage").path(0).path("value").asText(), state.toString());
        assertFalse(Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log")).contains("keep-me"));
        ok(p, "storage", "--clear", "--kind=cookies,local");
        ok(p, "storage-state", "--load=state/session.json");
        JsonNode back = ok(p, "storage", "--get", "--kind=cookies,local", "--reveal");
        assertEquals("keep-me", back.path("cookies").path(0).path("value").asText(), back.toString());
        assertEquals("dark", back.path("local").path("theme").asText(), back.toString());
        String context = ok(p, "context-new", "--storage-state=state/session.json").path("id").asText();
        assertEquals("keep-me", ok(p, "storage", "--get", "--kind=cookies", "--reveal", "--context=" + context).path("cookies").path(0).path("value").asText());
    }

    @Test
    void covers_command_downloads() throws Exception {
        Path p = project("downloads");
        ok(p, "browser-events", "--start");
        go(p, "/");
        JsonNode set = ok(p, "downloads", "--dir=dl");
        assertEquals("cdp", set.path("via").asText(), set.toString());
        ok(p, "pw", "locator", "click", "--selector=#dl");
        JsonNode waited = ok(p, "downloads", "--wait", "--timeout=10000");
        assertTrue(waited.path("waited").asBoolean(), waited.toString());
        long deadline = System.currentTimeMillis() + 5000;
        while (!Files.isRegularFile(p.resolve("dl/report.txt")) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertEquals("report body", Files.readString(p.resolve("dl/report.txt")));
        row(p, "download", r -> "completed".equals(r.path("subkind").asText()) && r.path("text").asText().endsWith("report.txt"));
        assertEquals(1, ok(p, "downloads", "--list").path("downloads").size());
    }

    @Test
    void uploadViaCdpAndPlaywright() throws Exception {
        Path p = project("upload");
        Files.createDirectories(p);
        Files.writeString(p.resolve("a.txt"), "A");
        Files.writeString(p.resolve("b.txt"), "B");
        go(p, "/");
        JsonNode cdp = ok(p, "upload", "--selector=#f", "--file=a.txt,b.txt", "--via=cdp");
        assertEquals("cdp", cdp.path("via").asText(), cdp.toString());
        assertEquals("a.txt,b.txt", eval(p, "window.names"));
        go(p, "/");
        JsonNode pw = ok(p, "upload", "--selector=#f", "--file=b.txt", "--via=playwright");
        assertEquals("playwright", pw.path("via").asText(), pw.toString());
        assertEquals("b.txt", eval(p, "window.names"));
        PwCommandLiveTest.Capture missing = run(p, "upload", "--selector=#f", "--file=nope.txt", "--via=cdp");
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("no file nope.txt"), missing.err());
    }

    @Test
    void fileDialogRowAndAnswer() throws Exception {
        Path p = project("filedialog");
        Files.createDirectories(p);
        Files.writeString(p.resolve("pick.txt"), "P");
        ok(p, "browser-events", "--start");
        go(p, "/");
        JsonNode armed = ok(p, "upload", "--on-dialog", "--file=pick.txt");
        assertTrue(armed.path("onDialog").asBoolean(), armed.toString());
        go(p, "/");
        ok(p, "pw", "locator", "click", "--selector=#f");
        row(p, "file-dialog", r -> "opened".equals(r.path("subkind").asText()));
        row(p, "file-dialog", r -> "answered".equals(r.path("subkind").asText()));
        long deadline = System.currentTimeMillis() + 5000;
        while (!"pick.txt".equals(eval(p, "window.names")) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertEquals("pick.txt", eval(p, "window.names"));
    }

    @Test
    void covers_command_drop() throws Exception {
        Path p = project("drop");
        Files.createDirectories(p);
        Files.writeString(p.resolve("d.txt"), "D");
        go(p, "/");
        JsonNode dropped = ok(p, "drop", "--selector=#zone", "--file=d.txt");
        assertEquals("cdp", dropped.path("via").asText(), dropped.toString());
        assertEquals("d.txt|", eval(p, "window.dropped"));
        ok(p, "drop", "--selector=#zone", "--data=text/plain:hello drop");
        assertEquals("|hello drop", eval(p, "window.dropped"));
        go(p, "/");
        ok(p, "drop", "--selector=#zone", "--file=d.txt", "--via=playwright");
        assertTrue(eval(p, "window.dropped").startsWith("d.txt"), "Playwright Locator.drop");
    }

    @Test
    void covers_command_dialogs() throws Exception {
        Path p = project("dialogs");
        go(p, "/");
        assertEquals("accept", ok(p, "dialogs", "--policy=accept").path("policy").asText());
        // The dialogs open after the eval's own Playwright client has gone, as it does when the CLI process exits: a
        // Playwright client with no dialog listener auto-dismisses any dialog it sees (see docs/browser-events.md).
        eval(p, "window.done=0,window.dialogs(1500)");
        tools.dscode.browser.PlaywrightConnection.closeAll();
        handledAlert(p, "accept");
        assertEquals("true|x", eval(p, "window.c+'|'+window.p"), "accept: confirm true, prompt default");
        row(p, "dialog", r -> "opened".equals(r.path("subkind").asText()));
        ok(p, "dialogs", "--policy=dismiss");
        eval(p, "window.done=0,window.dialogs(1500)");
        tools.dscode.browser.PlaywrightConnection.closeAll();
        handledAlert(p, "dismiss");
        assertEquals("false|null", eval(p, "window.c+'|'+window.p"));
        ok(p, "dialogs", "--policy=prompt:Ann");
        eval(p, "window.done=0,window.dialogs(1500)");
        tools.dscode.browser.PlaywrightConnection.closeAll();
        handledAlert(p, "prompt");
        assertEquals("true|Ann", eval(p, "window.c+'|'+window.p"));
        String log = Files.readString(p.resolve(".pickleball/logs/proto-" + java.time.LocalDate.now() + ".log"));
        assertTrue(log.contains("dialogs policy=prompt"), "policy change logged");
        assertFalse(log.contains("Ann"), "prompt text is not logged");
        PwCommandLiveTest.Capture bad = run(p, "dialogs", "--policy=maybe");
        assertEquals(1, bad.code());
    }
}
