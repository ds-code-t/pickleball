package tools.dscode.common.agentfast;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lean replies, view options, short ids, and per-channel cursors. No browser. */
class LeanReplyTest {
    @TempDir
    Path project;

    @Test
    void envelopeSendsAListOnce() {
        List<Map<String, Object>> rows = List.of(Map.of("id", "p1", "visible", true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("elapsedMs", 1);
        body.put("placements", rows);
        body.put("lines", rows);
        body.put("ops", List.of(Map.of("i", 1)));
        Map<String, Object> wrapped = AgentEnvelope.apply(body, 0, "ui-verify", project, "run");
        assertEquals(rows, wrapped.get("items"));
        assertFalse(wrapped.containsKey("lines"));
        assertFalse(wrapped.containsKey("placements"));
        assertEquals(List.of(Map.of("i", 1)), wrapped.get("ops"));
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("ok", true);
        apply.put("elapsedMs", 1);
        apply.put("ops", rows);
        Map<String, Object> applied = AgentEnvelope.apply(apply, 0, "ui-apply", project, "run");
        assertEquals(rows, applied.get("items"));
        assertFalse(applied.containsKey("ops"));
        assertNull(applied.get("err"));
    }

    @Test
    void snapLineUnescapesAQuotedNameAndKeepsAShortRef() {
        List<String> lines = TargetVerb.snapLines(List.of(Map.of(
                "role", "button",
                "name", "Say \\\"Save\\\"",
                "ref", "ref:1:1",
                "id", "s1.h4"
        )), Map.of());
        String line = lines.get(1);
        assertTrue(line.contains("Say \"Save\""), line);
        assertTrue(line.contains("[ref=ref:1:1]"), line);
        assertTrue(line.contains("id=s1.h4"), line);
    }

    @Test
    void fieldsAndBudgetAreHonored() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", "button");
            row.put("name", "Row " + index);
            row.put("ref", "ref:https://example.test/very/long/" + index);
            row.put("id", "s1.h" + index);
            rows.add(row);
        }
        List<Map<String, Object>> projected = SnapView.filter(rows, Map.of("fields", "id,role"));
        assertEquals(List.of("id", "role"), new ArrayList<>(projected.get(0).keySet()));
        assertFalse(projected.get(0).containsKey("ref"));
        Map<String, String> lineArgs = Map.of("fields", "id");
        List<String> lines = TargetVerb.snapLines(rows, lineArgs);
        assertTrue(lines.get(1).contains("id=s1.h0"), lines.toString());
        assertFalse(lines.get(1).contains("ref="), lines.toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("elapsedMs", 1);
        body.put("lines", lines);
        body.put("replyBudget", 480);
        Map<String, Object> wrapped = AgentEnvelope.apply(body, 0, "snap", project, "run");
        String json = write(wrapped);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= SnapView.REPLY_CAP, json);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 480, json.getBytes(StandardCharsets.UTF_8).length + " " + json);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) wrapped.get("trunc")).get("on"));
        assertTrue(((List<?>) wrapped.get("items")).size() < lines.size());
    }

    @Test
    void shortRefsDropEmbeddedPagesAndFramesStayStable() {
        String page = "data:text/html," + "x".repeat(500);
        String ref = TargetVerb.publishedRef(page, 7);
        assertTrue(ref.startsWith("ref:d"), ref);
        assertFalse(ref.contains("data:"), ref);
        assertTrue(ref.endsWith(":7"), ref);
        assertEquals("ref:desktop:0:7", TargetVerb.publishedRef("desktop:0", 7));
        DomNode node = new DomNode();
        node.framePath = "/html/body/iframe[1]";
        assertEquals("top", TargetVerb.stableFrame(node, "main"));
        assertEquals("ABC", TargetVerb.stableFrame(node, "ABC"));
        assertEquals("d" + Integer.toUnsignedString(page.hashCode(), 36), TargetVerb.shownVersion(page));
        assertEquals("desktop:0 -> desktop:1", TargetVerb.shownVersion("desktop:0 -> desktop:1"));
    }

    @Test
    void shortIdAndVisibleLabelResolveAField() {
        DomNode root = new DomNode();
        root.nodeType = 1;
        root.localName = "form";
        DomNode input = new DomNode();
        input.nodeType = 1;
        input.localName = "input";
        input.backendNodeId = 42;
        input.attributes.put("placeholder", "City");
        input.parent = root;
        root.children.add(input);
        assertSame(input, TargetVerb.fieldNode(root, "city:"));
        assertSame(input, TargetVerb.fieldNode(root, "CITY"));
        String id = SessionIds.intern("fields", 42, "ref:desktop:0:42");
        assertSame(input, TargetVerb.fieldNode(root, id));
        DomNode button = new DomNode();
        button.role = "button";
        button.accessibleName = "Save";
        DomNode link = new DomNode();
        link.role = "link";
        link.accessibleName = "Home";
        assertSame(button, TargetVerb.pickHit(List.of(link, button), "role=button"));
        assertSame(button, TargetVerb.pickHit(List.of(link, button), "button"));
        assertNull(TargetVerb.pickHit(List.of(button, button), "button"));
    }

    @Test
    void channelsStaySeparate() {
        String uiToken = AgentCursor.pack("m9", 0, 3, "ui");
        Map<String, String> foreign = new LinkedHashMap<>();
        foreign.put("cursor", uiToken);
        AgentCursor.absorb("chat-poll", foreign);
        assertEquals("true", foreign.get("peek"));
        assertFalse(foreign.containsKey("after"));
        AgentCursor.noteUi("mix", 4);
        Map<String, String> back = new LinkedHashMap<>();
        back.put("cursor", AgentCursor.pack("m2", 0, 0, "chat"));
        back.put("run-id", "mix");
        AgentCursor.absorb("ui-poll", back);
        assertEquals("4", back.get("after"));
        String again = AgentCursor.pack("", 0, 2);
        Map<String, String> same = new LinkedHashMap<>();
        same.put("cursor", again);
        AgentCursor.absorb("ui-poll", same);
        assertEquals("2", same.get("after"));
        AgentCursor.absorb("ui-poll", same);
        assertEquals("2", same.get("after"));
    }

    @Test
    void pushRowsCarryASessionId() {
        PushChannel.resetForTests();
        try {
            PushChannel.bind(project, "sess-9");
            assertTrue(PushChannel.accept(project, Map.of("type", "click", "source", "human", "text", "go")));
            String line = tools.dscode.control.protocol.AgentEvents.lines(project).isEmpty()
                    ? Files.readString(tools.dscode.control.protocol.AgentEvents.file(project))
                    : "";
            String raw = Files.readString(tools.dscode.control.protocol.AgentEvents.file(project));
            assertTrue(raw.contains("\"sessionId\":\"sess-9\""), raw);
            assertFalse(line.isEmpty() && raw.isBlank());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            PushChannel.resetForTests();
        }
    }

    private static String write(Map<String, Object> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
