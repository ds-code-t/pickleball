package tools.dscode.common.agentcontrol;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Child process for the cross-process chat lock test. Not a product entry point. */
public final class ChatLockChild {
    private ChatLockChild() {
    }

    public static void main(String[] args) {
        AgentControl.Result result = AgentControl.execute(
                Path.of(args[0]),
                args[1],
                "chat-send",
                Map.of("channel", "run", "source", "human", "text", "child"),
                List.of(),
                "child"
        );
        System.out.println("exit=" + result.exit());
        System.out.println(result.body());
        System.exit(result.exit());
    }
}
