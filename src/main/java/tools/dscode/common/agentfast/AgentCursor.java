package tools.dscode.common.agentfast;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * One opaque cursor for chat, agent events, and overlay input.
 * The same token reads the same position again.
 */
public final class AgentCursor {
    private AgentCursor() {
    }

    public record Parts(String chat, int events, int overlay) {
    }

    public static String pack(String chat, int events, int overlay) {
        String raw = (chat == null ? "" : chat) + "\n" + Math.max(0, events) + "\n" + Math.max(0, overlay);
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
            int events = Integer.parseInt(raw.substring(first + 1, second));
            int overlay = Integer.parseInt(raw.substring(second + 1));
            return new Parts(chat, events, overlay);
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

    /** Copies a unified cursor into the flag the verb already understands. */
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
        if ("chat-poll".equals(verb) || "chat-history".equals(verb)) {
            values.put("after", parts.chat == null || parts.chat.isEmpty() ? "0" : parts.chat);
            if (parts.chat != null && !parts.chat.isEmpty()) values.put("id", parts.chat);
            return;
        }
        if ("ui-poll".equals(verb) || "overlay-poll".equals(verb)) {
            values.put("after", Integer.toString(parts.overlay));
            if (!parts.chat.isEmpty()) values.put("chat-after", parts.chat);
        }
    }

    /** The cursor now, using a numeric {@code next} already on the body as the overlay index. */
    public static String stamp(String runId, String agentId, Map<String, Object> body) {
        int overlay = 0;
        Object next = body == null ? null : body.get("next");
        if (next instanceof Number number) overlay = number.intValue();
        String chat = "";
        Object chatNext = body == null ? null : body.get("chatNext");
        if (chatNext != null) chat = String.valueOf(chatNext);
        return pack(chat, 0, overlay);
    }
}
