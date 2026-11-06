package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.LaunchTemplates;
import tools.dscode.common.agentcontrol.ObserveEvents;
import tools.dscode.common.agentcontrol.ScenarioCapture;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Targeted coverage for the 2.1.19 agent commands. Each method is a real CLI call. */
class AgentExpansionTest {
    @TempDir
    Path temp;

    @Test
    void covers_command_observe() throws Exception {
        Path events = temp.resolve("events.json");
        Files.writeString(events, """
                {"events":[
                  {"method":"Network.requestWillBeSent","kind":"network","url":"https://example.test/a","text":"","at":10,"httpMethod":"GET"},
                  {"method":"Network.requestWillBeSent","kind":"network","url":"https://other.test/b","text":"","at":20,"httpMethod":"POST"},
                  {"method":"Runtime.consoleAPICalled","kind":"console","url":"","text":"hello example","at":30}
                ]}
                """);
        Capture capture = agent("observe", temp.toString(), "--events=" + events, "--url=example", "--limit=1");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("\"count\":1"), capture.out);
        assertTrue(capture.out.contains("example.test") || capture.out.contains("hello example"), capture.out);
        assertTrue(capture.out.contains("CDP Session"), capture.out);
        assertFalse(capture.out.contains("other.test"), capture.out);
        List<ObserveEvents.Event> filtered = ObserveEvents.filter(
                ObserveEvents.parse(Files.readString(events)),
                Map.of("type", "console", "url", "example")
        );
        assertEquals(1, filtered.size());
        assertEquals("console", filtered.get(0).kind());
    }

    @Test
    void covers_command_launch() throws Exception {
        Capture capture = agent(
                "launch", temp.toString(), "--run-id=run-1",
                "--template=chrome-headed-quiet", "--arg=--disable-gpu"
        );
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("chrome-headed-quiet"), capture.out);
        assertTrue(capture.out.contains("pkb_browser") || capture.out.contains("CHROME"), capture.out);
        assertTrue(capture.out.contains("--disable-gpu"), capture.out);
        assertTrue(capture.out.contains("Selenium Manager"), capture.out);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/agent-launch.json")));
        Capture windows = agent("windows", temp.toString(), "--run-id=run-1");
        assertEquals(0, windows.code, windows.err);
        assertTrue(windows.out.contains("window-1"), windows.out);
        Capture unknown = agent("launch", temp.toString(), "--template=not-a-browser");
        assertEquals(2, unknown.code);
        assertTrue(unknown.err.contains("error:"), unknown.err);
        assertTrue(unknown.err.contains("fix:"), unknown.err);
        assertEquals(7, LaunchTemplates.names().size());
        assertTrue(LaunchTemplates.names().contains("chrome-headed-logs"));
        assertTrue(LaunchTemplates.resolve("edge-headless-quiet", List.of("--disable-gpu")).args().contains("--headless=new"));
    }

    @Test
    void covers_command_windows() throws Exception {
        assertEquals(0, agent("launch", temp.toString(), "--run-id=w", "--template=edge-headed-quiet").code);
        Capture listed = agent("windows", temp.toString(), "--run-id=w");
        assertEquals(0, listed.code, listed.err);
        assertTrue(listed.out.contains("\"kind\":\"window\""), listed.out);
        Capture switched = agent("windows", temp.toString(), "--run-id=w", "--switch=window-1");
        assertEquals(0, switched.code, switched.err);
        assertTrue(switched.out.contains("window-1"), switched.out);
        Capture closed = agent("windows", temp.toString(), "--run-id=w", "--close=window-1");
        assertEquals(0, closed.code, closed.err);
        assertTrue(closed.out.contains("\"open\":false") || closed.out.contains("Closed"), closed.out);
        Capture missing = agent("windows", temp.toString(), "--run-id=w", "--switch=missing");
        assertEquals(2, missing.code);
        assertTrue(missing.err.contains("error:"), missing.err);
    }

    @Test
    void covers_command_tabs() throws Exception {
        assertEquals(0, agent("launch", temp.toString(), "--run-id=t", "--template=chrome-headless-verbose").code);
        Capture listed = agent("tabs", temp.toString(), "--run-id=t");
        assertEquals(0, listed.code, listed.err);
        assertTrue(listed.out.contains("tab-2") || listed.out.contains("\"kind\":\"tab\""), listed.out);
        Capture closed = agent("tabs", temp.toString(), "--run-id=t", "--close=tab-2");
        assertEquals(0, closed.code, closed.err);
        assertTrue(closed.out.contains("tab-2"), closed.out);
    }

    @Test
    void covers_command_chat_send() throws Exception {
        Capture sent = agent("chat-send", temp.toString(), "--run-id=chat", "--channel=run", "--source=human", "--agent=ada", "--text=hello");
        assertEquals(0, sent.code, sent.err);
        assertTrue(sent.out.contains("\"source\":\"human\""), sent.out);
        assertTrue(sent.out.contains("hello"), sent.out);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/runs/chat/agent/chat/run.jsonl")));
        Capture missing = agent("chat-send", temp.toString(), "--run-id=chat");
        assertEquals(2, missing.code);
        assertTrue(missing.err.contains("error:"), missing.err);
    }

    @Test
    void covers_command_chat_history() throws Exception {
        assertEquals(0, agent("chat-send", temp.toString(), "--run-id=chat", "--text=one", "--source=agent", "--agent=ada").code);
        assertEquals(0, agent("chat-send", temp.toString(), "--run-id=chat", "--text=two", "--source=system").code);
        Capture history = agent("chat-history", temp.toString(), "--run-id=chat", "--limit=1");
        assertEquals(0, history.code, history.err);
        assertTrue(history.out.contains("two"), history.out);
        assertFalse(history.out.contains("\"text\":\"one\""), history.out);
    }

    @Test
    void covers_command_chat_poll() throws Exception {
        Capture first = agent("chat-send", temp.toString(), "--run-id=chat", "--text=one");
        assertEquals(0, first.code, first.err);
        String id = first.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        assertEquals(0, agent("chat-send", temp.toString(), "--run-id=chat", "--text=two").code);
        Capture polled = agent("chat-poll", temp.toString(), "--run-id=chat", "--id=" + id);
        assertEquals(0, polled.code, polled.err);
        assertTrue(polled.out.contains("two"), polled.out);
        assertFalse(polled.out.contains("\"text\":\"one\""), polled.out);
    }

    @Test
    void covers_command_highlight() throws Exception {
        Path page = page();
        Capture painted = agent("highlight", temp.toString(), "--handle=save", "--preset=yellow", "--html=" + page);
        assertEquals(0, painted.code, painted.err);
        assertTrue(painted.out.contains("style_modified"), painted.out);
        assertTrue(painted.out.contains("person did not author"), painted.out);
        String html = Files.readString(page);
        assertTrue(html.contains("data-pkb-style=\"style_modified\""), html);
        assertTrue(html.contains("background-color:#fff3a0"), html);
        Capture bad = agent("highlight", temp.toString(), "--handle=save", "--preset=blue", "--html=" + page);
        assertEquals(2, bad.code);
        assertTrue(bad.err.contains("error:"), bad.err);
    }

    @Test
    void covers_command_unhighlight() throws Exception {
        Path page = page();
        assertEquals(0, agent("highlight", temp.toString(), "--handle=save", "--preset=outline-red", "--html=" + page).code);
        Capture cleared = agent("unhighlight", temp.toString(), "--handle=save", "--html=" + page);
        assertEquals(0, cleared.code, cleared.err);
        assertTrue(cleared.out.contains("\"reversed\":true"), cleared.out);
        assertFalse(Files.readString(page).contains("data-pkb-style"), Files.readString(page));
    }

    @Test
    void covers_command_overlay() throws Exception {
        Path page = page();
        Capture placed = agent(
                "overlay", temp.toString(), "--run-id=ov", "--html=" + page,
                "--text=<button>click here</button><script>alert(1)</script>",
                "--x=12", "--y=40"
        );
        assertEquals(0, placed.code, placed.err);
        assertTrue(placed.out.contains("agent_injected"), placed.out);
        assertTrue(placed.out.contains("overlay"), placed.out);
        assertTrue(placed.out.contains("a person did not author this UI"), placed.out);
        assertTrue(placed.out.contains("visual-viewport-relative"), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains("data-pkb-agent=\"agent_injected\""), html);
        assertTrue(html.contains("click here"), html);
        assertFalse(html.contains("<script"), html);
        assertTrue(html.contains("left:12px"), html);
    }

    @Test
    void covers_command_overlay_poll() throws Exception {
        Capture polled = agent("overlay-poll", temp.toString(), "--run-id=ov");
        assertEquals(0, polled.code, polled.err);
        assertTrue(polled.out.contains("\"events\""), polled.out);
    }

    @Test
    void covers_command_overlay_remove() throws Exception {
        Path page = page();
        Capture placed = agent("overlay", temp.toString(), "--html=" + page, "--text=click here");
        assertEquals(0, placed.code, placed.err);
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("overlay-remove", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertTrue(removed.out.contains("\"removed\":true"), removed.out);
        assertFalse(Files.readString(page).contains("click here"));
    }

    @Test
    void covers_command_html_write() throws Exception {
        Path page = page();
        Capture written = agent("html-write", temp.toString(), "--html=" + page, "--text=<p id=\"note\">agent note</p>");
        assertEquals(0, written.code, written.err);
        assertTrue(written.out.contains("agent_injected"), written.out);
        assertTrue(written.out.contains("sha256"), written.out);
        String html = Files.readString(page);
        assertTrue(html.contains("agent note"), html);
        assertTrue(html.contains("data-pkb-agent=\"agent_injected\""), html);
        assertTrue(html.contains("a person did not author this node"), html);
    }

    @Test
    void covers_command_html_replace() throws Exception {
        Path page = page();
        Capture replaced = agent("html-replace", temp.toString(), "--html=" + page, "--handle=save", "--text=<span>replaced</span>");
        assertEquals(0, replaced.code, replaced.err);
        String html = Files.readString(page);
        assertTrue(html.contains("replaced"), html);
        assertFalse(html.contains(">Save<"), html);
    }

    @Test
    void covers_command_html_insert() throws Exception {
        Path page = page();
        Capture inserted = agent("html-insert", temp.toString(), "--html=" + page, "--handle=save", "--where=before", "--text=<i>before</i>");
        assertEquals(0, inserted.code, inserted.err);
        String html = Files.readString(page);
        assertTrue(html.indexOf("before") < html.indexOf("id=\"save\""), html);
    }

    @Test
    void covers_command_html_remove() throws Exception {
        Path page = page();
        Capture written = agent("html-write", temp.toString(), "--html=" + page, "--text=<b>gone</b>");
        String id = written.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("html-remove", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.readString(page).contains("gone"));
    }

    @Test
    void covers_command_html_cleanup() throws Exception {
        Path page = page();
        assertEquals(0, agent("html-write", temp.toString(), "--html=" + page, "--text=<b>temp</b>").code);
        assertEquals(0, agent("highlight", temp.toString(), "--handle=save", "--preset=yellow", "--html=" + page).code);
        Capture cleaned = agent("html-cleanup", temp.toString(), "--html=" + page);
        assertEquals(0, cleaned.code, cleaned.err);
        String html = Files.readString(page);
        assertFalse(html.contains("temp"), html);
        assertFalse(html.contains("data-pkb-style"), html);
    }

    @Test
    void covers_command_capture_scenario() throws Exception {
        Path events = temp.resolve("events.json");
        Files.writeString(events, """
                {"events":[
                  {"method":"Network.requestWillBeSent","kind":"network","url":"https://api.example.test/health?ready=1","httpMethod":"GET","at":5},
                  {"method":"Runtime.consoleAPICalled","kind":"console","url":"","text":"skip","at":6}
                ]}
                """);
        Path out = temp.resolve("captured.feature");
        Capture capture = agent(
                "capture-scenario", temp.toString(), "--events=" + events, "--url=health", "--out=" + out
        );
        assertEquals(0, capture.code, capture.err);
        String feature = Files.readString(out);
        assertTrue(feature.contains("Scenario Outline:"), feature);
        assertTrue(feature.contains("MAP \"REQUEST\" TABLE VALUES TO SCENARIO MAP"), feature);
        assertTrue(feature.contains("EXECUTE SERVICE CALL"), feature);
        assertTrue(feature.contains("| GET |"), feature);
        assertTrue(feature.contains("REQUEST.queryParams"), feature);
        assertTrue(feature.contains("| ready | 1 |"), feature);
        assertTrue(capture.out.contains("Existing service-call"), capture.out);
        assertTrue(feature.contains("EXECUTE SERVICE CALL"), feature);
        assertFalse(feature.contains("consoleAPICalled"), feature);
        assertTrue(ScenarioCapture.feature(ObserveEvents.parse(Files.readString(events))).contains("EXECUTE SERVICE CALL"));
    }

    private Path page() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page, "<html><body><button id=\"save\">Save</button></body></html>");
        return page;
    }

    private Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
