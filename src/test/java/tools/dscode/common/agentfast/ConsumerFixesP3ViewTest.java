package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AgentControl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P3 reply shape: visibility, fields, desktop targets, lean placements, and attached targets. */
class ConsumerFixesP3ViewTest {
    @TempDir
    Path temp;

    @Test
    void hiddenInputIsNotVisible() {
        DomNode input = element("input");
        input.attributes.put("type", "hidden");
        input.attributes.put("hidden", "");
        assertTrue(TargetVerb.hiddenInput(input));
        assertFalse(TargetVerb.layoutVisible(input));
    }

    @Test
    void humanFindKeepsACollapsedRow() {
        Map<String, Object> collapsed = new LinkedHashMap<>();
        collapsed.put("visible", false);
        collapsed.put("collapsed", true);
        assertTrue(TargetVerb.humanKeeps(collapsed));
        Map<String, Object> hidden = Map.of("visible", false);
        assertFalse(TargetVerb.humanKeeps(hidden));
    }

    @Test
    void collapsedAncestorMarksTheRow() {
        DomNode panel = element("div");
        panel.attributes.put("aria-expanded", "false");
        panel.states.add("collapsed");
        DomNode button = element("button");
        button.parent = panel;
        panel.children.add(button);
        assertTrue(TargetVerb.collapsedRow(button));
    }

    @Test
    void unknownFieldFailsAndIdValuePairsDoNot() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SnapView.requireFields(List.of("zzz"), List.of(Map.of("id", "s1"))));
        assertTrue(failure.getMessage().contains("unknown field \"zzz\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("known:"), failure.getMessage());
        SnapView.requireFields(List.of("id"), List.of());
        SnapView.requireFields(List.of("email=ada@b.c"), List.of());
        Map<String, Object> row = Map.of("id", "s1", "role", "button");
        List<Map<String, Object>> filtered = SnapView.filter(List.of(row), Map.of("fields", "id,role"));
        assertEquals("s1", filtered.get(0).get("id"));
    }

    @Test
    void desktopAppEqualsMatchesTheHyphenatedName() {
        TargetSelector.Parsed parsed = TargetSelector.parse("desktop:app=pickleball-desktop-test");
        assertEquals("desktop", parsed.kind());
        assertEquals("pickleball-desktop-test", parsed.app());
        assertEquals("", parsed.window());
        assertEquals("Notes", TargetSelector.parse("desktop:app~Notes").app());
    }

    @Test
    void desktopReplyStateFrameKeepsTheTitleOnTheBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", "find");
        body.put("kind", "desktop");
        body.put("used", "desktop");
        body.put("frame", "Notes");
        Map<String, Object> applied = AgentEnvelope.apply(body, 0, "find", temp, "p3");
        assertEquals("Notes", applied.get("frame"));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) applied.get("state");
        assertEquals("desktop:Notes", state.get("frame"));
    }

    @Test
    void attachedCollaborationTargetIsRefused() {
        assertEquals("unsupported on attached target",
                AgentControl.attachedRefusal("overlay", "run", Map.of("target", "desktop:app~Notes")));
        assertEquals("unsupported on attached target",
                AgentControl.attachedRefusal("chat-send", "run", Map.of("target", "browser:cdp=ws://127.0.0.1/devtools/page/1")));
        assertEquals(null, AgentControl.attachedRefusal("overlay", "run", Map.of("target", "browser:auto")));
        assertEquals(null, AgentControl.attachedRefusal("panel-open", "run", Map.of("target", "page")));
        assertEquals(null, AgentControl.attachedRefusal("step-badge", "run-1", Map.of("target", "browser:run-1")));
        assertEquals(null, AgentControl.attachedRefusal("highlight", "run", Map.of("target", "browser:cdp=ws://127.0.0.1/devtools/page/1")));
    }

    @Test
    void leanPlacementDropsGuidanceUnlessVerbose() {
        Map<String, Object> placed = new LinkedHashMap<>();
        placed.put("id", "badge-1");
        placed.put("kind", "step");
        placed.put("visible", true);
        placed.put("x", 4);
        placed.put("y", 8);
        placed.put("width", 20);
        placed.put("height", 20);
        placed.put("sha256", "abc");
        placed.put("summary", "sum");
        placed.put("guidance", "look");
        placed.put("overlaps", List.of());
        AgentControl.leanPlacement(placed, Map.of());
        assertFalse(placed.containsKey("sha256"));
        assertFalse(placed.containsKey("summary"));
        assertFalse(placed.containsKey("guidance"));
        assertEquals("badge-1", placed.get("id"));
        assertTrue(placed.get("rect") instanceof Map);
        Map<String, Object> verbose = new LinkedHashMap<>(Map.of("sha256", "abc", "summary", "sum", "guidance", "look"));
        AgentControl.leanPlacement(verbose, Map.of("verbose", "true"));
        assertEquals("abc", verbose.get("sha256"));
    }

    @Test
    void actWithoutAQueryNamesOnlyActFlags() {
        TargetVerb.Outcome outcome = TargetVerb.run(temp, "p3", "act", Map.of(
                "target", "desktop:app=pickleball-desktop-test",
                "kind", "type"));
        assertEquals(1, outcome.exit());
        String error = String.valueOf(outcome.body().get("error"));
        assertEquals("act requires --query or --ref", error);
        assertFalse(error.contains("--text"), error);
        assertFalse(error.contains("--pipeline"), error);
        assertFalse(error.contains("--selector"), error);
        assertFalse(error.contains("query requires"), error);
    }

    @Test
    void placementRuntimeAvoidsPageControls() throws Exception {
        String runtime = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        assertTrue(runtime.contains("function controlBoxes()"), runtime.substring(0, 80));
        assertTrue(runtime.contains("hitsControl"));
        assertTrue(runtime.contains("ResizeObserver"));
        assertTrue(runtime.contains("MutationObserver"));
        assertTrue(runtime.contains("window.__pkbUi"));
    }

    private static DomNode element(String tag) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        node.localName = tag;
        node.nodeName = tag.toUpperCase();
        node.displayed = true;
        return node;
    }
}
