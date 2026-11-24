package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.AgentCursor;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.common.agentfast.LineView;
import tools.dscode.common.agentfast.SessionIds;
import tools.dscode.common.agentfast.SnapView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Envelope, short ids, one cursor, and compact line rules. No browser. */
class AgentDataFoundationTest {
    @TempDir
    Path project;

    @Test
    void envelopeCarriesStateAndCandidates() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "ambiguous");
        body.put("fix", "pass --ref or a tighter --query");
        body.put("elapsedMs", 12);
        body.put("frame", "top");
        List<Map<String, Object>> candidates = List.of(Map.of("id", "s1.h1", "role", "button", "name", "Save"));
        body.put("candidates", candidates);
        Map<String, Object> wrapped = AgentEnvelope.apply(body, 1, "act", project, "run-1");
        assertEquals(1, wrapped.get("v"));
        assertEquals(false, wrapped.get("ok"));
        assertEquals("act", wrapped.get("op"));
        assertEquals(12L, ((Number) wrapped.get("ms")).longValue());
        assertTrue(wrapped.get("state") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) wrapped.get("state");
        assertEquals("run-1", state.get("session"));
        assertEquals("top", state.get("frame"));
        assertEquals("down", state.get("browser"));
        assertEquals("none", state.get("lease"));
        assertNotNull(wrapped.get("next"));
        assertTrue(wrapped.get("trunc") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) wrapped.get("err");
        assertEquals("ambiguous", err.get("code"));
        assertEquals("pass --ref or a tighter --query", err.get("fix"));
        assertEquals(candidates, err.get("candidates"));
        Map<String, Object> again = AgentEnvelope.apply(wrapped, 1, "act", project, "run-1");
        assertEquals(wrapped.get("next"), again.get("next"));
    }

    @Test
    void shortIdFollowsTheNodeAndExpands() {
        String first = SessionIds.intern("life", 42, "ref:https://example.test/:1:42");
        String second = SessionIds.intern("life", 42, "ref:https://example.test/:2:42");
        String other = SessionIds.intern("life", 99, "ref:https://example.test/:2:99");
        assertEquals(first, second);
        assertTrue(first.matches("s\\d+\\.h\\d+"));
        assertFalse(first.equals(other));
        assertEquals("ref:https://example.test/:2:42", SessionIds.expand(first));
        assertTrue(first.length() < "ref:https://example.test/:2:42".length());
        Map<String, String> rewritten = SessionIds.rewrite(Map.of("target", first, "query", "Save"));
        assertEquals("ref:https://example.test/:2:42", rewritten.get("target"));
    }

    @Test
    void oneCursorRepeatsAcrossChatAndUiPoll() throws Exception {
        ChatLog.send(project, "mix", "run", "human", "", "hello");
        ChatLog.send(project, "mix", "run", "human", "", "again");
        PageSurfaces.recordInput(project, "mix", Map.of("type", "click", "id", "pkb-1"));
        String start = AgentCursor.pack("", 0, 0);
        Map<String, String> firstArgs = new LinkedHashMap<>();
        firstArgs.put("cursor", start);
        firstArgs.put("channel", "run");
        AgentControl.Result chat = AgentControl.execute(project, "mix", "chat-poll", firstArgs, List.of(), "a");
        assertEquals(0, chat.exit(), String.valueOf(chat.body()));
        assertEquals(1, chat.body().get("v"));
        assertEquals(2, chat.body().get("count"));
        String next = String.valueOf(chat.body().get("next"));
        assertFalse(next.isBlank());
        Map<String, String> repeat = new LinkedHashMap<>();
        repeat.put("cursor", start);
        repeat.put("channel", "run");
        AgentControl.Result same = AgentControl.execute(project, "mix", "chat-poll", repeat, List.of(), "a");
        assertEquals(2, same.body().get("count"), String.valueOf(same.body()));
        Map<String, String> later = new LinkedHashMap<>();
        later.put("cursor", next);
        later.put("channel", "run");
        AgentControl.Result empty = AgentControl.execute(project, "mix", "chat-poll", later, List.of(), "a");
        assertEquals(0, empty.body().get("count"), String.valueOf(empty.body()));

        Map<String, String> ui = new LinkedHashMap<>();
        ui.put("cursor", start);
        ui.put("channel", "run");
        AgentControl.Result polled = AgentControl.execute(project, "mix", "ui-poll", ui, List.of(), "a");
        assertEquals(0, polled.exit(), String.valueOf(polled.body()));
        assertEquals(1, polled.body().get("v"));
        assertTrue(polled.body().get("items") instanceof List<?>);
        assertFalse(((List<?>) polled.body().get("items")).isEmpty());
        Map<String, String> uiAgain = new LinkedHashMap<>();
        uiAgain.put("cursor", start);
        uiAgain.put("channel", "run");
        AgentControl.Result uiRepeat = AgentControl.execute(project, "mix", "ui-poll", uiAgain, List.of(), "a");
        assertEquals(((List<?>) polled.body().get("items")).size(), ((List<?>) uiRepeat.body().get("items")).size());
        assertFalse(polled.body().containsKey("events"));
        AgentControl.Result chatAfterUi = AgentControl.execute(project, "mix", "chat-poll", firstArgs, List.of(), "a");
        assertEquals(2, chatAfterUi.body().get("count"), String.valueOf(chatAfterUi.body()));
        PageSurfaces.recordInput(project, "mix", Map.of("type", "click", "id", "pkb-1"));
        List<String> stored = java.nio.file.Files.readAllLines(
                project.resolve(".pickleball/runs/mix/agent/overlay-events.jsonl"));
        assertEquals(1, stored.size(), stored.toString());
        assertTrue(stored.get(0).contains("sessionId"));
    }

    @Test
    void uiPollWithoutACursorDoesNotReplayHistory() throws Exception {
        PageSurfaces.recordInput(project, "now", Map.of("type", "click", "id", "pkb-old"));
        Map<String, String> fresh = new LinkedHashMap<>();
        fresh.put("channel", "run");
        AgentControl.Result polled = AgentControl.execute(project, "now", "ui-poll", fresh, List.of(), "a");
        assertEquals(0, polled.exit(), String.valueOf(polled.body()));
        assertFalse(String.valueOf(polled.body()).contains("pkb-old"), String.valueOf(polled.body()));
        Map<String, String> replay = new LinkedHashMap<>();
        replay.put("after", "0");
        replay.put("channel", "run");
        AgentControl.Result history = AgentControl.execute(project, "now", "ui-poll", replay, List.of(), "a");
        assertTrue(String.valueOf(history.body()).contains("pkb-old"), String.valueOf(history.body()));
    }

    @Test
    void chatTurnSkipsAReadMessageListsAnEarlierUnreadOneAndReturnsALaterReply() throws Exception {
        ChatCursor.arm(project, "turn", "run");
        ChatLog.Message seen = ChatLog.send(project, "turn", "run", "human", "", "seen-reply", "h-seen");
        ReadMarks.note(project, "turn", java.util.List.of("h-seen"));
        ChatLog.send(project, "turn", "run", "human", "", "fresh-reply", "h-fresh");
        Map<String, String> args = new LinkedHashMap<>();
        args.put("text", "ping");
        args.put("until", "human");
        args.put("timeout", "0");
        args.put("channel", "run");
        // An unread line from before the agent spoke is listed as earlier and is not the reply.
        AgentControl.Result early = AgentControl.execute(project, "turn", "chat-turn", args, List.of(), "a");
        assertEquals(1, early.exit(), String.valueOf(early.body()));
        assertTrue(String.valueOf(early.body().get("earlier")).contains("fresh-reply"), String.valueOf(early.body()));
        assertTrue(String.valueOf(early.body().get("earlier")).contains("earlier=true"), String.valueOf(early.body()));
        assertFalse(String.valueOf(early.body()).contains("seen-reply"), String.valueOf(early.body()));
        assertFalse(String.valueOf(early.body().get("sentId")).isBlank(), String.valueOf(early.body()));
        AgentControl.Result again = AgentControl.execute(project, "turn", "chat-turn", args, List.of(), "a");
        assertEquals(1, again.exit(), String.valueOf(again.body()));
        assertFalse(String.valueOf(again.body()).contains("fresh-reply"), String.valueOf(again.body()));
        assertEquals("h-seen", seen.pageId());
        // A human line after the agent's line is the reply.
        Map<String, String> waiting = new LinkedHashMap<>(args);
        waiting.put("timeout", "5");
        java.util.concurrent.CompletableFuture<AgentControl.Result> turn = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return AgentControl.execute(project, "turn", "chat-turn", waiting, List.of(), "a");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        Thread.sleep(600);
        ChatLog.send(project, "turn", "run", "human", "", "later-reply", "h-later");
        AgentControl.Result hit = turn.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, hit.exit(), String.valueOf(hit.body()));
        assertTrue(String.valueOf(hit.body()).contains("later-reply"), String.valueOf(hit.body()));
        assertEquals(1, hit.body().get("count"), String.valueOf(hit.body()));

        ChatCursor.arm(project, "poll", "run");
        ChatLog.send(project, "poll", "run", "human", "", "still-polled", "h-polled");
        ReadMarks.note(project, "poll", java.util.List.of("h-polled"));
        Map<String, String> peek = new LinkedHashMap<>();
        peek.put("channel", "run");
        peek.put("from", "human");
        AgentControl.Result polled = AgentControl.execute(project, "poll", "chat-poll", peek, List.of(), "a");
        assertEquals(0, polled.exit(), String.valueOf(polled.body()));
        assertTrue(String.valueOf(polled.body()).contains("still-polled"), String.valueOf(polled.body()));
    }

    @Test
    void linesAreSmallerAndEscapeAndStayUnderTheCap() {
        assertEquals("Save", LineView.token("Save"));
        assertEquals("\"a\\nb\"", LineView.token("a\nb"));
        assertTrue(LineView.token("caf\u00e9").startsWith("\""));
        assertTrue(LineView.token("caf\u00e9").contains("\\u00e9"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", "button");
            row.put("name", "Row " + index);
            row.put("visible", true);
            rows.add(row);
        }
        List<Map<String, Object>> filtered = SnapView.filter(rows, Map.of("where", "name~Row 1", "limit", "5"));
        assertFalse(filtered.isEmpty());
        assertTrue(filtered.size() <= 5);
        List<String> huge = new ArrayList<>();
        for (int index = 0; index < 400; index++) huge.add("x".repeat(200));
        SnapView.limitLines(huge, 200, 160, SnapView.REPLY_CAP);
        assertTrue(huge.size() <= 200);
        assertTrue(SnapView.bytes(huge) <= SnapView.REPLY_CAP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", "snap");
        body.put("elapsedMs", 3);
        body.put("lines", List.of("- button \"Save\" [ref=ref:v:1] id=s1.h1 visible=true enabled=true interactive=true frame=top"));
        body.put("sentence", "snap ok");
        String json = AgentEnvelope.ensure(write(AgentEnvelope.apply(body, 0, "snap", project, "s")), "snap", 3, project, "s");
        String lines = AgentEnvelope.renderLines(json);
        assertTrue(lines.length() < json.length(), lines.length() + " vs " + json.length());
        assertTrue(lines.contains("id=s1.h1"));
        assertTrue(json.contains("\"v\""));
        assertNull(AgentEnvelope.apply(body, 0, "snap", project, "s").get("err"));
    }

    private static String write(Map<String, Object> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
