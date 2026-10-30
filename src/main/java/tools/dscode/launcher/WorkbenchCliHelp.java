package tools.dscode.launcher;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * One catalog for the consumer launcher. Agents read {@code help} instead of
 * guessing a controller classpath or starting a JVM for a typo.
 */
final class WorkbenchCliHelp {
    private static final Map<String, String> USAGE = Map.ofEntries(
            Map.entry("help", "Usage: help [command|flag]"),
            Map.entry("discover", "Usage: discover [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--select=<path:line>] [--plan] [--quiet] [--refresh] [--no-narrow|--full-scan] [--runvar=key=value] [--retention=all|failed|none] [--run-id=<id>] [--agent=<id>] [--run-timeout=<seconds>]"),
            Map.entry("confirm", "Usage: confirm [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--select=<path:line>] [--quiet] [--runvar=key=value] [--run-id=<id>] [--agent=<id>] [--run-timeout=<seconds>]"),
            Map.entry("plan", "Usage: plan [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--select=<path:line>] [--refresh] [--json]"),
            Map.entry("hint", "Usage: hint [--json]"),
            Map.entry("discover-hint", "Usage: discover-hint [--json]"),
            Map.entry("resolve-runvars", "Usage: resolve-runvars [--json]"),
            Map.entry("workspace-show", "Usage: workspace-show --run-id=<id> [--json]"),
            Map.entry("workspace-edit", "Usage: workspace-edit --run-id=<id> (--runvar=key=value | --config=<relative> --text=<body>) [--expect=<fingerprint>]"),
            Map.entry("workspace-seed", "Usage: workspace-seed --run-id=<id> [--reseed] [--from-run=<id>]"),
            Map.entry("isolate", "Usage: isolate [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--runvar=key=value] [--run-id=<id>] [--agent=<id>] [--session-file=<path>]"),
            Map.entry("session-start", "Usage: session-start [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--run-id=<id>] [--agent=<id>] [--session-file=<path>]"),
            Map.entry("execute-step", "Usage: execute-step <gherkin> | execute-step --text=<gherkin> [--ack-only] [--step-timeout=<seconds>]"),
            Map.entry("status", "Usage: status [command-id] [--json] [--run-id=<id>]"),
            Map.entry("events", "Usage: events [--json] [--run-id=<id>]"),
            Map.entry("stop", "Usage: stop [--force] [--run-id=<id>]"),
            Map.entry("diagnostic-run", "Usage: diagnostic-run --run=<id> [--run-id=<live-session>]"),
            Map.entry("open-scenario", "Usage: open-scenario --feature=<path-or-name> --name=<scenario> [--example=<rows>]"),
            Map.entry("example", "Usage: example --example=<rows>"),
            Map.entry("finish", "Usage: finish --run-id=<id> [--learned=<one line>] [--status=PASSED|FAILED|EMPTY|TIMEOUT|STOPPED]"),
            Map.entry("note", "Usage: note --text=<one line> [--agent=<id>] [--run-id=<id>]"),
            Map.entry("short-log", "Usage: short-log [--limit=<n>]"),
            Map.entry("export-guidance", "Usage: export-guidance [output-directory]"),
            Map.entry("session-list", "Usage: session list [--json]"),
            Map.entry("session-info", "Usage: session info <sessionId> [--json]"),
            Map.entry("session-hold", "Usage: session hold <sessionId> [--json]"),
            Map.entry("session-release", "Usage: session release <sessionId> [--json]"),
            Map.entry("query", "Usage: query <runs|get|query|search|props|errors> [--run-id=<id>] [--scenario=<id>] [--kind=run|scenario|step|event|property|artifact|error|log] [--text=<words>] [--status=<outcome>] [--fields=<a,b>] [--limit=<n>] [--cursor=<token>] [--id=<id>]"),
            Map.entry("use-version", "Usage: use-version --version=<safe-version>"),
            Map.entry("find-element", "Usage: find-element --category=<Button> [--text=<label>] [--html=<file>]"),
            Map.entry("presence", "Usage: presence --touch --agent=<id> [--run-id=<id>] | presence --list | presence --sweep"),
            Map.entry("post", "Usage: post --write --to=<agent-id|all> --text=<one line> [--run-id=<id>] [--ttl=1h] | post --renew --id=<id> [--ttl=1h]"),
            Map.entry("history", "Usage: history --append --text=<one line>"),
            Map.entry("insert-step", "Usage: insert-step --text=<gherkin>"),
            Map.entry("update-step", "Usage: update-step --text=<gherkin>"),
            Map.entry("show-run", "Usage: show-run --run-id=<id>"),
            Map.entry("open-window", "Usage: open-window --run-id=<id>"),
            Map.entry("inbox", "Usage: inbox --write --to=<agent-id|any> --text=<one line> [--from=<agent-id>] [--run-id=<id>] | inbox --list|--take --agent=<id>")
    );

    private static final Map<String, String> FLAGS = Map.ofEntries(
            Map.entry("--run-id", "Usage: --run-id=<id>\nLive session and run directory under .pickleball/runs. On diagnostic-run, --run=<id> is the diagnostic pack. When --run is omitted, --run-id is that pack."),
            Map.entry("--agent", "Usage: --agent=<id>\nSame value as --agent-id."),
            Map.entry("--agent-id", "Usage: --agent-id=<id>\nSame value as --agent."),
            Map.entry("--json", "Usage: --json\nOne compact JSON object on stdout and no preamble. workspace-show, resolve-runvars, hint, plan, session list, session info, status, and events."),
            Map.entry("--runvar", "Usage: --runvar=key=value\nRepeatable. Example: --runvar=pkb_browser=CHROME_HEADLESS. Never pass pkb_run_profile."),
            Map.entry("--run", "Usage: diagnostic-run --run=<id>\nDiagnostic pack id. Not the live-session flag. Prefer this over --run-id when the pack and the session differ. --run-id selects the live session and is the pack when --run is omitted."),
            Map.entry("--tags", "Usage: --tags=<expr>\nQuote a value that contains spaces: --tags='@A and not @B'. Or use --tags-file=<path> or --tags-b64=<base64 of the UTF-8 expression>."),
            Map.entry("--session-file", "Usage: --session-file=<path>\nisolate and session-start wait on this file. The default is that run's cli-session.json."),
            Map.entry("--step-timeout", "Usage: --step-timeout=<seconds>\nPositive whole number of seconds. execute-step only. discover and confirm reject it; use --run-timeout."),
            Map.entry("--run-timeout", "Usage: --run-timeout=<seconds>\nPositive whole number of seconds. discover, confirm, and execute-step."),
            Map.entry("--kind", "Usage: --kind=run|scenario|step|event|property|artifact|error|log\nquery and query get. Default for query is event when --run-id is set, otherwise run."),
            Map.entry("--scenario", "Usage: --scenario=<id>\nScenario directory name under the run. query get, query query, and query errors."),
            Map.entry("--cursor", "Usage: --cursor=<token>\nPage token from nextCursor. The truncated line says …+N more, use cursor."),
            Map.entry("--fields", "Usage: --fields=<a,b>\nComma-separated item fields. Omit it for the compact default.")
    );

    private WorkbenchCliHelp() {
    }

    static boolean isHelpToken(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }

    static String catalog() {
        StringBuilder text = new StringBuilder();
        text.append("Pickleball Workbench. help <command> prints usage. help <flag> prints that flag.\n");
        text.append("exit 0 ok, 1 failed, 2 usage. --json is one JSON object and no preamble.\n");
        text.append("decide: discover, then plan, then run. plan lists the scenarios. run is confirm, or isolate then execute-step.\n");
        text.append("Timing is .pickleball/runs/<id>/launch-timing.txt. Stderr timing only when PKB_WORKBENCH_TIMING=1 or -Dpkb_workbench_timing=true.\n");
        text.append("Do not pass pkb_run_profile. Do not open the GUI to test yourself. isolate is the headless session.\n");
        text.append("run: discover confirm plan hint discover-hint resolve-runvars\n");
        text.append("workspace: workspace-show workspace-edit workspace-seed\n");
        text.append("session: isolate session-start execute-step status events stop kill open-scenario example play from-here pause insert-step update-step diagnostic-run save refresh session-sync worker-start worker-restart worker-stop open-window close-window show-run\n");
        text.append("coordination: short-log note inbox finish presence post history gc-runs gc-versions use-version export-guidance\n");
        text.append("descriptors: session list | session info <sessionId> [--json] | session hold <sessionId> | session release <sessionId>\n");
        text.append("page: inspect-page find-element\n");
        text.append("retrieval: query runs | query get | query query | query search | query props | query errors\n");
        text.append("controller: sync worker-check live-check ui mcp session\n");
        text.append("all:");
        for (String command : commands()) text.append(' ').append(command);
        text.append('\n');
        return text.toString();
    }

    static String forTopic(String topic) {
        if (topic == null || topic.isBlank() || isHelpToken(topic.trim())) return catalog();
        String token = topic.trim();
        if ("session".equals(token)) {
            return "Usage: session list [--json] | session info <sessionId> [--json] | session hold <sessionId> | session release <sessionId>\n"
                    + "Bare session starts the controller. Agents testing alone use isolate.\n";
        }
        if (token.startsWith("--")) {
            String page = FLAGS.get(token);
            if (page != null) return ensureNewline(page);
            if (knownFlag(token)) return "Usage: " + token + "\nhelp <command> shows which command takes it.\n";
            return null;
        }
        return usage(token);
    }

    static String usage(String command) {
        if (command == null || command.isBlank()) return null;
        String specific = USAGE.get(command);
        if (specific != null) return ensureNewline(specific);
        if (WorkbenchCommandLine.isKnownCommand(command)) return "Usage: " + command + "\n";
        return null;
    }

    /** {@code error} plus the one usage-table row for {@code key}. */
    static String usageError(String error, String key) {
        String page = key == null || key.isBlank() ? null : forTopic(key);
        String fix = page == null ? "help" : page.strip();
        return structured(error, fix);
    }

    /** Every launcher failure is these two lines. {@code fix} is the recovery, not a second copy of {@code error}. */
    static String structured(String error, String fix) {
        return "error: " + error + "\nfix: " + fix + "\n";
    }

    static String unknown(String command) {
        String shown = command == null || command.isBlank() ? "(none)" : command;
        StringBuilder text = new StringBuilder();
        text.append("Unknown Workbench command: ").append(shown).append('\n');
        String near = closest(shown);
        if (near != null) text.append("Did you mean: ").append(near).append('\n');
        text.append("Usage: help\n");
        text.append("Usage: help <command>\n");
        text.append("error: Unknown Workbench command: ").append(shown).append('\n');
        text.append("fix: ").append(near == null ? "help" : near).append('\n');
        return text.toString();
    }

    static String closest(String raw) {
        if (raw == null || raw.isBlank() || isHelpToken(raw)) return null;
        String token = raw.trim().toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = 3;
        for (String command : commands()) {
            int distance = distance(token, command);
            if (distance < bestDistance || (distance == bestDistance && best != null && command.length() < best.length())) {
                bestDistance = distance;
                best = command;
            }
        }
        return bestDistance <= 2 ? best : null;
    }

    private static List<String> commands() {
        TreeSet<String> commands = new TreeSet<>();
        commands.addAll(WorkbenchCommandLine.FORWARDED_COMMANDS);
        commands.addAll(WorkbenchCommandLine.AGENT_CORE_COMMANDS);
        commands.addAll(WorkbenchCommandLine.SESSION_CLIENT_COMMANDS);
        return List.copyOf(commands);
    }

    private static boolean knownFlag(String token) {
        return WorkbenchCommandLine.MAJOR_FLAGS.contains(token)
                || WorkbenchSessionCommands.SessionFlags.MAJOR_FLAGS.contains(token)
                || WorkbenchAgentCommands.MAJOR_FLAGS.contains(token);
    }

    private static String ensureNewline(String text) {
        return text.endsWith("\n") ? text : text + "\n";
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int cost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
