package tools.dscode.launcher;

import java.util.List;
import java.util.Map;

/**
 * One flag catalog. Help reads this class only, so it does not initialize
 * {@link WorkbenchAgentCommands} or {@link WorkbenchSessionCommands}.
 */
final class WorkbenchCliFlags {
    static final List<String> COMMAND_LINE = List.of(
            "--tags", "--tags-file", "--tags-b64",
            "--name", "--example", "--plan", "--refresh", "--no-narrow", "--full-scan",
            "--runvar", "--select", "--retention",
            "--run-id", "--agent", "--agent-id", "--group", "--sequence",
            "--who", "--why", "--learned", "--text", "--to", "--from", "--limit",
            "--write", "--list", "--take", "--touch", "--sweep", "--renew", "--append",
            "--id", "--version", "--ttl", "--step-timeout", "--run-timeout", "--quiet",
            "--max-steps", "--all"
    );

    static final List<String> AGENT = List.of(
            "--json", "--expect", "--config", "--reseed", "--from-run",
            "--html", "--category", "--status",
            "--kind", "--scenario", "--cursor", "--fields"
    );

    static final List<String> SESSION = List.of(
            "--ack-only", "--wait", "--no-wait", "--force",
            "--stop-after", "--feature", "--run", "--from-here", "--session-file",
            "--text-file", "--text-b64", "--hooks"
    );

    /**
     * Commands in this map reject any flag that is not listed.
     * Commands that are absent keep their previous acceptance, including
     * discover and confirm {@code --quiet}.
     */
    static final Map<String, List<String>> USED_BY = Map.of(
            "validate", List.of(
                    "--tags", "--tags-file", "--tags-b64", "--name", "--run-id",
                    "--max-steps", "--all"
            )
    );

    private WorkbenchCliFlags() {
    }

    static boolean known(String token) {
        return COMMAND_LINE.contains(token) || AGENT.contains(token) || SESSION.contains(token);
    }
}
