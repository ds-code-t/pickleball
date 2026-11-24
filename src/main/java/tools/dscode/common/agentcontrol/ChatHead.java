package tools.dscode.common.agentcontrol;

import java.nio.file.Files;
import java.nio.file.Path;

/** The newest chat id of a run, for {@code cursors.chat} on replies that do not read chat. */
public final class ChatHead {
    private ChatHead() {
    }

    /** Newest id on the {@code run} channel, or blank when the run has no chat yet. */
    public static String latest(Path project, String runId) {
        if (project == null || runId == null || runId.isBlank()) return "";
        try {
            if (!Files.isDirectory(AgentStore.root(project, runId))) return "";
            String id = ChatLog.latestId(project, runId, "run");
            return "0".equals(id) ? "" : id;
        } catch (Exception failure) {
            return "";
        }
    }
}
