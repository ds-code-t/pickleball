package tools.dscode.common.agentfast;

import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.LiveSessionAttach;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Posts one agent verb to the live worker {@code POST /v1/agent/command}.
 * A failed post is the result. Callers must not fall through to a local driver.
 * The post itself is {@link LiveSessionAttach}, shared with MCP.
 */
public final class AgentForward {
    private AgentForward() {
    }

    public record Posted(int exit, String bodyJson, String error) {
        static Posted from(LiveSessionAttach.Posted posted) {
            return new Posted(posted.exit(), posted.bodyJson(), posted.error());
        }
    }

    public static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId
    ) {
        return Posted.from(LiveSessionAttach.post(bridge, op, args, extra, project, runId, agentId));
    }

    /**
     * The HTTP budget must outlive the verb. A flat 60s made {@code chat-turn --timeout=120}
     * look like the worker was gone after the message had already been sent.
     */
    public static Duration httpTimeout(String op, Map<String, String> args) {
        return LiveSessionAttach.httpTimeout(op, args);
    }

    public static Posted post(
            LiveBridgeFile.Bridge bridge,
            String op,
            Map<String, String> args,
            List<String> extra,
            Path project,
            String runId,
            String agentId,
            Duration timeout
    ) {
        return Posted.from(LiveSessionAttach.post(bridge, op, args, extra, project, runId, agentId, timeout));
    }
}
