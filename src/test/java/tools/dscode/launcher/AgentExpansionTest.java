package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.LaunchTemplates;
import tools.dscode.common.agentcontrol.ObserveEvents;
import tools.dscode.common.agentcontrol.ScenarioCapture;
import tools.dscode.common.agentfast.LiveBrowserHost;
import dev.domengine.session.SessionEvents;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void observe_failure_is_not_an_empty_buffer() throws Exception {
        CompositeRuntime.use(temp, Map.of(), AgentExpansionTest.class.getClassLoader(), temp);
        try {
            Capture capture = agent("observe", temp.toString(), "--run-id=obs");
            assertNotEquals(0, capture.code, capture.out + capture.err);
            assertFalse(capture.out.contains("\"source\":\"cdp-session\""), capture.out);
            assertTrue((capture.out + capture.err).contains("error: no live browser"), capture.out + capture.err);
            tools.dscode.common.agentcontrol.AgentControl.Result direct =
                    tools.dscode.common.agentcontrol.AgentControl.execute(temp, "obs", "observe", java.util.Map.of(), java.util.List.of(), "a");
            String directText = String.valueOf(direct.body());
            assertNotEquals(0, direct.exit(), directText);
            assertTrue(directText.contains("observe failed"), directText);
            assertFalse(directText.contains("\"source\":\"cdp-session\""), directText);
            Capture captured = agent("capture-scenario", temp.toString(), "--run-id=obs2");
            assertNotEquals(0, captured.code, captured.out + captured.err);
            assertFalse(Files.exists(temp.resolve(".pickleball/runs/obs2/agent/captured-service-calls.feature")));
            assertFalse(captured.out.contains("No Network.requestWillBeSent"), captured.out);
        } finally {
            LiveBrowserHost.useEventsForTests(null);
            CompositeRuntime.resetForTests();
        }
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
        assertFalse(windows.out.contains("window-1"), windows.out);
        assertTrue(windows.out.contains("\"count\":0"), windows.out);
        Capture unknown = agent("launch", temp.toString(), "--template=not-a-browser");
        assertEquals(2, unknown.code);
        assertTrue(unknown.err.contains("error:"), unknown.err);
        assertTrue(unknown.err.contains("fix:"), unknown.err);
        assertEquals(8, LaunchTemplates.names().size());
        assertTrue(LaunchTemplates.names().contains("chrome-headed-logs"));
        assertTrue(LaunchTemplates.names().contains("chromium-headed-quiet"));
        assertTrue(LaunchTemplates.resolve("chromium-headed-quiet", List.of()).sentence().contains("Not a second launcher"));
        assertTrue(LaunchTemplates.resolve("edge-headless-quiet", List.of("--disable-gpu")).args().contains("--headless=new"));
    }

    @Test
    void covers_command_windows() throws Exception {
        assertEquals(0, agent("launch", temp.toString(), "--run-id=w", "--template=edge-headed-quiet").code);
        Capture listed = agent("windows", temp.toString(), "--run-id=w");
        assertEquals(0, listed.code, listed.err);
        assertFalse(listed.out.contains("window-1"), listed.out);
        assertTrue(listed.out.contains("\"count\":0"), listed.out);
        Capture switched = agent("windows", temp.toString(), "--run-id=w", "--switch=window-1");
        assertEquals(2, switched.code);
        assertTrue(switched.err.contains("unknown handle"), switched.err);
        Capture closed = agent("windows", temp.toString(), "--run-id=w", "--close=window-1");
        assertEquals(2, closed.code);
        Capture missing = agent("windows", temp.toString(), "--run-id=w", "--switch=missing");
        assertEquals(2, missing.code);
        assertTrue(missing.err.contains("error:"), missing.err);
    }

    @Test
    void covers_command_tabs() throws Exception {
        assertEquals(0, agent("launch", temp.toString(), "--run-id=t", "--template=chrome-headless-verbose").code);
        Capture listed = agent("tabs", temp.toString(), "--run-id=t");
        assertEquals(0, listed.code, listed.err);
        assertFalse(listed.out.contains("tab-2"), listed.out);
        assertTrue(listed.out.contains("\"count\":0"), listed.out);
        Capture closed = agent("tabs", temp.toString(), "--run-id=t", "--close=tab-2");
        assertEquals(2, closed.code);
        assertTrue(closed.err.contains("unknown handle"), closed.err);
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
        Capture missing = agent("highlight", temp.toString(), "--handle=main:4", "--preset=yellow");
        assertNotEquals(0, missing.code);
        assertTrue(missing.out.contains("no live browser") || missing.err.contains("no live browser"), missing.out + missing.err);
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
        assertTrue(placed.out.contains("\"placement\":\"caller\""), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains("data-pkb-agent=\"agent_injected\""), html);
        assertTrue(html.contains("click here"), html);
        assertFalse(html.contains("<script"), html);
        assertTrue(html.contains("left:12px"), html);
    }

    @Test
    void overlayStoresWidthAndResolvedBoundsWin() throws Exception {
        Path page = page();
        Capture stored = agent(
                "overlay", temp.toString(), "--html=" + page, "--text=sized",
                "--x=3", "--y=4", "--width=40", "--height=16", "--target=page"
        );
        assertEquals(0, stored.code, stored.err);
        assertTrue(stored.out.contains("\"width\":40"), stored.out);
        assertTrue(stored.out.contains("\"height\":16"), stored.out);
        assertTrue(stored.out.contains("\"boundsSource\":\"stored\""), stored.out);
        assertTrue(stored.out.contains("#fffef2") || Files.readString(page).contains("#fffef2"));
        String html = Files.readString(page);
        assertTrue(html.contains("width:40px"), html);
        assertTrue(html.contains("height:16px"), html);
        assertTrue(html.contains("background:#fffef2"), html);

        Capture geometry = agent(
                "overlay", temp.toString(), "--html=" + page, "--text=geom",
                "--width=9", "--height=9", "--target=page",
                "--geometry={\"visual_viewport_relative\":{\"x\":5,\"y\":6,\"width\":70,\"height\":18}}"
        );
        assertEquals(0, geometry.code, geometry.err);
        assertTrue(geometry.out.contains("\"width\":70"), geometry.out);
        assertTrue(geometry.out.contains("\"boundsSource\":\"resolved\""), geometry.out);
        assertTrue(Files.readString(page).contains("left:5px"), Files.readString(page));
        assertTrue(Files.readString(page).contains("width:70px"), Files.readString(page));

        AgentControl.rememberBounds("save", 11, 12, 80, 22);
        try {
            AgentControl.Result result = AgentControl.execute(temp, "ovb", "overlay", Map.of(
                    "text", "box",
                    "handle", "save",
                    "html", page.toString(),
                    "target", "page",
                    "x", "1",
                    "y", "2",
                    "width", "9",
                    "height", "9"
            ), List.of(), "a");
            assertEquals(0, result.exit(), String.valueOf(result.body()));
            assertEquals(80, result.body().get("width"));
            assertEquals(22, result.body().get("height"));
            assertEquals("resolved", result.body().get("boundsSource"));
            assertEquals(11, result.body().get("x"));
            assertTrue(Files.readString(page).contains("width:80px"));
        } finally {
            AgentControl.clearRememberedGeometry();
        }

        AgentControl.rememberGeometry("only-point", 8.4, 9.6);
        try {
            int[] point = AgentControl.rememberedPoint("only-point");
            assertEquals(2, point.length);
            assertEquals(8, point[0]);
            assertEquals(10, point[1]);
        } finally {
            AgentControl.clearRememberedGeometry();
        }

        Capture ignored = agent(
                "highlight", temp.toString(), "--handle=save", "--preset=yellow",
                "--html=" + page, "--x=1", "--y=2", "--width=50", "--height=60", "--target=page"
        );
        assertEquals(0, ignored.code, ignored.err);
        assertFalse(Files.readString(page).contains("width:50px"), Files.readString(page));

        AgentControl.Result nativeBox = AgentControl.execute(temp, "nat", "overlay", Map.of(
                "target", "native", "text", "cap", "x", "8", "y", "9", "width", "30", "height", "12"
        ), List.of(), "a");
        assertEquals(0, nativeBox.exit(), String.valueOf(nativeBox.body()));
        assertEquals("native", nativeBox.body().get("target"));
        assertEquals(30, nativeBox.body().get("width"));
        assertEquals("stored", nativeBox.body().get("boundsSource"));
        if (Boolean.FALSE.equals(nativeBox.body().get("drawn"))) {
            assertEquals("headless", nativeBox.body().get("reason"));
        }
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
        Capture placed = agent("overlay", temp.toString(), "--html=" + page, "--text=click here", "--x=8", "--y=8");
        assertEquals(0, placed.code, placed.err);
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("overlay-remove", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertTrue(removed.out.contains("\"removed\":true"), removed.out);
        assertFalse(Files.readString(page).contains("click here"));
    }

    @Test
    void covers_command_label() throws Exception {
        Path page = page();
        Capture placed = agent(
                "label", temp.toString(), "--html=" + page, "--text=Save name",
                "--x=4", "--y=6", "--target=page"
        );
        assertEquals(0, placed.code, placed.err);
        assertTrue(placed.out.contains("agent_injected"), placed.out);
        assertTrue(placed.out.contains("agent-injected label"), placed.out);
        assertTrue(placed.out.contains("\"kind\":\"label\""), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains("Save name"), html);
        assertTrue(html.contains("data-pkb-kind=\"label\""), html);
        assertTrue(html.contains("background:#ffffff;color:#1b1f24"), html);
        assertTrue(html.contains("left:4px"), html);
        Capture dirty = agent(
                "label", temp.toString(), "--html=" + page, "--text=<b>ok</b><script>alert(1)</script>",
                "--x=1", "--y=2", "--target=page"
        );
        assertEquals(0, dirty.code, dirty.err);
        assertTrue(dirty.out.contains("\"scriptStripped\":true"), dirty.out);
        assertFalse(Files.readString(page).contains("<script"), Files.readString(page));
        Capture missing = agent("label", temp.toString(), "--html=" + page, "--x=1", "--y=1");
        assertEquals(2, missing.code);
        assertTrue(missing.err.contains("label requires --text"), missing.err);
    }

    @Test
    void covers_command_unlabel() throws Exception {
        Path page = page();
        Capture placed = agent("label", temp.toString(), "--html=" + page, "--text=Gone", "--x=1", "--y=1");
        assertEquals(0, placed.code, placed.err);
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("unlabel", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertTrue(removed.out.contains("\"removed\":true"), removed.out);
        assertFalse(Files.readString(page).contains("Gone"), Files.readString(page));
    }

    @Test
    void unVerbRefusesAPlacementOfAnotherKind() throws Exception {
        Path page = page();
        Capture placed = agent("border", temp.toString(), "--html=" + page, "--x=1", "--y=1", "--width=10", "--height=8");
        assertEquals(0, placed.code, placed.err);
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture wrong = agent("unlabel", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(2, wrong.code, wrong.out + wrong.err);
        String message = wrong.out + wrong.err;
        assertTrue(message.contains("is a border, not a label"), message);
        assertTrue(message.contains("unborder --id=" + id), message);
        assertTrue(Files.readString(page).contains("data-pkb-kind=\"border\""), Files.readString(page));
        Capture stepWrong = agent("unstep-badge", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(2, stepWrong.code, stepWrong.out + stepWrong.err);
        Capture right = agent("unborder", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, right.code, right.err);
        assertFalse(Files.readString(page).contains("data-pkb-kind=\"border\""), Files.readString(page));
    }

    @Test
    void unVerbRefusesAnOverlayAndNamesItsRemovalVerb() throws Exception {
        Path page = page();
        Capture placed = agent("label", temp.toString(), "--html=" + page, "--text=Keep", "--x=1", "--y=1");
        assertEquals(0, placed.code, placed.err);
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture wrong = agent("unspotlight", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(2, wrong.code, wrong.out + wrong.err);
        assertTrue((wrong.out + wrong.err).contains("is a label, not a spotlight; use unlabel"), wrong.out + wrong.err);
        assertTrue(Files.readString(page).contains("Keep"), Files.readString(page));
        assertEquals("unstep-badge", tools.dscode.common.agentcontrol.VisualKinds.removalVerb("step"));
        assertEquals("overlay-remove", tools.dscode.common.agentcontrol.VisualKinds.removalVerb("overlay"));
        assertEquals("html-remove", tools.dscode.common.agentcontrol.VisualKinds.removalVerb("html"));
    }

    @Test
    void covers_command_border() throws Exception {
        Path page = page();
        Capture stored = agent(
                "border", temp.toString(), "--html=" + page, "--x=2", "--y=3",
                "--width=40", "--height=18", "--color=#123456", "--target=page"
        );
        assertEquals(0, stored.code, stored.err);
        assertTrue(stored.out.contains("\"width\":40"), stored.out);
        assertTrue(stored.out.contains("\"boundsSource\":\"stored\""), stored.out);
        assertTrue(stored.out.contains("agent-injected border"), stored.out);
        String html = Files.readString(page);
        assertTrue(html.contains("border:3px solid #123456"), html);
        assertTrue(html.contains("width:40px"), html);
        assertTrue(html.contains("data-pkb-agent=\"agent_injected\""), html);

        AgentControl.rememberBounds("save", 11, 12, 80, 22);
        try {
            AgentControl.Result result = AgentControl.execute(temp, "brd", "border", Map.of(
                    "handle", "save",
                    "html", page.toString(),
                    "target", "page",
                    "x", "1",
                    "y", "2",
                    "width", "9",
                    "height", "9"
            ), List.of(), "a");
            assertEquals(0, result.exit(), String.valueOf(result.body()));
            assertEquals(80, result.body().get("width"));
            assertEquals(22, result.body().get("height"));
            assertEquals("resolved", result.body().get("boundsSource"));
            assertTrue(Files.readString(page).contains("left:11px"), Files.readString(page));
            assertTrue(Files.readString(page).contains("width:80px"), Files.readString(page));
        } finally {
            AgentControl.clearRememberedGeometry();
        }
    }

    @Test
    void covers_command_unborder() throws Exception {
        Path page = page();
        Capture placed = agent("border", temp.toString(), "--html=" + page, "--x=1", "--y=1", "--width=10", "--height=8");
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("unborder", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.readString(page).contains("data-pkb-kind=\"border\""), Files.readString(page));
    }

    @Test
    void covers_command_arrow() throws Exception {
        Path page = page();
        Capture placed = agent(
                "arrow", temp.toString(), "--html=" + page, "--x=10", "--y=20",
                "--direction=right", "--target=page"
        );
        assertEquals(0, placed.code, placed.err);
        assertTrue(placed.out.contains("\"kind\":\"arrow\""), placed.out);
        assertTrue(placed.out.contains("agent-injected arrow"), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains("<svg"), html);
        assertTrue(html.contains("pkb-arrowhead"), html);
        assertTrue(html.contains("data-pkb-agent=\"agent_injected\""), html);
        assertFalse(html.contains("<script"), html);

        AgentControl.rememberBounds("from-h", 10, 10, 20, 20);
        AgentControl.rememberBounds("to-h", 80, 40, 10, 10);
        try {
            AgentControl.Result result = AgentControl.execute(temp, "arw", "arrow", Map.of(
                    "from", "from-h",
                    "to", "to-h",
                    "html", page.toString(),
                    "target", "page"
            ), List.of(), "a");
            assertEquals(0, result.exit(), String.valueOf(result.body()));
            assertEquals("resolved", result.body().get("boundsSource"));
            assertTrue(Files.readString(page).contains("<line"), Files.readString(page));
        } finally {
            AgentControl.clearRememberedGeometry();
        }
    }

    @Test
    void covers_command_unarrow() throws Exception {
        Path page = page();
        Capture placed = agent("arrow", temp.toString(), "--html=" + page, "--x=4", "--y=4", "--direction=down");
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("unarrow", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.readString(page).contains("<svg"), Files.readString(page));
    }

    @Test
    void covers_command_step_badge() throws Exception {
        Path page = page();
        Capture placed = agent(
                "step-badge", temp.toString(), "--html=" + page, "--number=3",
                "--text=Save", "--x=8", "--y=9", "--target=page"
        );
        assertEquals(0, placed.code, placed.err);
        assertTrue(placed.out.contains("\"number\":3"), placed.out);
        assertTrue(placed.out.contains("agent-injected step badge"), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains(">3<"), html);
        assertTrue(html.contains("Save"), html);
        assertTrue(html.contains("#ff2d55"), html);
        assertFalse(html.contains("Scenario"), html);
        Capture bad = agent("step-badge", temp.toString(), "--html=" + page, "--text=no number", "--x=1", "--y=1");
        assertEquals(2, bad.code);
        assertTrue(bad.err.contains("--number"), bad.err);
    }

    @Test
    void covers_command_unstep_badge() throws Exception {
        Path page = page();
        Capture placed = agent("step-badge", temp.toString(), "--html=" + page, "--number=1", "--x=1", "--y=1");
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("unstep-badge", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.readString(page).contains("#ff2d55"), Files.readString(page));
    }

    @Test
    void covers_command_magnifier() throws Exception {
        Path page = page();
        Capture placed = agent(
                "magnifier", temp.toString(), "--html=" + page, "--text=detail",
                "--zoom=2", "--x=5", "--y=6", "--width=90", "--height=90", "--target=page"
        );
        assertEquals(0, placed.code, placed.err);
        assertTrue(placed.out.contains("\"zoom\":\"2.0\"") || placed.out.contains("\"zoom\":\"2\""), placed.out);
        assertTrue(placed.out.contains("agent-injected magnifier"), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains("detail"), html);
        assertTrue(html.contains("border-radius:50%"), html);
        assertTrue(html.contains("scale(2"), html);
        assertTrue(html.contains("data-pkb-copy=\"fallback\""), html);
        Capture bad = agent("magnifier", temp.toString(), "--html=" + page, "--zoom=12", "--x=1", "--y=1");
        assertEquals(2, bad.code);
        assertTrue(bad.err.contains("--zoom"), bad.err);
    }

    @Test
    void covers_command_unmagnifier() throws Exception {
        Path page = page();
        Capture placed = agent("magnifier", temp.toString(), "--html=" + page, "--x=1", "--y=1");
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("unmagnifier", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.readString(page).contains("border-radius:50%"), Files.readString(page));
    }

    @Test
    void covers_command_spotlight() throws Exception {
        Path page = page();
        Capture placed = agent(
                "spotlight", temp.toString(), "--html=" + page, "--dim=0.4",
                "--x=3", "--y=4", "--width=70", "--height=30", "--target=page"
        );
        assertEquals(0, placed.code, placed.err);
        assertTrue(placed.out.contains("\"dim\":\"0.4\""), placed.out);
        assertTrue(placed.out.contains("agent-injected spotlight"), placed.out);
        String html = Files.readString(page);
        assertTrue(html.contains("box-shadow:0 0 0 9999px rgba(0,0,0,0.4)"), html);
        assertTrue(html.contains("width:70px"), html);
        assertTrue(html.contains("data-pkb-agent=\"agent_injected\""), html);
        Capture cleaned = agent("html-cleanup", temp.toString(), "--html=" + page);
        assertEquals(0, cleaned.code, cleaned.err);
        assertFalse(Files.readString(page).contains("9999px"), Files.readString(page));
    }

    @Test
    void covers_command_unspotlight() throws Exception {
        Path page = page();
        Capture placed = agent("spotlight", temp.toString(), "--html=" + page, "--x=1", "--y=1", "--width=20", "--height=10");
        String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        Capture removed = agent("unspotlight", temp.toString(), "--html=" + page, "--id=" + id);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.readString(page).contains("9999px"), Files.readString(page));
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

    @Test
    void observe_and_capture_scenario_read_the_start_host_buffer() throws Exception {
        LiveBrowserHost.useEventsForTests(null);
        SessionEvents buffer = new SessionEvents();
        buffer.accept(new ObjectMapper().readTree("""
                {"method":"Network.requestWillBeSent","params":{"requestId":"r1","wallTime":1.7,"request":{"url":"https://api.example.test/health?ready=1","method":"GET"}}}
                """));
        LiveBrowserHost.useEventsForTests(buffer);
        try {
            tools.dscode.common.agentcontrol.AgentControl.Result observed =
                    tools.dscode.common.agentcontrol.AgentControl.execute(temp, "host-obs", "observe", Map.of(), List.of(), "a");
            String observedText = String.valueOf(observed.body());
            assertEquals(0, observed.exit(), observedText);
            assertTrue(observedText.contains("api.example.test"), observedText);
            assertTrue(observedText.contains("\"count\":1") || observedText.contains("count=1"), observedText);
            Path feature = temp.resolve("host-captured.feature");
            tools.dscode.common.agentcontrol.AgentControl.Result captured =
                    tools.dscode.common.agentcontrol.AgentControl.execute(
                            temp, "host-cap", "capture-scenario", Map.of("out", feature.toString()), List.of(), "a");
            String capturedText = String.valueOf(captured.body());
            assertEquals(0, captured.exit(), capturedText);
            String written = Files.readString(feature);
            assertTrue(written.contains("EXECUTE SERVICE CALL"), written);
            assertTrue(written.contains("health"), written);
            assertFalse(written.contains("No Network.requestWillBeSent"), written);
        } finally {
            LiveBrowserHost.useEventsForTests(null);
        }
    }

    @Test
    void covers_command_capture_steps() throws Exception {
        Path events = temp.resolve("clicks.jsonl");
        Files.writeString(events, """
                {"type":"click","ref":"s1.h1","query":"Save"}
                {"type":"answer","ref":"s1.h2","value":"Ada"}
                {"type":"answer","sensitive":"true","value":"secret","ref":"s1.h3"}
                {"op":"snap","args":{"budget":"4"}}
                """);
        Path out = temp.resolve("steps.json");
        Capture capture = agent("capture-steps", temp.toString(), "--events=" + events, "--out=" + out, "--run-id=cap");
        assertEquals(0, capture.code, capture.out + capture.err);
        assertTrue(capture.out.contains("\"count\":3"), capture.out);
        String body = Files.readString(out);
        assertTrue(body.contains("\"op\":\"act\""), body);
        assertTrue(body.contains("\"kind\":\"click\""), body);
        assertTrue(body.contains("\"kind\":\"type\""), body);
        assertTrue(body.contains("\"op\":\"snap\""), body);
        assertFalse(body.contains("secret"), body);
        Path bare = Files.createDirectories(temp.resolve("bare"));
        Path emptyOut = bare.resolve("captured-steps.json");
        Capture empty = agent("capture-steps", bare.toString(), "--run-id=none", "--out=" + emptyOut);
        assertEquals(0, empty.code, empty.out + empty.err);
        assertTrue(empty.out.contains("no steps captured"), empty.out);
        assertTrue(empty.out.contains("\"count\":0"), empty.out);
        assertEquals("[]", Files.readString(emptyOut).trim());
    }

    @Test
    void step_badge_and_magnifier_text_is_escaped_not_markup() throws Exception {
        Path page = page();
        Capture step = agent(
                "step-badge", temp.toString(), "--html=" + page, "--number=4",
                "--text=<b>Go</b> & \"on\"", "--x=1", "--y=1", "--target=page"
        );
        assertEquals(0, step.code, step.err);
        String html = Files.readString(page);
        assertFalse(html.contains("<b>Go</b>"), html);
        assertTrue(html.contains("&" + "lt;b&" + "gt;Go&" + "lt;/b&" + "gt;"), html);
        assertTrue(html.contains("&" + "amp;"), html);
        Capture zoomed = agent(
                "magnifier", temp.toString(), "--html=" + page, "--text=<i>tiny</i>",
                "--x=2", "--y=2", "--target=page"
        );
        assertEquals(0, zoomed.code, zoomed.err);
        html = Files.readString(page);
        assertFalse(html.contains("<i>tiny</i>"), html);
        assertTrue(html.contains("&" + "lt;i&" + "gt;tiny"), html);
    }

    @Test
    void every_visual_kind_logs_place_and_remove_under_its_own_entity() throws Exception {
        CompositeRuntime.resetForTests();
        Path logs = temp.resolve("logs");
        String previousLog = System.getProperty("pkb_composite_log");
        String previousDir = System.getProperty("pkb_composite_log_dir");
        System.setProperty("pkb_composite_log", "label,border,arrow,step,magnifier,spotlight");
        System.setProperty("pkb_composite_log_dir", logs.toString());
        try {
            Path page = page();
            String[][] calls = {
                    {"label", "unlabel", "label", "--text=Name"},
                    {"border", "unborder", "border", "--width=10"},
                    {"arrow", "unarrow", "arrow", "--direction=up"},
                    {"step-badge", "unstep-badge", "step", "--number=1"},
                    {"magnifier", "unmagnifier", "magnifier", "--zoom=2"},
                    {"spotlight", "unspotlight", "spotlight", "--dim=0.3"},
            };
            for (String[] call : calls) {
                Capture placed = agent(call[0], temp.toString(), "--html=" + page, "--x=5", "--y=40", "--height=10", call[3]);
                assertEquals(0, placed.code, call[0] + " " + placed.err);
                String id = placed.out.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
                Capture removed = agent(call[1], temp.toString(), "--html=" + page, "--id=" + id);
                assertEquals(0, removed.code, call[1] + " " + removed.err);
                assertTrue(removed.out.contains("\"" + call[2] + "\""), removed.out);
                String log = readLogs(logs);
                assertTrue(log.contains("INFO " + call[2] + " append id=" + id), call[2] + " place line missing:\n" + log);
                assertTrue(log.contains("INFO " + call[2] + " remove id=" + id), call[2] + " remove line missing:\n" + log);
            }

            System.setProperty("pkb_composite_log", "overlay");
            String before = readLogs(logs);
            Capture quiet = agent("label", temp.toString(), "--html=" + page, "--x=1", "--y=1", "--text=quiet");
            assertEquals(0, quiet.code, quiet.err);
            assertEquals(before, readLogs(logs), "label logged while only overlay was named");
        } finally {
            if (previousLog == null) System.clearProperty("pkb_composite_log");
            else System.setProperty("pkb_composite_log", previousLog);
            if (previousDir == null) System.clearProperty("pkb_composite_log_dir");
            else System.setProperty("pkb_composite_log_dir", previousDir);
        }
    }

    private static String readLogs(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return "";
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                assertTrue(file.getFileName().toString().matches("composite-\\d{4}-\\d{2}-\\d{2}\\.log(\\.1)?"), file.toString());
                all.append(Files.readString(file));
            }
        }
        return all.toString();
    }

    private Path page() throws Exception {
        Path page = temp.resolve("page.html");
        Files.writeString(page, "<html><body><button id=\"save\">Save</button></body></html>");
        return page;
    }

    @Test
    void covers_command_panel_open() {
        Capture badTheme = agent("panel-open", temp.toString(), "--run-id=panel", "--target=page", "--theme=neon");
        assertEquals(2, badTheme.code, badTheme.out + badTheme.err);
        assertTrue((badTheme.out + badTheme.err).contains("--theme"), badTheme.out + badTheme.err);
    }

    @Test
    void covers_command_panel_close() {
        Capture badTarget = agent("panel-close", temp.toString(), "--run-id=panel", "--target=desktop");
        assertEquals(2, badTarget.code, badTarget.out + badTarget.err);
        assertTrue((badTarget.out + badTarget.err).contains("--target"), badTarget.out + badTarget.err);
    }

    @Test
    void panelOpenCloseVerbs() {
        Capture badWidth = agent("panel-open", temp.toString(), "--run-id=panel", "--target=page", "--width=wide");
        assertEquals(2, badWidth.code, badWidth.out + badWidth.err);
        assertTrue((badWidth.out + badWidth.err).contains("--width"), badWidth.out + badWidth.err);
        Capture badTab = agent("panel-open", temp.toString(), "--run-id=panel", "--target=page", "--tab=diagram");
        assertEquals(2, badTab.code, badTab.out + badTab.err);
        Capture badTarget = agent("panel-close", temp.toString(), "--run-id=panel", "--target=native");
        assertEquals(2, badTarget.code, badTarget.out + badTarget.err);
        Capture noPage = agent("panel-open", temp.toString(), "--run-id=panel", "--target=page");
        assertEquals(1, noPage.code, noPage.out + noPage.err);
        assertTrue((noPage.out + noPage.err).contains("error: no live browser"), noPage.out + noPage.err);
        assertTrue((noPage.out + noPage.err).contains("fix: run start --url= --browser="), noPage.out + noPage.err);
        assertFalse((noPage.out + noPage.err).contains("error: error:"), noPage.out + noPage.err);
    }

    @Test
    void guideVerbsValidate() {
        Capture noSay = agent("guide", temp.toString(), "--run-id=guide", "--steps=[{\"t\":\"#email\"}]");
        assertEquals(2, noSay.code, noSay.out + noSay.err);
        assertTrue((noSay.out + noSay.err).contains("needs t (target) and say"), noSay.out + noSay.err);
        assertTrue((noSay.out + noSay.err).contains("fix:"), noSay.out + noSay.err);
        Capture noTarget = agent("guide", temp.toString(), "--run-id=guide", "--steps=[{\"say\":\"Type\"}]");
        assertEquals(2, noTarget.code, noTarget.out + noTarget.err);
        assertTrue((noTarget.out + noTarget.err).contains("fix:"), noTarget.out + noTarget.err);
        Capture badDo = agent("guide", temp.toString(), "--run-id=guide", "--steps=[{\"t\":\"#a\",\"say\":\"x\",\"do\":\"hover\"}]");
        assertEquals(2, badDo.code, badDo.out + badDo.err);
        Capture empty = agent("guide", temp.toString(), "--run-id=guide", "--steps=[]");
        assertEquals(2, empty.code, empty.out + empty.err);
    }

    @Test
    void covers_command_guide() throws Exception {
        java.nio.file.Files.writeString(temp.resolve("steps.json"), "[{\"t\":\"#email\",\"say\":\"Type your email\",\"do\":\"input\"}]");
        Capture noPage = agent("guide", temp.toString(), "--run-id=guide", "--steps=@steps.json");
        assertEquals(1, noPage.code, noPage.out + noPage.err);
        assertTrue((noPage.out + noPage.err).contains("live browser page"), noPage.out + noPage.err);
    }

    @Test
    void covers_command_guide_next() {
        Capture next = agent("guide-next", temp.toString(), "--run-id=guide");
        assertEquals(1, next.code, next.out + next.err);
        assertTrue((next.out + next.err).contains("no live browser page"), next.out + next.err);
    }

    @Test
    void covers_command_guide_back() {
        Capture back = agent("guide-back", temp.toString(), "--run-id=guide");
        assertEquals(1, back.code, back.out + back.err);
        assertTrue((back.out + back.err).contains("no live browser page"), back.out + back.err);
    }

    @Test
    void covers_command_guide_done() {
        Capture done = agent("guide-done", temp.toString(), "--run-id=guide");
        assertEquals(1, done.code, done.out + done.err);
        assertTrue((done.out + done.err).contains("no live browser page"), done.out + done.err);
    }

    @Test
    void covers_command_ask() {
        Capture noSay = agent("ask", temp.toString(), "--run-id=ask");
        assertEquals(2, noSay.code, noSay.out + noSay.err);
        assertTrue((noSay.out + noSay.err).contains("--say"), noSay.out + noSay.err);
        Capture tooMany = agent("ask", temp.toString(), "--run-id=ask", "--say=Pick", "--choices=a,b,c,d,e,f,g");
        assertEquals(2, tooMany.code, tooMany.out + tooMany.err);
    }

    @Test
    void covers_command_ui_apply() {
        Capture bad = agent("ui-apply", temp.toString(), "--run-id=ui", "--ops=[{\"c\":\"nope\"}]");
        assertEquals(2, bad.code, bad.out + bad.err);
        assertTrue((bad.out + bad.err).contains("unknown component nope") && (bad.out + bad.err).contains("fix:"), bad.out + bad.err);
        Capture noPage = agent("ui-apply", temp.toString(), "--run-id=ui", "--ops=[{\"c\":\"label\",\"t\":\"#a\",\"text\":\"A\"}]");
        assertEquals(1, noPage.code, noPage.out + noPage.err);
        assertTrue((noPage.out + noPage.err).contains("live browser page"), noPage.out + noPage.err);
    }

    @Test
    void covers_command_ui_poll() {
        Capture polled = agent("ui-poll", temp.toString(), "--run-id=ui");
        assertEquals(0, polled.code, polled.out + polled.err);
        assertTrue(polled.out.contains("\"op\":\"ui-poll\"") && polled.out.contains("\"items\""), polled.out);
        assertFalse(polled.out.contains("\"events\""), polled.out);
        assertFalse(polled.out.contains("\"chat\""), polled.out);
        Capture bad = agent("ui-poll", temp.toString(), "--run-id=ui", "--wait=soon");
        assertEquals(2, bad.code, bad.out + bad.err);
    }

    @Test
    void covers_command_ui_catalog() {
        Capture all = agent("ui-catalog", temp.toString());
        assertEquals(0, all.code, all.out + all.err);
        assertTrue(all.out.contains("\"guide\"") && all.out.contains("\"eg\""), all.out);
        Capture bad = agent("ui-catalog", temp.toString(), "--component=nope");
        assertEquals(2, bad.code, bad.out + bad.err);
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
