package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import tools.dscode.common.composite.CompositeLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-run chat channels. One JSON line per message. Ids continue from the stored log. */
final class ChatLog {
    private ChatLog() {
    }

    record Message(String id, String at, String source, String agent, String channel, String text) {
    }

    static Message send(Path project, String runId, String channel, String source, String agent, String text) throws IOException {
        String useChannel = channel == null || channel.isBlank() ? "run" : channel;
        String useSource = normalizeSource(source);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("missing text");
        synchronized (ChatLog.class) {
            long next = maxId(project, runId, useChannel) + 1;
            Message message = new Message(
                    Long.toString(next),
                    AgentStore.now(),
                    useSource,
                    agent == null ? "" : agent,
                    useChannel,
                    text
            );
            AgentStore.appendCapped(file(project, runId, useChannel), AgentStore.JSON.writeValueAsString(message));
            CompositeLog.info("chat", "send source=" + useSource + " channel=" + useChannel + " agent=" + message.agent() + " id=" + message.id());
            return message;
        }
    }

    static List<Message> history(Path project, String runId, String channel, int limit) throws IOException {
        String useChannel = channel == null || channel.isBlank() ? "run" : channel;
        List<Message> all = read(project, runId, useChannel);
        CompositeLog.info("chat", "receive channel=" + useChannel + " count=" + all.size());
        if (limit > 0 && all.size() > limit) return List.copyOf(all.subList(all.size() - limit, all.size()));
        return all;
    }

    static List<Message> poll(Path project, String runId, String channel, String after) throws IOException {
        List<Message> all = history(project, runId, channel, 0);
        if (after == null || after.isBlank()) return all;
        List<Message> newer = new ArrayList<>();
        boolean seen = false;
        for (Message message : all) {
            if (seen) newer.add(message);
            else if (message.id().equals(after)) seen = true;
        }
        return seen ? newer : all;
    }

    private static List<Message> read(Path project, String runId, String channel) throws IOException {
        List<Message> messages = new ArrayList<>();
        for (String line : AgentStore.readGroup(file(project, runId, channel))) {
            JsonNode node = AgentStore.JSON.readTree(line);
            messages.add(new Message(
                    node.path("id").asText(""),
                    node.path("at").asText(""),
                    node.path("source").asText("agent"),
                    node.path("agent").asText(""),
                    node.path("channel").asText(channel),
                    node.path("text").asText("")
            ));
        }
        return messages;
    }

    private static long maxId(Path project, String runId, String channel) throws IOException {
        long max = 0L;
        for (String line : AgentStore.readGroup(file(project, runId, channel))) {
            JsonNode node = AgentStore.JSON.readTree(line);
            max = Math.max(max, longOrZero(node.path("id").asText("")));
        }
        return max;
    }

    private static long longOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) return "agent";
        String token = source.trim().toLowerCase(Locale.ROOT);
        if (!token.equals("agent") && !token.equals("human") && !token.equals("system")) {
            throw new IllegalArgumentException("source is agent, human, or system");
        }
        return token;
    }

    private static Path file(Path project, String runId, String channel) {
        String safe = channel.replaceAll("[^A-Za-z0-9._-]", "_");
        return AgentStore.root(project, runId).resolve("chat").resolve(safe + ".jsonl");
    }

    static Map<String, Object> toMap(Message message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", message.id());
        map.put("at", message.at());
        map.put("source", message.source());
        map.put("agent", message.agent());
        map.put("channel", message.channel());
        map.put("text", message.text());
        return map;
    }
}
