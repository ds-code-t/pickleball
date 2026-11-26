package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed ask cards. Answers that are not in the listed options are rejected. */
public final class AskCards {
    private AskCards() {
    }

    public static Map<String, Object> open(Path project, String session, String id, String type, String prompt, List<String> options) throws IOException {
        String useSession = sessionOf(project, session);
        List<ObjectNode> cards = read(project, useSession);
        String useId = id == null || id.isBlank() ? "q" + (cards.size() + 1) : id.trim();
        ObjectNode card = AgentStore.JSON.createObjectNode();
        card.put("id", useId);
        card.put("type", type);
        card.put("prompt", prompt == null ? "" : prompt);
        ArrayNode listed = card.putArray("options");
        if (options != null) for (String option : options) listed.add(option);
        card.putNull("value");
        cards.add(card);
        write(project, useSession, cards);
        return toMap(card, useSession);
    }

    /** Stores a listed choice. An unknown choice throws {@code error:} / {@code fix:}. */
    public static void answer(Path project, String session, String id, String value) throws IOException {
        String useSession = sessionOf(project, session);
        String useValue = value == null ? "" : value.trim();
        if (useValue.isBlank()) {
            throw new IllegalArgumentException("error: missing answer\nfix: pass a value from --options");
        }
        List<ObjectNode> cards = read(project, useSession);
        ObjectNode card = find(cards, id);
        if (card == null) {
            throw new IllegalArgumentException("error: no ask card " + id + "\nfix: ask --type=choice --id=" + id);
        }
        String type = card.path("type").asText("");
        if ("choice".equals(type) || "confirm".equals(type)) {
            boolean listed = false;
            for (JsonNode option : card.path("options")) {
                if (useValue.equals(option.asText())) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                throw new IllegalArgumentException("error: option is not listed\nfix: use a value from --options");
            }
        }
        card.put("value", useValue);
        write(project, useSession, cards);
    }

    /** The newest answered card, or null when none has a value yet. */
    public static Map<String, Object> answered(Path project, String session) {
        String useSession = sessionOf(project, session);
        List<ObjectNode> cards;
        try {
            cards = read(project, useSession);
        } catch (IOException failure) {
            return null;
        }
        ObjectNode latest = null;
        for (ObjectNode card : cards) {
            if (!card.path("value").isNull() && !card.path("value").asText("").isBlank()) latest = card;
        }
        return latest == null ? null : toMap(latest, useSession);
    }

    private static String sessionOf(Path project, String session) {
        if (session != null && !session.isBlank()) return session.trim();
        String current = SpineSession.current(project);
        return current == null || current.isBlank() ? "session" : current;
    }

    private static ObjectNode find(List<ObjectNode> cards, String id) {
        if (id == null || id.isBlank()) return cards.isEmpty() ? null : cards.get(cards.size() - 1);
        for (ObjectNode card : cards) if (id.equals(card.path("id").asText())) return card;
        return null;
    }

    private static Map<String, Object> toMap(ObjectNode card, String session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", card.path("id").asText(""));
        map.put("type", card.path("type").asText(""));
        map.put("prompt", card.path("prompt").asText(""));
        map.put("session", session);
        List<String> options = new ArrayList<>();
        for (JsonNode option : card.path("options")) options.add(option.asText());
        map.put("options", options);
        if (card.path("value").isNull() || card.path("value").asText("").isBlank()) map.put("value", null);
        else map.put("value", card.path("value").asText(""));
        return map;
    }

    private static List<ObjectNode> read(Path project, String session) throws IOException {
        Path file = file(project, session);
        List<ObjectNode> cards = new ArrayList<>();
        if (!Files.isRegularFile(file)) return cards;
        JsonNode node = AgentStore.JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        if (node.isArray()) {
            for (JsonNode item : node) if (item.isObject()) cards.add((ObjectNode) item);
        }
        return cards;
    }

    private static void write(Path project, String session, List<ObjectNode> cards) throws IOException {
        Path file = file(project, session);
        Files.createDirectories(file.getParent());
        ArrayNode array = AgentStore.JSON.createArrayNode();
        cards.forEach(array::add);
        Files.writeString(file, AgentStore.JSON.writeValueAsString(array), StandardCharsets.UTF_8);
    }

    private static Path file(Path project, String session) {
        Path root = project == null ? Path.of("") : project;
        String safe = session.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(".pickleball").resolve("workbench-ui").resolve("cards").resolve(safe + ".json");
    }
}
