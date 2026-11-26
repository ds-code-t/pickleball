package tools.dscode.common.agentcontrol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Channel for {@code chat send}. A blank channel uses the spine session id.
 * {@code pkb_chat_channel=run} keeps the single run channel. {@link ChatLog} still
 * stores a blank channel as {@code run} so older {@code chat-send} callers stay put.
 */
public final class ChatChannel {
    private ChatChannel() {
    }

    public static String resolve(Path project, String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String mode = System.getProperty("pkb_chat_channel");
        if (mode == null || mode.isBlank()) {
            String env = System.getenv("PKB_CHAT_CHANNEL");
            mode = env == null ? "" : env;
        }
        if ("run".equalsIgnoreCase(mode.trim())) return "run";
        String session = SpineSession.current(project);
        if (session != null && !session.isBlank()) return session;
        return "run";
    }

    public static Map<String, Object> send(Path project, String runId, String explicitChannel, String text, String ref, String action, String agent) throws IOException {
        String channel = resolve(project, explicitChannel);
        ChatLog.Message message = ChatLog.send(project, runId, channel, "agent", agent, text, "", ref, action);
        return ChatLog.toMap(message);
    }

    public static List<Map<String, Object>> list(Path project, String runId, String explicitChannel) throws IOException {
        String channel = resolve(project, explicitChannel);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChatLog.Message message : ChatLog.history(project, runId, channel, 0)) rows.add(ChatLog.toMap(message));
        return rows;
    }
}
