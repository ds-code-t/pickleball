package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AgentControl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSurfaceRouterTest {
    @TempDir
    Path project;

    @AfterEach
    void clear() {
        System.clearProperty("pkb_agent_popup");
        System.clearProperty("pkb_agent_session");
    }

    @Test
    void routingTable() throws Exception {
        System.setProperty("pkb_agent_popup", "off");
        Map<String, String> html = Map.of("text", "<p>x</p>");
        assertTrue(AgentSurfaceRouter.route(project, "r", "html-write", html, "a").isEmpty());
        AgentControl.Result off = AgentControl.execute(project, "r", "html-write", html, java.util.List.of(), "a");
        assertFalse(String.valueOf(off.body()).contains("target=popup") && "popup".equals(off.body().get("target")));

        System.setProperty("pkb_agent_popup", "auto");
        assertTrue(AgentSurfaceRouter.route(project, "r", "html-write", Map.of("text", "<p>x</p>", "html", "page.html"), "a").isEmpty());
        assertTrue(AgentSurfaceRouter.route(project, "r", "html-write", Map.of("text", "<p>x</p>", "target", "page"), "a").isEmpty());

        Optional<AgentControl.Result> desktop = AgentSurfaceRouter.route(project, "r", "html-write", html, "a");
        assertTrue(desktop.isPresent());
        assertEquals("popup", desktop.get().body().get("target"));

        System.setProperty("pkb_agent_session", "browser");
        assertTrue(AgentSurfaceRouter.route(project, "r", "html-write", html, "a").isEmpty());
        System.setProperty("pkb_agent_popup", "always");
        Optional<AgentControl.Result> unanchored = AgentSurfaceRouter.route(project, "r", "overlay", Map.of("text", "<b>n</b>"), "a");
        assertPopupOrNoChromium(unanchored.orElseThrow());
        assertTrue(AgentSurfaceRouter.route(project, "r", "html-write", Map.of("text", "<p>x</p>", "handle", "save"), "a").isEmpty());
        assertTrue(AgentSurfaceRouter.route(project, "r", "highlight", Map.of("handle", "save"), "a").isEmpty());
        Optional<AgentControl.Result> chat = AgentSurfaceRouter.route(project, "r", "chat-send", Map.of("text", "hi", "channel", "run"), "agent");
        assertPopupOrNoChromium(chat.orElseThrow());
        assertTrue(Files.readString(project.resolve(".pickleball/runs/r/agent/chat/run.jsonl")).contains("hi"));

        System.clearProperty("pkb_agent_session");
        System.setProperty("pkb_agent_popup", "auto");
        Optional<AgentControl.Result> explicit = AgentSurfaceRouter.route(project, "r", "html-write", Map.of("text", "<p>y</p>", "target", "popup"), "a");
        assertPopupOrNoChromium(explicit.orElseThrow());
        Optional<AgentControl.Result> nativeBox = AgentSurfaceRouter.route(project, "r", "highlight", Map.of(
                "target", "native", "x", "100", "y", "100", "width", "200", "height", "40", "text", "cap"), "a");
        assertEquals("native", nativeBox.orElseThrow().body().get("target"));
        if (Boolean.FALSE.equals(nativeBox.orElseThrow().body().get("drawn"))) {
            assertEquals("headless", nativeBox.orElseThrow().body().get("reason"));
        }
        assertTrue(Files.readString(project.resolve(".pickleball/runs/r/agent/popup/surfaces.jsonl")).contains("cap"));
    }

    private static void assertPopupOrNoChromium(AgentControl.Result result) {
        Object target = result.body().get("target");
        if ("none".equals(target)) {
            assertEquals("no-chromium", result.body().get("fallback"));
            return;
        }
        assertEquals("popup", target);
    }
}
