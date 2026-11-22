package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.AgentEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Person-facing feedback, ask value, guide, card, and push contracts that do not need Chrome. */
class PersonFeedbackTest {
    @Test
    void acceptedChoiceIsOnlyTheClickedLabel() {
        List<String> choices = List.of("Alpha", "Beta");
        assertEquals("Alpha", AgentControl.acceptedChoice(
                Map.of("type", "answer", "id", "q1", "choice", "Alpha", "value", "Alpha"), "q1", choices));
        assertNull(AgentControl.acceptedChoice(
                Map.of("type", "answer", "id", "q1", "value", "Alpha Beta"), "q1", choices));
        assertNull(AgentControl.acceptedChoice(
                Map.of("type", "answer", "id", "other", "choice", "Alpha"), "q1", choices));
        assertNull(AgentControl.acceptedChoice(
                Map.of("type", "click", "id", "q1", "choice", "Alpha"), "q1", choices));
    }

    @Test
    void aStoredAnswerIsNotMissedAndAJoinedLabelIsIgnored() throws Exception {
        Path project = Files.createTempDirectory("pkb-answer");
        String early = Instant.now().minusSeconds(5).toString();
        String late = Instant.now().toString();
        String joined = "{\"type\":\"answer\",\"id\":\"q1\",\"choice\":\"Alpha Beta\",\"value\":\"Alpha Beta\",\"at\":\"" + late + "\"}\n";
        String picked = "{\"type\":\"answer\",\"id\":\"q1\",\"choice\":\"Alpha\",\"value\":\"Alpha\",\"at\":\"" + early + "\"}\n";
        Files.createDirectories(project.resolve(".pickleball"));
        Files.writeString(AgentEvents.file(project), joined + picked);
        Map<String, String> found = AnswerRecovery.find(project, "run-1", System.currentTimeMillis() - 60_000, "q1", List.of("Alpha", "Beta"));
        assertEquals("Alpha", found.get("value"));
        assertEquals("Alpha", found.get("choice"));
        Files.writeString(AgentEvents.file(project), joined);
        assertNull(AnswerRecovery.find(project, "run-1", System.currentTimeMillis() - 60_000, "q1", List.of("Alpha", "Beta")));
    }

    @Test
    void anAnswerAlreadyInTheOverlayLogIsRecovered() throws Exception {
        Path project = Files.createTempDirectory("pkb-overlay-answer");
        Path log = AgentStore.root(project, "run-9").resolve("overlay-events.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "{\"type\":\"answer\",\"id\":\"q9\",\"choice\":\"Beta\",\"value\":\"Beta\",\"at\":\""
                + Instant.now() + "\"}\n");
        Map<String, String> found = AnswerRecovery.find(project, "run-9", System.currentTimeMillis() - 60_000, "q9", List.of("Alpha", "Beta"));
        assertEquals("Beta", found.get("value"));
    }

    @Test
    void pageSourcesCarryFeedbackGuidesAndCards() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        String css = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/panel.css"));
        String events = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/surface-events.js"));
        String java = Files.readString(Path.of("src/main/java/tools/dscode/common/agentcontrol/AgentControl.java"));
        String adapter = Files.readString(Path.of("src/main/java/tools/dscode/browser/CdpAdapter.java"));
        assertTrue(js.contains("VERSION = '3.0.0-editor'"));
        assertTrue(js.contains("TYPE_SETTLE_MS = 700"));
        assertTrue(js.contains("unresolved"));
        assertTrue(js.contains("function parkCards()"));
        assertTrue(js.contains("data-choice"));
        assertTrue(js.contains("You chose "));
        assertTrue(js.contains("function byDomId"));
        assertTrue(js.contains("getElementById"));
        assertTrue(js.contains("function panelUnread()"));
        assertTrue(js.contains("Chat, "));
        assertTrue(js.contains("installId"));
        assertTrue(css.contains("[data-state=\"sending\"]"));
        assertTrue(css.contains("[data-state=\"delivered\"]"));
        assertTrue(css.contains("[data-state=\"read\"]"));
        assertTrue(css.contains(".pkb-badge:not(:empty)"));
        assertFalse(events.contains("n.innerText"));
        assertTrue(events.contains("data-choice"));
        assertTrue(events.contains("__pkbPush"));
        assertTrue(java.contains("type=\\\"checkbox\\\""));
        assertTrue(java.contains("data-pkb-check"));
        assertTrue(java.contains("AnswerRecovery.find"));
        assertTrue(java.contains("PageSurfaces.markRead"));
        assertTrue(adapter.contains("scheduleRebind"));
        assertTrue(adapter.contains("Runtime.enable"));
    }
}
