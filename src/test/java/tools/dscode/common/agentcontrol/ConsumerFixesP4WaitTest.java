package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4 wait-any: each condition, the ignores, and the timeout. No browser. */
class ConsumerFixesP4WaitTest {
    @TempDir
    Path project;

    @Test
    void missingConditionsAndBadFlagsExitTwo() {
        AgentControl.Result none = run("", "");
        assertEquals(2, none.exit());
        assertEquals("wait-any needs --answer, --chat, or --watch", none.body().get("error"));
        assertEquals("control", none.body().get("tier"));
        assertFalse(none.body().containsKey("caller"));

        AgentControl.Result agent = run("", "agent-9");
        assertEquals("agent-9", agent.body().get("caller"));

        AgentControl.Result chat = run("p4", "", Map.of("chat", "both"));
        assertEquals(2, chat.exit());
        assertEquals("wait-any --chat is human, agent, or any", chat.body().get("error"));

        AgentControl.Result watch = run("p4", "", Map.of("watch", "s1.h1"));
        assertEquals(2, watch.exit());
        assertEquals("wait-any --watch is <short id>:click", watch.body().get("error"));
    }

    @Test
    @Timeout(10)
    void anAnswerAlreadyStoredDoesNotFire() throws Exception {
        event("answer", "q1", "choice", "Yes");
        AgentControl.Result result = run("p4", "", Map.of("answer", "q1", "timeout", "150"));
        assertEquals(1, result.exit(), result.body().toString());
        assertEquals("timeout", result.body().get("error"));
        assertFalse(result.body().containsKey("code"), result.body().toString());
    }

    @Test
    @Timeout(10)
    void answerFiresAndADifferentIdDoesNot() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> waiting = submit(pool, Map.of("answer", "q1", "timeout", "3000"));
            Thread.sleep(250);
            event("answer", "other", "choice", "No");
            Thread.sleep(150);
            assertFalse(waiting.isDone(), "a different question must not fire");
            event("answer", "q1", "choice", "Yes");
            AgentControl.Result result = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(0, result.exit(), result.body().toString());
            assertEquals("answer", result.body().get("fired"));
            assertEquals("Yes", result.body().get("choice"));
            assertEquals("Yes", result.body().get("value"));
            assertEquals("q1", result.body().get("id"));
            assertEquals("control", result.body().get("tier"));
            assertEquals("p4", result.body().get("caller"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void chatHumanIgnoresAgentAndSystem() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> waiting = submit(pool, Map.of("chat", "human", "timeout", "3000"));
            Thread.sleep(250);
            ChatLog.send(project, "p4", "run", "agent", "agent", "from the agent");
            ChatLog.send(project, "p4", "run", "system", "", "overlay input click s1.h1");
            Thread.sleep(150);
            assertFalse(waiting.isDone(), "agent and system lines are not a human chat");
            ChatLog.send(project, "p4", "run", "human", "helper", "from the helper");
            AgentControl.Result result = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(0, result.exit(), result.body().toString());
            assertEquals("chat", result.body().get("fired"));
            assertEquals("human", result.body().get("source"));
            assertEquals("from the helper", result.body().get("text"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void chatAgentFiresOnAnAgentLine() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> waiting = submit(pool, Map.of("chat", "agent", "timeout", "3000"));
            Thread.sleep(250);
            ChatLog.send(project, "p4", "run", "agent", "agent", "ready");
            AgentControl.Result result = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(0, result.exit(), result.body().toString());
            assertEquals("chat", result.body().get("fired"));
            assertEquals("agent", result.body().get("source"));
            assertEquals("ready", result.body().get("text"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void chatAnyFiresOnAHumanLine() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> waiting = submit(pool, Map.of("chat", "any", "timeout", "3000"));
            Thread.sleep(250);
            ChatLog.send(project, "p4", "run", "system", "", "not a person");
            Thread.sleep(250);
            assertFalse(waiting.isDone());
            ChatLog.send(project, "p4", "run", "human", "helper", "here");
            AgentControl.Result result = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(0, result.exit(), result.body().toString());
            assertEquals("chat", result.body().get("fired"));
            assertEquals("human", result.body().get("source"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void watchFiresOnThatClickAndIgnoresAnotherId() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> waiting = submit(pool, Map.of("watch", "s1.h4:click", "chat", "human", "timeout", "3000"));
            Thread.sleep(250);
            event("click", "s1.h9", "id", "s1.h9");
            Thread.sleep(150);
            assertFalse(waiting.isDone(), "a click for another id must not fire");
            event("click", "s1.h4", "id", "s1.h4");
            AgentControl.Result result = waiting.get(3, TimeUnit.SECONDS);
            assertEquals(0, result.exit(), result.body().toString());
            assertEquals("watch", result.body().get("fired"));
            assertEquals("s1.h4", result.body().get("id"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(10)
    void theEarliestStampWinsAndATiePrefersAnswer() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> earliest = submit(pool, Map.of(
                    "answer", "q1", "watch", "s1.h4:click", "timeout", "3000"));
            Thread.sleep(250);
            append("overlay-events.jsonl", "{\"type\":\"click\",\"id\":\"s1.h4\",\"at\":\"2019-01-01T00:00:00Z\"}");
            append("overlay-events.jsonl", "{\"type\":\"answer\",\"id\":\"q1\",\"choice\":\"Yes\",\"at\":\"2020-01-01T00:00:00Z\"}");
            AgentControl.Result first = earliest.get(3, TimeUnit.SECONDS);
            assertEquals("watch", first.body().get("fired"), first.body().toString());
        } finally {
            pool.shutdownNow();
        }

        ExecutorService tiePool = Executors.newSingleThreadExecutor();
        try {
            Future<AgentControl.Result> tie = submit(tiePool, Map.of(
                    "answer", "q2", "chat", "human", "timeout", "3000"));
            Thread.sleep(250);
            append("overlay-events.jsonl", "{\"type\":\"answer\",\"id\":\"q2\",\"choice\":\"Yes\",\"at\":\"2020-06-01T00:00:00Z\"}");
            appendChat("{\"id\":\"9\",\"at\":\"2020-06-01T00:00:00Z\",\"source\":\"human\",\"agent\":\"helper\",\"channel\":\"run\",\"text\":\"same time\"}");
            AgentControl.Result same = tie.get(3, TimeUnit.SECONDS);
            assertEquals(0, same.exit(), same.body().toString());
            assertEquals("answer", same.body().get("fired"), same.body().toString());
            assertEquals("Yes", same.body().get("choice"));
        } finally {
            tiePool.shutdownNow();
        }
    }

    private AgentControl.Result run(String runId, String agentId) {
        return run(runId, agentId, Map.of());
    }

    private AgentControl.Result run(String runId, String agentId, Map<String, String> args) {
        return AgentControl.execute(project, runId, "wait-any", new LinkedHashMap<>(args), List.of(), agentId);
    }

    private Future<AgentControl.Result> submit(ExecutorService pool, Map<String, String> args) {
        Map<String, String> copy = new LinkedHashMap<>(args);
        return pool.submit(() -> AgentControl.execute(project, "p4", "wait-any", copy, List.of(), ""));
    }

    private void event(String type, String id, String extraKey, String extraValue) throws Exception {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("id", id);
        event.put(extraKey, extraValue);
        PageSurfaces.recordInput(project, "p4", event);
    }

    private void append(String name, String json) throws Exception {
        Path file = AgentStore.root(project, "p4").resolve(name);
        AgentStore.appendCapped(file, json);
        tools.dscode.common.agentfast.PushWake.signal();
    }

    private void appendChat(String json) throws Exception {
        Path file = AgentStore.root(project, "p4").resolve("chat").resolve("run.jsonl");
        Files.createDirectories(file.getParent());
        AgentStore.appendCapped(file, json);
        tools.dscode.common.agentfast.PushWake.signal();
    }
}
