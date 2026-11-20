package tools.dscode.launcher;

import java.io.PrintStream;
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
            Map.entry("dashboard", "Usage: dashboard [--project=<dir>] [--out=<file>] [--from=<iso>] [--to=<iso>] [--status=<status>] [--feature=<path>] [--run=<id>] [--entity=<Type#id>] [--text=<words>]\nWrites one static HTML page from the embedded store. It does not change the store and it does not start a server."),
            Map.entry("discover", "Usage: discover [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--select=<path:line>] [--plan] [--quiet] [--refresh] [--no-narrow|--full-scan] [--runvar=key=value] [--retention=all|failed|none] [--run-id=<id>] [--agent=<id>] [--run-timeout=<seconds>]"),
            Map.entry("confirm", "Usage: confirm [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--select=<path:line>] [--quiet] [--runvar=key=value] [--run-id=<id>] [--agent=<id>] [--run-timeout=<seconds>]"),
            Map.entry("plan", "Usage: plan [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--select=<path:line>] [--refresh] [--json]"),
            Map.entry("validate", "Usage: validate [--tags=<expr>] [--name=<regex>] [--run-id=<id>] [--max-steps=<n>] [--all] [--noop]\nReports undefined steps, ambiguous glue, and unresolved RUN SCENARIO targets. Outline rows are expanded. Core steps count as defined. A long suite prints progress and stops at a step cap unless --max-steps or --all is set, and reports how many scenarios were skipped. --noop lists empty consumer step definitions (noop:true) and exits 1 when any are found. A non-empty body is not listed. Runs nothing. A flag this command does not use, including --quiet, is rejected with error: and fix:."),
            Map.entry("hint", "Usage: hint [--json]"),
            Map.entry("discover-hint", "Usage: discover-hint [--json]"),
            Map.entry("doctor", "Usage: doctor\nReports chrome, session, health, headed, pid, and bridge. No session still exits 0."),
            Map.entry("resolve-runvars", "Usage: resolve-runvars [--json]"),
            Map.entry("workspace-show", "Usage: workspace-show --run-id=<id> [--json]"),
            Map.entry("workspace-edit", "Usage: workspace-edit --run-id=<id> (--runvar=key=value | --config=<relative> --text=<body>) [--expect=<fingerprint>]"),
            Map.entry("workspace-seed", "Usage: workspace-seed --run-id=<id> [--reseed] [--from-run=<id>]"),
            Map.entry("isolate", "Usage: isolate [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--runvar=key=value] [--run-id=<id>] [--agent=<id>] [--session-file=<path>]"),
            Map.entry("session-start", "Usage: session-start [--tags=<expr>] [--name=<regex>] [--example=<rows>] [--run-id=<id>] [--agent=<id>] [--session-file=<path>]"),
            Map.entry("execute-step", "Usage: execute-step <gherkin> | execute-step --text=<gherkin> | --text-file=<path> | --text-b64=<base64> [--ack-only] [--step-timeout=<seconds>]"),
            Map.entry("status", "Usage: status [command-id] [--json] [--run-id=<id>]"),
            Map.entry("events", "Usage: events [--hooks] [--json] [--cursor=<token>] [--run-id=<id>]"),
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
            Map.entry("query", "Usage: query <runs|get|query|search|props|errors|tail> [--run-id=<id>] [--scenario=<id>] [--kind=run|scenario|step|event|property|artifact|error|log] [--text=<words>] [--status=<outcome>] [--fields=<a,b>] [--limit=<n>] [--cursor=<token>] [--id=<id>]"),
            Map.entry("use-version", "Usage: use-version --version=<safe-version>"),
            Map.entry("find-element", "Usage: find-element (--category=<Button> [--text=<label>] | --query=<selector>) [--exposure=human|lower] [--limit=<n>] [--explain] [--promote-visual] [--html=<file>]"),
            Map.entry("presence", "Usage: presence --touch --agent=<id> [--run-id=<id>] | presence --list | presence --sweep"),
            Map.entry("post", "Usage: post --write --to=<agent-id|all> --text=<one line> [--run-id=<id>] [--ttl=1h] | post --renew --id=<id> [--ttl=1h]"),
            Map.entry("history", "Usage: history --append --text=<one line>"),
            Map.entry("insert-step", "Usage: insert-step --text=<gherkin>"),
            Map.entry("update-step", "Usage: update-step --text=<gherkin>"),
            Map.entry("show-run", "Usage: show-run --run-id=<id>"),
            Map.entry("open-window", "Usage: open-window --run-id=<id>"),
            Map.entry("inbox", "Usage: inbox --write --to=<agent-id|any> --text=<one line> [--from=<agent-id>] [--run-id=<id>] | inbox --list|--take --agent=<id>"),
            Map.entry("observe", "Usage: observe [--type=<Network|console|log>] [--url=<substring>] [--since=<epoch-ms>] [--until=<epoch-ms>] [--limit=<n>] [--wait-ms=<n>] [--events=<file>] [--cdp=<url>] [--session=<id>] [--run-id=<id>]"),
            Map.entry("launch", "Usage: launch --template=<name> [--arg=<chrome-arg>] [--start] [--run-id=<id>]\nTemplates: chrome-headed-verbose, chrome-headed-quiet, chrome-headless-verbose, chrome-headless-quiet, edge-headed-quiet, edge-headless-quiet, chrome-headed-logs, chromium-headed-quiet. Args are added to the existing pkb_browser / Selenium Manager launch. Chromium uses that Chrome launcher. --start calls DriverConstruction."),
            Map.entry("windows", "Usage: windows [--switch=<handle>] [--close=<handle>] [--run-id=<id>]"),
            Map.entry("tabs", "Usage: tabs [--switch=<handle>] [--close=<handle>] [--run-id=<id>]"),
            Map.entry("chat-send", "Usage: chat-send --text=<message> [--source=agent|human|system] [--channel=<name>] [--agent=<id>] [--run-id=<id>]"),
            Map.entry("chat-history", "Usage: chat-history [--channel=<name>] [--limit=<n>] [--run-id=<id>]"),
            Map.entry("chat-poll", "Usage: chat-poll [--channel=<name>] [--id=<after>] [--wait=<seconds>] [--run-id=<id>]"),
            Map.entry("start", "Usage: start --url=<page> --browser=<chrome|edge|chrome-headless> [--env=KEY=VALUE] [--project=<dir>]\nPublishes a live session, opens the browser, and waits until GET /health is ok. Later page commands with no --run-id target that session. live-start is start."),
            Map.entry("chat_wait", "Usage: chat_wait [--timeout=<seconds>] [--run-id=<id>]\nBlocks until a chat message is stored, or until --timeout (default 30, max 120)."),
            Map.entry("history-summary", "Usage: history-summary\nPrints runs, passed, failed, skipped, and other from run records and the store."),
            Map.entry("history-query", "Usage: history-query <runs|get|query|search|props|errors|tail> [--run-id=<id>] [--text=<words>] [--limit=<n>]\nSame read as query. The store is the source of truth."),
            Map.entry("entity-lookup", "Usage: entity-lookup --ref=<entity:Type#id.field>\nPrints the field value. A miss prints miss and exits 1."),
            Map.entry("open-run", "Usage: open-run --run-id=<id> [--tab=explorer|tracking]\nAsks the Workbench to show that run. explorer is the default tab."),
            Map.entry("bridge-health", "Usage: bridge-health --url=<bridge-base>\nGET /v1/health. Exit 0 on HTTP 200. Exit 1 when the bridge is down or unhealthy."),
            Map.entry("highlight", "Usage: highlight --handle=<id> (--preset=yellow|outline-red) [--style=<css>] [--html=<page>] [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>]"),
            Map.entry("unhighlight", "Usage: unhighlight [--handle=<id>] [--html=<page>]"),
            Map.entry("overlay", "Usage: overlay --text=<html> [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>] [--handle=<id>] [--at=auto|top|bottom|left|right|over] [--theme=auto|light|dark] [--block=true] [--geometry=<json>] [--html=<page>]"),
            Map.entry("panel-open", "Usage: panel-open [--target=auto|page|popup] [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>] [--tab=chat|steps] [--title=<text>] [--theme=auto|light|dark] [--minimized=true|false] [--run-id=<id>]"),
            Map.entry("panel-close", "Usage: panel-close [--target=auto|page|popup] [--run-id=<id>]"),
            Map.entry("guide", "Usage: guide --steps=<json array>|@file.json [--id=<guide>] [--scroll=true] [--run-id=<id>]  (step: {\"t\":\"#email\",\"say\":\"Type your email\",\"do\":\"input\"}; do is click, input, select, check, or any)"),
            Map.entry("guide-next", "Usage: guide-next [--id=<guide>] [--run-id=<id>]"),
            Map.entry("guide-back", "Usage: guide-back [--id=<guide>] [--run-id=<id>]"),
            Map.entry("guide-done", "Usage: guide-done [--id=<guide>] [--run-id=<id>]"),
            Map.entry("ask", "Usage: ask --say=<question> [--choices=Yes,No] [--handle=<target>] [--id=<question>] [--sensitive=true] [--run-id=<id>]"),
            Map.entry("ask-and-wait", "Usage: ask-and-wait --say=<question> [--choices=a,b] [--id=<id>] [--timeout=<ms>]"),
            Map.entry("pin", "Usage: pin --text=<comment>"),
            Map.entry("toast", "Usage: toast --text=<message>"),
            Map.entry("checklist", "Usage: checklist --items=a|b|c [--id=<id> --check=<n>]"),
            Map.entry("ui-apply", "Usage: ui-apply --ops=<json array>|@file.json [--verbose=true] [--scope=page|origin|run] [--ttl=<seconds>] [--run-id=<id>]  (op: {\"c\":\"label\",\"t\":\"#email\",\"text\":\"Email\"}; see ui-catalog)"),
            Map.entry("ui-poll", "Usage: ui-poll [--after=<n>] [--chat-after=<id>] [--wait=<seconds, up to 25>] [--channel=<name>] [--run-id=<id>]"),
            Map.entry("ui-catalog", "Usage: ui-catalog [--component=<name>] [--json]"),
            Map.entry("overlay-poll", "Usage: overlay-poll [--after=<next>] [--run-id=<id>]"),
            Map.entry("overlay-remove", "Usage: overlay-remove --id=<id> [--html=<page>]"),
            Map.entry("label", "Usage: label --text=<label> [--handle=<id>] [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>] [--geometry=<json>] [--target=auto|page|popup|native] [--block=true] [--html=<page>]"),
            Map.entry("unlabel", "Usage: unlabel --id=<id> [--html=<page>]"),
            Map.entry("border", "Usage: border [--text=<html>] [--handle=<id>] [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>] [--color=<color>] [--style=<css>] [--target=auto|page|popup|native] [--block=true] [--html=<page>]"),
            Map.entry("unborder", "Usage: unborder --id=<id> [--html=<page>]"),
            Map.entry("arrow", "Usage: arrow (--from=<handle> --to=<handle> | --handle=<id> --direction=up|down|left|right | --x=<px> --y=<px> --direction=up|down|left|right) [--target=auto|page|popup|native] [--block=true] [--html=<page>]"),
            Map.entry("unarrow", "Usage: unarrow --id=<id> [--html=<page>]"),
            Map.entry("step-badge", "Usage: step-badge --number=<n> [--text=<caption>] [--handle=<id>] [--x=<px>] [--y=<px>] [--target=auto|page|popup|native] [--block=true] [--html=<page>]"),
            Map.entry("unstep-badge", "Usage: unstep-badge --id=<id> [--html=<page>]"),
            Map.entry("magnifier", "Usage: magnifier [--text=<caption>] [--zoom=<1-8>] [--handle=<id>] [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>] [--target=auto|page|popup|native] [--block=true] [--html=<page>]"),
            Map.entry("unmagnifier", "Usage: unmagnifier --id=<id> [--html=<page>]"),
            Map.entry("spotlight", "Usage: spotlight [--dim=<0-1>] [--handle=<id>] [--x=<px>] [--y=<px>] [--width=<px>] [--height=<px>] [--target=auto|page|popup|native] [--block=true] [--html=<page>]"),
            Map.entry("unspotlight", "Usage: unspotlight --id=<id> [--html=<page>]"),
            Map.entry("html-write", "Usage: html-write --text=<html> [--html=<page>]"),
            Map.entry("html-replace", "Usage: html-replace --handle=<id> --text=<html> [--html=<page>]"),
            Map.entry("html-insert", "Usage: html-insert --handle=<id> --text=<html> [--where=before|after] [--html=<page>]"),
            Map.entry("html-remove", "Usage: html-remove --id=<id> [--html=<page>]"),
            Map.entry("html-cleanup", "Usage: html-cleanup [--html=<page>]"),
            Map.entry("capture-scenario", "Usage: capture-scenario [--url=<substring>] [--since=<epoch-ms>] [--until=<epoch-ms>] [--events=<file>] [--out=<feature>] [--run-id=<id>]"),
            Map.entry("screenshot", "Usage: screenshot --out=<file> [--run-id=<id>]"),
            Map.entry("selftest", "Usage: selftest ui --cpu [--component=panel]\nselftest ui --component=pages"),
            Map.entry("snap", "Usage: snap [--target=browser:auto|browser:<id>|browser:cdp=<url>|desktop:<pid>|desktop:app~<name>|ref:<doc>:<handle>] [--format=lines|json] [--fields=<a,b>] [--where=<role=button>] [--limit=<n>] [--budget=<bytes>]"),
            Map.entry("find", "Usage: find --target=<selector> [--query=<text>]"),
            Map.entry("act", "Usage: act --target=<selector> [--kind=click|type|hover|press|select] [--query=<text>] [--ref=<ref>] [--value=<text>] [--condition=title=<text>|text=<text>|url=<text>|ref=<id>:selected] [--snap=diff|auto] [--timeout=<ms>]"),
            Map.entry("form-fill", "Usage: form-fill --target=<selector> --fields=id=value|id=value"),
            Map.entry("wait", "Usage: wait --target=<selector> [--ref=<ref>] [--condition=mutation|network|load|navigation|mask|visible|hidden|enabled|detached|text=<text>|<text>] [--timeout=<ms>]"),
            Map.entry("wait-for", "Usage: wait-for --target=<selector> --condition=<text> [--timeout=<ms>]"),
            Map.entry("watch", "Usage: watch --target=<selector> [--kinds=<kinds>] [--duration=<ms>]"),
            Map.entry("explain", "Usage: explain --target=<selector> [--query=<text>]"),
            Map.entry("capture", "Usage: capture --target=<selector> --out=<file>"),
            Map.entry("attach", "Usage: attach --target=<selector>"),
            Map.entry("eval", "Usage: eval --target=<selector> --expr=<js> [--world=pkb|main]"),
            Map.entry("ui", "Usage: ui [--ops=<json array>]\nWithout --ops, ui opens the Workbench controller. With --ops, ui applies a catalog batch (same as ui-apply)."),
            Map.entry("frame", "Usage: frame --target=<selector> [--list] [--reset] [--enter=<id|name>]\nLists frames, returns later steps to the top document, or enters a frame. --reset clears the entered frame and the Selenium driver. --enter scopes later find, act, snap, and inspect to that frame, and enters an iframe with that id, name, or title when the driver can see it. Replies include frame."),
            Map.entry("ref", "Usage: ref --id=<short-id>\nExpands a short id such as s1.h42 to its full ref. The id follows the DOM node until that node is removed."),
            Map.entry("controller-start", "Usage: controller-start [project]\nStarts the long-lived agent controller for this project. Later verbs are clients of that process."),
            Map.entry("controller-stop", "Usage: controller-stop [project]\nStops the agent controller and deletes its handle file."),
            Map.entry("controller-status", "Usage: controller-status [project]\nPrints running=true and the pid when the controller is up."),
            Map.entry("controller-ping", "Usage: controller-ping [project]\nOne warm round trip. The reply is the agent envelope."),
            Map.entry("inspect-page", "Usage: inspect-page [--query=<selector>] [--html=<file>] [--target=<selector>]\nLists interactive elements and the phrase that would target each one. It takes no category. The reply names the current frame. --target uses the same page as snap."),
            Map.entry("live-stop", "Usage: live-stop\nStops the start host and Chrome. It does not stop a Workbench worker.")
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
            Map.entry("--cursor", "Usage: --cursor=<token>\nPage token from nextCursor. The truncated line says ...+N more, use cursor."),
            Map.entry("--fields", "Usage: --fields=<a,b>\nComma-separated item fields. Omit it for the compact default."),
            Map.entry("--text-file", "Usage: --text-file=<path>\nUTF-8 file used as execute-step --text. Keeps inner quotes that a shell would strip."),
            Map.entry("--text-b64", "Usage: --text-b64=<base64>\nBase64 of the UTF-8 step text. Use this when the shell strips quotes."),
            Map.entry("--hooks", "Usage: --hooks\nevents prints the raw event stream. The default is step and phrase lines, paged like query.")
    );

    private WorkbenchCliHelp() {
    }

    static boolean isHelpToken(String token) {
        return "help".equals(token) || "--help".equals(token) || "-h".equals(token);
    }

    /** First argument is help, {@code --help}, or {@code -h}. */
    static boolean isHelpRequest(String[] args) {
        if (args == null || args.length == 0 || args[0] == null) return false;
        return isHelpToken(args[0].trim());
    }

    /**
     * The only help path. The thin controller jar can run this without Jackson
     * and without initializing the agent or session command classes.
     */
    static int answer(String[] args, PrintStream out, PrintStream err) {
        String topic = null;
        if (args != null) {
            for (int index = 1; index < args.length; index++) {
                String token = args[index];
                if (token == null || token.isBlank() || isHelpToken(token.trim())) continue;
                topic = token.trim();
                break;
            }
        }
        if (topic == null) {
            out.print(catalog());
            return 0;
        }
        String page = forTopic(topic);
        if (page == null) {
            err.print(unknown(topic));
            return 2;
        }
        out.print(page);
        return 0;
    }

    static String catalog() {
        StringBuilder text = new StringBuilder();
        text.append("Pickleball Workbench. help <command> prints usage. help <flag> prints that flag.\n");
        text.append("exit 0 ok, 1 failed, 2 usage. --json is one JSON object and no preamble.\n");
        text.append("help is always available on the thin controller jar and does not load Jackson.\n");
        text.append("decide: discover, then plan, then run. plan lists the scenarios. validate checks steps and does not run. run is confirm, or isolate then execute-step.\n");
        text.append("Timing is .pickleball/runs/<id>/launch-timing.txt. A cache hit skips Maven. Stderr timing only when PKB_WORKBENCH_TIMING=1 or -Dpkb_workbench_timing=true.\n");
        text.append("Do not pass pkb_run_profile. Do not open the GUI to test yourself. isolate is the headless session.\n");
        text.append("run: discover confirm plan validate hint discover-hint resolve-runvars\n");
        text.append("workspace: workspace-show workspace-edit workspace-seed\n");
        text.append("session: isolate session-start execute-step status events stop kill open-scenario example play from-here pause insert-step update-step diagnostic-run save refresh session-sync worker-start worker-restart worker-stop open-window close-window show-run\n");
        text.append("coordination: short-log note inbox finish presence post history gc-runs gc-versions use-version export-guidance\n");
        text.append("descriptors: session list | session info <sessionId> [--json] | session hold <sessionId> | session release <sessionId>\n");
        text.append("page: inspect-page find-element\n");
        text.append("agent: observe launch windows tabs chat-send chat-history chat-poll chat_wait highlight unhighlight overlay overlay-poll overlay-remove label unlabel border unborder arrow unarrow step-badge unstep-badge magnifier unmagnifier spotlight unspotlight html-write html-replace html-insert html-remove html-cleanup popup-open popup-status popup-close panel-open panel-close guide guide-next guide-back guide-done ask ask-and-wait pin toast checklist ui-apply ui-poll ui-catalog capture-scenario screenshot selftest doctor act form-fill snap find frame wait wait-for watch explain capture attach eval ref controller-start controller-stop controller-status controller-ping\n");
        text.append("consumer: start live-stop history-summary history-query entity-lookup open-run bridge-health\n");
        text.append("retrieval: query runs | query get | query query | query search | query props | query errors | query tail\n");
        text.append("controller: sync worker-check live-check ui mcp session\n");
        text.append("dashboard: dashboard [--project=<dir>] [--out=<file>]\n");
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
        return WorkbenchCliFlags.known(token);
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
