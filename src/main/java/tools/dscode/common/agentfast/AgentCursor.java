package tools.dscode.common.agentfast;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One opaque cursor per channel (chat, ui, events). The same token reads the same
 * position again and does not move another channel.
 */
public final class AgentCursor {
    private static final ConcurrentHashMap<String, Integer> UI_NEXT = new ConcurrentHashMap<>();

    private AgentCursor() {
    }

    public record Parts(String chat, int events, int overlay, String channel) {
    }

    public static String pack(String chat, int events, int overlay) {
        return pack(chat, events, overlay, "");
    }

    public static String pack(String chat, int events, int overlay, String channel) {
        String raw = (chat == null ? "" : chat) + "\n" + Math.max(0, events) + "\n" + Math.max(0, overlay)
                + "\n" + (channel == null ? "" : channel);
        return "c." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Parts unpack(String token) {
        if (token == null || !token.startsWith("c.")) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token.substring(2));
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int first = raw.indexOf('\n');
            int second = first < 0 ? -1 : raw.indexOf('\n', first + 1);
            if (first < 0 || second < 0) return null;
            String chat = raw.substring(0, first);
            int events = Integer.parseInt(raw.substring(first + 1, second).trim());
            int third = raw.indexOf('\n', second + 1);
            String overlayText = third < 0 ? raw.substring(second + 1) : raw.substring(second + 1, third);
            int overlay = Integer.parseInt(overlayText.trim());
            String channel = third < 0 ? "" : raw.substring(third + 1);
            return new Parts(chat, events, overlay, channel);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    public static int overlay(String token) {
        Parts parts = unpack(token);
        return parts == null ? 0 : parts.overlay;
    }

    public static String chat(String token) {
        Parts parts = unpack(token);
        return parts == null || parts.chat == null ? "" : parts.chat;
    }

    public static String channelOf(String op) {
        if (op == null || op.isBlank()) return "";
        if (op.startsWith("chat")) return "chat";
        if ("ui-poll".equals(op) || "overlay-poll".equals(op)) return "ui";
        if ("events".equals(op)) return "events";
        return "";
    }

    /** Remembers the ui/overlay end index so a foreign cursor does not replay from zero. */
    public static void noteUi(String runId, int next) {
        int value = Math.max(0, next);
        UI_NEXT.put("*", value);
        if (runId != null && !runId.isBlank()) UI_NEXT.put(runId, value);
    }

    public static int uiAfter(String runId) {
        if (runId != null && !runId.isBlank()) {
            Integer exact = UI_NEXT.get(runId);
            if (exact != null) return exact;
        }
        Integer any = UI_NEXT.get("*");
        return any == null ? 0 : any;
    }

    /** Copies a cursor into the flag the verb already understands. A token for another channel is ignored. */
    public static void absorb(String op, Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        String token = values.get("cursor");
        if (token == null || token.isBlank()) {
            String after = values.get("after");
            if (after != null && after.startsWith("c.")) token = after;
        }
        Parts parts = unpack(token);
        if (parts == null) return;
        String verb = op == null ? "" : op;
        String want = channelOf(verb);
        boolean legacy = parts.channel == null || parts.channel.isEmpty();
        if (!legacy && !want.isEmpty() && !want.equals(parts.channel)) {
            values.put("peek", "true");
            if ("ui".equals(want)) values.put("after", Integer.toString(uiAfter(values.get("run-id"))));
            else if (token.equals(values.get("after"))) values.remove("after");
            return;
        }
        if ("chat-poll".equals(verb) || "chat-history".equals(verb)) {
            values.put("after", parts.chat == null || parts.chat.isEmpty() ? "0" : parts.chat);
            if (parts.chat != null && !parts.chat.isEmpty()) values.put("id", parts.chat);
            return;
        }
        if ("ui-poll".equals(verb) || "overlay-poll".equals(verb)) {
            values.put("after", Integer.toString(parts.overlay));
            if (parts.chat != null && !parts.chat.isEmpty()) values.put("chat-after", parts.chat);
        }
    }

    /** The cursor now. A numeric {@code next} on the body is the overlay index. The token's channel is the verb. */
    public static String stamp(String runId, String agentId, Map<String, Object> body) {
        int overlay = 0;
        Object next = body == null ? null : body.get("next");
        if (next instanceof Number number) overlay = number.intValue();
        String chat = "";
        Object chatNext = body == null ? null : body.get("chatNext");
        if (chatNext != null) chat = String.valueOf(chatNext);
        int events = 0;
        Object eventNext = body == null ? null : body.get("eventNext");
        if (eventNext instanceof Number number) events = number.intValue();
        String op = body == null || body.get("op") == null ? "" : String.valueOf(body.get("op"));
        String channel = channelOf(op);
        if (channel.isEmpty() && overlay > 0) channel = "ui";
        String token = pack(chat, events, overlay, channel);
        if ("ui".equals(channel)) noteUi(runId, overlay);
        if (body != null) {
            Map<String, Object> cursors = new LinkedHashMap<>();
            cursors.put("channel", channel);
            Object head = body.get("chatHead");
            // A reply that does not read chat shows the newest id; its token still does not skip unread chat.
            cursors.put("chat", chat.isEmpty() && head != null ? String.valueOf(head) : chat);
            cursors.put("ui", overlay);
            cursors.put("events", events);
            cursors.put("token", token);
            body.put("cursors", cursors);
            body.remove("chatHead");
        }
        return token;
    }
}
