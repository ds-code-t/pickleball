package tools.dscode.launcher;

import java.util.List;

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

    private WorkbenchCliFlags() {
    }

    static boolean known(String token) {
        return COMMAND_LINE.contains(token) || AGENT.contains(token) || SESSION.contains(token);
    }
}
