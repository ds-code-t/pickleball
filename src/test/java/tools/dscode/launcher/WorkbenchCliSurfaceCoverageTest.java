package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.control.protocol.SessionDescriptor;
import tools.dscode.control.protocol.SessionDescriptorWriter;
import tools.dscode.control.protocol.Workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import tools.dscode.control.protocol.PickleballLocalLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One method per Workbench command and major flag. The coverage gate reads these names.
 * Each method asserts a real parse result, exit code, or file.
 */
class WorkbenchCliSurfaceCoverageTest {
    @TempDir
    Path temp;

    @BeforeEach
    void captureSeed() {
        WorkspaceLaunch.testCapture = workspace -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : Workspace.CONTEXT_KEYS) values.put(key, key);
            values.put("pkb_browser", "CHROME_HEADLESS");
            values.put("pkb_parallel", "auto");
            Workspace.writeRunVars(workspace, values);
        };
    }

    @AfterEach
    void clearSeed() {
        WorkspaceLaunch.testCapture = null;
        AgentCoordination.clearCurrent();
    }

    @Test
    void covers_command_sync() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("sync", temp.toString(), "--tags=@smoke");
        assertEquals("sync", parsed.command());
        assertTrue(WorkbenchCommandLine.isForwardedCommand("sync"));
        assertFalse(WorkbenchCommandLine.isAgentCoreCommand("sync"));
        assertFalse(WorkbenchCommandLine.isSessionClientCommand("sync"));
        assertEquals(List.of("sync", temp.toAbsolutePath().normalize().toString(), "--tags", "@smoke"),
                List.of(parsed.forwarded()));
        forwardedFailsClosed("sync");
    }

    @Test
    void covers_command_worker_check() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("worker-check", temp.toString());
        assertEquals("worker-check", parsed.command());
        assertTrue(WorkbenchCommandLine.isForwardedCommand("worker-check"));
        assertEquals("worker-check", parsed.forwarded()[0]);
        assertEquals(temp.toAbsolutePath().normalize().toString(), parsed.forwarded()[1]);
        forwardedFailsClosed("worker-check");
    }

    @Test
    void covers_command_live_check() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("live-check", temp.toString(), "--runvar=pkb_browser=CHROME_HEADLESS");
        assertEquals("live-check", parsed.command());
        assertTrue(List.of(parsed.forwarded()).contains("--runvar"));
        assertTrue(List.of(parsed.forwarded()).contains("pkb_browser=CHROME_HEADLESS"));
        forwardedFailsClosed("live-check");
    }

    @Test
    void covers_command_ui() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("ui", temp.toString(), "--run-id=run-1", "--agent=ada");
        assertEquals("ui", parsed.command());
        assertTrue(WorkbenchCommandLine.isForwardedCommand("ui"));
        assertEquals("run-1", parsed.coordination().runId());
        assertEquals("ada", parsed.coordination().agentId());
        forwardedFailsClosed("ui");
    }

    @Test
    void covers_command_mcp() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("mcp", temp.toString());
        assertEquals("mcp", parsed.command());
        assertTrue(WorkbenchCommandLine.isForwardedCommand("mcp"));
        assertFalse(WorkbenchCommandLine.isSessionClientCommand("mcp"));
        forwardedFailsClosed("mcp");
    }

    @Test
    void covers_command_isolate() throws Exception {
        AtomicReference<WorkbenchSessionCommands.SessionLaunch> launch = new AtomicReference<>();
        Capture capture = session(command -> {
            launch.set(command);
            return new ProcessBuilder("true").start();
        }, "isolate", temp.toString(), "--tags=@one", "--name=The one", "--runvar=pkb_environment=QA", "--run-id=iso-1");
        assertEquals(1, capture.code);
        assertTrue(capture.err.contains("exited before becoming healthy") || capture.err.contains("Session start failed"), capture.err);
        assertEquals("@one", launch.get().tags());
        assertEquals("The one", launch.get().name());
        assertEquals(List.of("pkb_environment=QA"), launch.get().runVars());
        assertTrue(Files.isRegularFile(AgentCoordination.runDirectory(temp, launch.get().runId()).resolve("record.json")));
    }

    @Test
    void covers_command_session() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("session", temp.toString());
        assertEquals("session", parsed.command());
        assertTrue(WorkbenchCommandLine.isForwardedCommand("session"));
        assertFalse(WorkbenchCommandLine.isAgentCoreCommand("session"));
        forwardedFailsClosed("session");
    }

    @Test
    void covers_command_session_start() throws Exception {
        AtomicReference<WorkbenchSessionCommands.SessionLaunch> launch = new AtomicReference<>();
        Capture capture = session(command -> {
            launch.set(command);
            return new ProcessBuilder("true").start();
        }, "session-start", temp.toString(), "--agent-id=ada", "--example=2");
        assertEquals(1, capture.code);
        assertEquals("2", launch.get().example());
        assertEquals("ada", launch.get().agentId());
    }

    @Test
    void covers_command_export_guidance() throws Exception {
        Path out = temp.resolve("guidance");
        Capture capture = agent("export-guidance", out.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.isRegularFile(out.resolve("AGENT-GUIDE.md")));
        assertTrue(capture.out.contains("NEXT: follow AGENT-GUIDE"));
    }

    @Test
    void covers_command_hint() throws Exception {
        Files.createDirectories(temp.resolve("src/test/resources"));
        Files.writeString(temp.resolve("src/test/resources/pickleball.properties"), "pkb_browser=EDGE\n");
        Capture capture = agent("hint", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("NEXT: run discover"));
        assertTrue(capture.out.contains("pkb_browser=EDGE"));
        assertTrue(capture.out.contains("Dry-run resolve"));
    }

    @Test
    void covers_command_discover_hint() throws Exception {
        Capture capture = agent("discover-hint", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("NEXT: run discover"));
        assertTrue(capture.out.contains("not the Discover worker"));
    }

    @Test
    void covers_command_discover() throws Exception {
        writeFeature("@smoke", "Find term");
        writeWrapper();
        Capture capture = discover(0, "discover", temp.toString(), "--tags=@missing", "--run-id=disc-empty");
        assertEquals(1, capture.code);
        assertTrue(capture.out.contains("status=EMPTY"), capture.out);
        String record = Files.readString(AgentCoordination.runDirectory(temp, "disc-empty").resolve("record.json"));
        assertTrue(record.contains("EMPTY"), record);
        assertFalse(record.contains("PASSED"), record);
    }

    @Test
    void covers_command_confirm() throws Exception {
        writeFeature("@smoke", "Find term");
        writeWrapper();
        Capture missing = agent("confirm", temp.toString(), "--run-id=confirm-missing");
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("Discover"), missing.err);

        discover(0, "discover", temp.toString(), "--tags=@smoke", "--run-id=disc-ok");
        Capture confirm = discover(0, "confirm", temp.toString(), "--run-id=confirm-ok", "--agent=ada");
        assertEquals(0, confirm.code, confirm.err + confirm.out);
        assertTrue(confirm.out.contains("retained pkb_run_profile") || confirm.out.contains("workspace="), confirm.out);
    }

    @Test
    void covers_command_plan() throws Exception {
        writeFeature("@smoke", "Find term");
        Capture capture = agent("plan", temp.toString(), "--tags=@smoke", "--name=Find term");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("PLAN 1 scenario"), capture.out);
        assertTrue(capture.out.contains("Find term"), capture.out);
    }

    @Test
    void covers_command_resolve_runvars() throws Exception {
        Files.createDirectories(temp.resolve("src/test/resources"));
        Files.writeString(temp.resolve("src/test/resources/pickleball.properties"), "pkb_parallel=4\npkb_name=caf\u00e9\n");
        Capture capture = agent("resolve-runvars", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("sealed="));
        assertTrue(capture.out.contains("runProfileFingerprint="));
        assertFalse(capture.out.contains("NEXT: run discover"));
        assertTrue(capture.out.contains("caf\u00e9") || capture.out.contains("pkb_parallel=4"), capture.out);
    }

    @Test
    void covers_command_workspace_show() throws Exception {
        Capture missing = agent("workspace-show", temp.toString(), "--run-id=missing-run");
        assertEquals(1, missing.code);
        assertTrue(missing.out.contains("present=false"), missing.out);
        seed("shown");
        Capture shown = agent("workspace-show", temp.toString(), "--run-id=shown");
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("present=true"));
        assertTrue(shown.out.contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(shown.out.contains("fingerprint="));
    }

    @Test
    void covers_command_workspace_edit() throws Exception {
        seed("edit-1");
        String fingerprint = Workspace.fingerprint(Workspace.directory(temp, "edit-1"));
        Capture capture = agent(
                "workspace-edit", temp.toString(), "--run-id=edit-1",
                "--expect=" + fingerprint, "--runvar=pkb_browser=CHROME", "--runvar=pkb_name=caf\u00e9"
        );
        assertEquals(0, capture.code, capture.err);
        Map<String, String> values = Workspace.readRunVars(Workspace.directory(temp, "edit-1"));
        assertEquals("CHROME", values.get("pkb_browser"));
        assertEquals("caf\u00e9", values.get("pkb_name"));
        Capture stale = agent(
                "workspace-edit", temp.toString(), "--run-id=edit-1",
                "--expect=not-the-fingerprint", "--runvar=pkb_browser=EDGE"
        );
        assertEquals(3, stale.code);
        assertTrue(stale.err.contains("Nothing was written"));
        assertEquals("CHROME", Workspace.readRunVars(Workspace.directory(temp, "edit-1")).get("pkb_browser"));
    }

    @Test
    void covers_command_workspace_seed() {
        Capture capture = agent("workspace-seed", temp.toString(), "--run-id=seed-1");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("present=true"));
        assertTrue(Workspace.exists(Workspace.directory(temp, "seed-1")));
    }

    @Test
    void covers_command_short_log() throws Exception {
        Capture empty = agent("short-log", temp.toString());
        assertEquals(0, empty.code);
        assertTrue(empty.out.contains("(no short-log lines)"));
        agent("note", temp.toString(), "--text=started the caf\u00e9 run", "--agent=ada", "--run-id=log-1");
        Capture capture = agent("short-log", temp.toString(), "--limit=5");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("caf\u00e9") || capture.out.contains("note"), capture.out);
    }

    @Test
    void covers_command_note() throws Exception {
        Capture missing = agent("note", temp.toString());
        assertEquals(2, missing.code);
        assertTrue(missing.err.contains("Usage: note"));
        Capture capture = agent("note", temp.toString(), "--text=one line", "--agent=ada", "--run-id=note-1");
        assertEquals(0, capture.code, capture.err);
        String log = Files.readString(temp.resolve(".pickleball/agent-log"));
        assertTrue(log.contains("one line"), log);
    }

    @Test
    void covers_command_inbox() throws Exception {
        Capture wrote = agent("inbox", temp.toString(), "--write", "--to=ada", "--from=bea", "--text=look here", "--run-id=in-1");
        assertEquals(0, wrote.code, wrote.err);
        assertTrue(wrote.out.contains("inbox="));
        Capture listed = agent("inbox", temp.toString(), "--list", "--agent=ada");
        assertEquals(0, listed.code, listed.err);
        assertTrue(listed.out.contains("look here"), listed.out);
        Capture taken = agent("inbox", temp.toString(), "--take", "--agent=ada");
        assertEquals(0, taken.code, taken.err);
        assertTrue(taken.out.contains("look here"));
        Capture gone = agent("inbox", temp.toString(), "--list", "--agent=ada");
        assertTrue(gone.out.contains("(no inbox notes)"));
    }

    @Test
    void covers_command_finish() throws Exception {
        AgentCoordination.begin(temp, AgentCoordination.Request.of("fin-1", "ada", null, null, null, null, "discover"));
        Capture capture = agent("finish", temp.toString(), "--run-id=fin-1", "--learned=stopped on purpose", "--status=FAILED");
        assertEquals(0, capture.code, capture.err);
        String record = Files.readString(AgentCoordination.runDirectory(temp, "fin-1").resolve("record.json"));
        assertTrue(record.contains("FAILED"), record);
        assertTrue(record.contains("stopped on purpose"), record);
        assertFalse(record.contains("\"status\" : \"STOPPED\""), record);
    }

    @Test
    void covers_command_presence() throws Exception {
        Capture touch = agent("presence", temp.toString(), "--touch", "--agent=ada", "--run-id=pres-1");
        assertEquals(0, touch.code, touch.err);
        assertTrue(touch.out.contains("presence="));
        Capture list = agent("presence", temp.toString(), "--list");
        assertEquals(0, list.code, list.err);
        assertTrue(list.out.contains("ada"), list.out);
        Capture sweep = agent("presence", temp.toString(), "--sweep");
        assertEquals(0, sweep.code, sweep.err);
        assertTrue(sweep.out.contains("presence-removed="));
    }

    @Test
    void covers_command_post() {
        Capture wrote = agent("post", temp.toString(), "--write", "--to=all", "--from=ada", "--text=board note", "--ttl=1h");
        assertEquals(0, wrote.code, wrote.err);
        assertTrue(wrote.out.startsWith("post="));
        String id = wrote.out.substring(wrote.out.indexOf("post=") + 5).trim();
        Path file = Path.of(id);
        assertTrue(Files.isRegularFile(file), id);
        String storedId = file.getFileName().toString().replace(".json", "");
        Capture listed = agent("post", temp.toString(), "--list");
        assertTrue(listed.out.contains("board note"), listed.out);
        Capture renewed = agent("post", temp.toString(), "--renew", "--id=" + storedId, "--ttl=30m");
        assertEquals(0, renewed.code, renewed.err);
        Capture sweep = agent("post", temp.toString(), "--sweep");
        assertTrue(sweep.out.contains("posts-removed="));
    }

    @Test
    void covers_command_history() throws Exception {
        Capture append = agent("history", temp.toString(), "--append", "--text=caf\u00e9 happened");
        assertEquals(0, append.code, append.err);
        assertTrue(Files.isRegularFile(temp.resolve(".pickleball/history.log")));
        Capture tail = agent("history", temp.toString(), "--limit=2");
        assertEquals(0, tail.code, tail.err);
        assertTrue(tail.out.contains("caf\u00e9 happened"), tail.out);
    }

    @Test
    void covers_command_gc_runs() {
        Capture capture = agent("gc-runs", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("profiles-removed="));
        assertTrue(capture.out.contains("payloads-removed="));
    }

    @Test
    void covers_command_gc_versions() {
        Capture capture = agent("gc-versions", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("versions-removed="));
    }

    @Test
    void covers_command_use_version() {
        Capture usage = agent("use-version", temp.toString());
        assertEquals(2, usage.code);
        assertTrue(usage.err.contains("--version"));
        Capture missing = agent("use-version", temp.toString(), "--version=2.1.18");
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("export-guidance"), missing.err);
        assertTrue(missing.err.contains("current.json was not changed"));
    }

    @Test
    void covers_command_inspect_page() throws Exception {
        Path html = writePage();
        Capture capture = agent("inspect-page", temp.toString(), "--html=" + html);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("source=offline-html"));
        assertTrue(capture.out.contains("the \"Save\" Button"), capture.out);
    }

    @Test
    void covers_command_find_element() throws Exception {
        Path html = writePage();
        Capture capture = agent("find-element", temp.toString(), "--category=Button", "--text=Save", "--html=" + html);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("source=offline-html"));
        assertTrue(capture.out.contains("xpath="));
        Capture usage = agent("find-element", temp.toString());
        assertEquals(2, usage.code);
        assertTrue(usage.err.contains("--category"));
    }

    @Test
    void covers_command_session_list() {
        publishSession("session-list-1", "run-list", null, null);
        Capture text = agent("session", "list", temp.toString());
        assertEquals(0, text.code, text.err);
        assertTrue(text.out.contains("session-list-1"), text.out);
        assertTrue(text.out.contains("running"));
        Capture json = agent("session", "list", "--json", temp.toString());
        assertEquals(0, json.code, json.err);
        assertTrue(json.out.trim().startsWith("["));
        assertTrue(json.out.contains("session-list-1"));
    }

    @Test
    void covers_command_session_info() throws Exception {
        publishSession("session-info-1", "run-info", null, null);
        Capture plain = agent("session", "info", "session-info-1", temp.toString());
        assertEquals(0, plain.code, plain.err);
        assertEquals(Files.readString(SessionDescriptorWriter.file(temp, "run-info")), plain.out);
        publishSession("session-composite", "run-composite",
                temp.resolve("composite").toAbsolutePath().normalize().toString(), "comp-1");
        Capture on = agent("session", "info", "session-composite", "--json", temp.toString());
        assertTrue(on.out.contains("compositeSessionDir"), on.out);
        assertTrue(on.out.contains("comp-1"));
        assertFalse(plain.out.contains("comp-1"));
    }

    @Test
    void covers_command_session_hold() throws Exception {
        publishSession("session-hold-1", "run-hold", null, null);
        Capture capture = agent("session", "hold", "session-hold-1", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(SessionDescriptorWriter.file(temp, "run-hold")).contains("\"state\": \"held\""));
    }

    @Test
    void covers_command_session_release() throws Exception {
        publishSession("session-rel-1", "run-rel", null, null);
        agent("session", "hold", "session-rel-1", temp.toString());
        Capture capture = agent("session", "release", "session-rel-1", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(SessionDescriptorWriter.file(temp, "run-rel")).contains("\"state\": \"running\""));
    }

    @Test
    void covers_command_execute_step() {
        Capture usage = session((launch) -> new ProcessBuilder("true").start(), "execute-step", temp.toString());
        assertEquals(2, usage.code);
        assertTrue(usage.err.contains("Usage: execute-step"), usage.err);
        Capture missing = session((launch) -> new ProcessBuilder("true").start(),
                "execute-step", temp.toString(), "--text=Given a step", "--ack-only");
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("No healthy Workbench session"), missing.err);
    }

    @Test
    void covers_command_status() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "status", temp.toString());
        assertEquals(1, capture.code);
        assertTrue(capture.err.contains("No healthy Workbench session"), capture.err);
    }

    @Test
    void covers_command_events() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "events", temp.toString());
        assertEquals(1, capture.code);
        assertTrue(capture.err.contains("No healthy Workbench session"), capture.err);
    }

    @Test
    void covers_command_stop() throws Exception {
        AgentCoordination.begin(temp, AgentCoordination.Request.of("stop-1", "ada", null, null, null, null, "isolate"));
        Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                "stop", temp.toString(), "--run-id=stop-1", "--learned=user stop");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("already-stopped"), capture.out);
        String record = Files.readString(AgentCoordination.runDirectory(temp, "stop-1").resolve("record.json"));
        assertTrue(record.contains("STOPPED"), record);
    }

    @Test
    void covers_command_kill() throws Exception {
        AgentCoordination.begin(temp, AgentCoordination.Request.of("kill-1", "ada", null, null, null, null, "isolate"));
        Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                "kill", temp.toString(), "--run-id=kill-1", "--force");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("already-stopped") || capture.out.contains("killed"), capture.out);
        assertTrue(Files.readString(AgentCoordination.runDirectory(temp, "kill-1").resolve("record.json")).contains("STOPPED"));
    }

    @Test
    void covers_command_open_scenario() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "open-scenario", temp.toString());
        assertEquals(2, capture.code);
        assertTrue(capture.err.contains("Usage: open-scenario"), capture.err);
    }

    @Test
    void covers_command_example() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "example", temp.toString());
        assertEquals(2, capture.code);
        assertTrue(capture.err.contains("Usage: example"), capture.err);
    }

    @Test
    void covers_command_play() throws Exception {
        assertUnhealthy("play");
        assertHeldCommand("play", "\"op\":\"play\"");
    }

    @Test
    void covers_command_from_here() throws Exception {
        assertUnhealthy("from-here", "--text=Given next");
        assertHeldCommand("from-here", "--text=Given next", "\"op\":\"from-here\"", "\"text\":\"Given next\"");
    }

    @Test
    void covers_command_pause() throws Exception {
        assertUnhealthy("pause");
        assertHeldCommand("pause", "\"op\":\"pause\"");
    }

    @Test
    void covers_command_insert_step() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "insert-step", temp.toString());
        assertEquals(2, capture.code);
        assertTrue(capture.err.contains("Usage: insert-step"), capture.err);
    }

    @Test
    void covers_command_update_step() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                "update-step", temp.toString(), "--text=Given edited");
        assertEquals(1, capture.code);
        assertTrue(capture.err.contains("No healthy Workbench session"), capture.err);
    }

    @Test
    void covers_command_diagnostic_run() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "diagnostic-run", temp.toString());
        assertEquals(2, capture.code);
        assertTrue(capture.err.contains("Usage: diagnostic-run"), capture.err);
    }

    @Test
    void covers_command_save() throws Exception {
        assertUnhealthy("save");
        assertHeldCommand("save", "\"op\":\"save\"");
    }

    @Test
    void covers_command_refresh() throws Exception {
        assertUnhealthy("refresh");
        assertHeldCommand("refresh", "\"op\":\"refresh\"");
    }

    @Test
    void covers_command_session_sync() throws Exception {
        assertUnhealthy("session-sync");
        assertHeldCommand("session-sync", "\"op\":\"session-sync\"");
    }

    @Test
    void covers_command_worker_start() throws Exception {
        assertUnhealthy("worker-start");
        assertHeldCommand("worker-start", "\"op\":\"worker-start\"");
    }

    @Test
    void covers_command_worker_restart() throws Exception {
        assertUnhealthy("worker-restart");
        assertHeldCommand("worker-restart", "\"op\":\"worker-restart\"");
    }

    @Test
    void covers_command_worker_stop() throws Exception {
        assertUnhealthy("worker-stop");
        assertHeldCommand("worker-stop", "\"op\":\"worker-stop\"");
    }

    @Test
    void covers_command_open_window() throws Exception {
        Capture usage = session(
                (launch) -> new ProcessBuilder("true").start(),
                (WorkbenchSessionCommands.UiStarter) null,
                "open-window", temp.toString()
        );
        assertEquals(2, usage.code);
        assertTrue(usage.err.contains("--run-id"), usage.err);
        AgentCoordination.begin(temp, AgentCoordination.Request.of("win-1", "ada", null, null, null, null, "discover"));
        Capture capture = session(
                (launch) -> new ProcessBuilder("true").start(),
                (project, runId, agentId) -> new ProcessBuilder("true").start(),
                "open-window", temp.toString(), "--run-id=win-1", "--agent=ada"
        );
        assertEquals(1, capture.code);
        assertTrue(capture.out.contains("does not start a test"), capture.out);
        assertTrue(capture.err.contains("exited before") || capture.err.contains("did not become healthy"), capture.err);
    }

    @Test
    void covers_command_close_window() {
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "close-window", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("ACK WINDOW closed"));
        assertTrue(capture.out.contains("short log"));
    }

    @Test
    void covers_command_show_run() throws Exception {
        Capture missing = session((launch) -> new ProcessBuilder("true").start(), "show-run", temp.toString(), "--run-id=missing");
        assertEquals(1, missing.code);
        AgentCoordination.begin(temp, AgentCoordination.Request.of("show-1", "ada", null, null, null, null, "discover"));
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), "show-run", temp.toString(), "--run-id=show-1");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("ACK WINDOW shown"));
        assertTrue(capture.out.contains("read-only"));
        assertTrue(capture.out.contains("does not start a test"));
    }

    @Test
    void covers_flag_tags() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("plan", "--tags=@smoke and @fast");
        assertEquals("@smoke and @fast", parsed.tags());
        IllegalArgumentException split = assertThrows(IllegalArgumentException.class,
                () -> parse("plan", "--tags=@smoke", "and", "@fast"));
        assertTrue(split.getMessage().contains("quoting"));
        assertTrue(split.getMessage().contains("--tags-file") || split.getMessage().contains("--tags-b64"));
        writeFeature("@smoke @fast", "Find term");
        Capture capture = agent("plan", temp.toString(), "--tags=@smoke and @fast");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Find term"), capture.out);
    }

    @Test
    void covers_flag_tags_file() throws Exception {
        Path file = temp.resolve("tags.txt");
        Files.writeString(file, "@a and @b\n");
        WorkbenchCommandLine.Parsed parsed = parse("discover", temp.toString(), "--tags-file=" + file);
        assertEquals("@a and @b", parsed.tags());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> parse("discover", temp.toString(), "--tags-file"));
        assertTrue(missing.getMessage().contains("quoting"));
        writeFeature("@a @b", "Tagged from file");
        Capture capture = agent("plan", temp.toString(), "--tags-file=" + file);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Tagged from file"), capture.out);
    }

    @Test
    void covers_flag_tags_b64() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("@a and @b".getBytes(StandardCharsets.UTF_8));
        WorkbenchCommandLine.Parsed parsed = parse("confirm", "--tags-b64=" + encoded);
        assertEquals("@a and @b", parsed.tags());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> parse("confirm", "--tags-b64=%%%"));
        assertTrue(bad.getMessage().contains("base64"));
        writeFeature("@a @b", "Tagged from base64");
        Capture capture = agent("plan", temp.toString(), "--tags-b64=" + encoded);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Tagged from base64"), capture.out);
    }

    @Test
    void covers_flag_name() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("discover", "--name=The", "failing", "scenario");
        assertEquals("The failing scenario", parsed.name());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> parse("discover", "--name=["));
        assertTrue(bad.getMessage().contains("Name quoting error"));
        writeFeature("@smoke", "Find term");
        Capture capture = agent("plan", temp.toString(), "--name=Find term");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Find term"), capture.out);
        Capture missed = agent("plan", temp.toString(), "--name=Absent");
        assertFalse(missed.out.contains("Find term"), missed.out);
    }

    @Test
    void covers_flag_example() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("isolate", "--example=1", "2", "7-11");
        assertEquals("1 2 7-11", parsed.example());
        assertTrue(List.of(parsed.forwarded()).contains("1 2 7-11"));
        AtomicReference<WorkbenchSessionCommands.SessionLaunch> launch = new AtomicReference<>();
        Capture capture = session(command -> {
            launch.set(command);
            return new ProcessBuilder("true").start();
        }, "isolate", temp.toString(), "--example=4");
        assertEquals(1, capture.code);
        assertEquals("4", launch.get().example());
    }

    @Test
    void covers_flag_plan() {
        WorkbenchCommandLine.Parsed parsed = parse("discover", "--plan", "--tags=@smoke");
        assertTrue(parsed.planOnly());
        Capture capture = agent("discover", temp.toString(), "--plan", "--tags=@none");
        assertEquals(1, capture.code);
        assertTrue(capture.out.contains("PLAN") || capture.err.contains("no scenarios"), capture.out + capture.err);
    }

    @Test
    void covers_flag_refresh() throws Exception {
        assertTrue(parse("plan", "--refresh").refreshIndex());
        writeFeature("@smoke", "Find term");
        Capture capture = agent("plan", temp.toString(), "--refresh", "--tags=@smoke");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Find term"), capture.out);
    }

    @Test
    void covers_flag_no_narrow() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("discover", "--no-narrow", "--tags=@caller");
        assertTrue(parsed.fullScan());
        assertEquals("@caller", parsed.tags());
        writeFeature("@caller", "Caller scenario");
        Capture narrowed = agent("plan", temp.toString(), "--tags=@caller");
        assertEquals(0, narrowed.code, narrowed.err);
        assertTrue(narrowed.out.contains("Caller scenario"), narrowed.out);
        Capture capture = agent("plan", temp.toString(), "--no-narrow", "--tags=@caller");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("PLAN full-scan"), capture.out);
        assertTrue(capture.out.contains("features=full-scan"), capture.out);
        assertTrue(capture.err.contains("--no-narrow"), capture.err);
        assertFalse(capture.out.contains("Caller scenario"), capture.out);
    }

    @Test
    void covers_flag_full_scan() throws Exception {
        assertTrue(parse("confirm", "--full-scan").fullScan());
        writeFeature("@smoke", "Full scan");
        Capture narrowed = agent("plan", temp.toString(), "--tags=@smoke");
        assertEquals(0, narrowed.code, narrowed.err);
        assertTrue(narrowed.out.contains("Full scan"), narrowed.out);
        Capture capture = agent("plan", temp.toString(), "--full-scan", "--tags=@smoke");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("PLAN full-scan"), capture.out);
        assertTrue(capture.out.contains("features=full-scan"), capture.out);
        assertTrue(capture.err.contains("--full-scan"), capture.err);
        assertFalse(capture.out.contains("Full scan"), capture.out);
    }

    @Test
    void covers_flag_runvar() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("discover", "--runvar=pkb_parallel=12", "--runvar", "pkb_browser=CHROME");
        assertEquals(List.of("pkb_parallel=12", "pkb_browser=CHROME"), parsed.runVars());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> parse("discover", "--runvar=browser"));
        assertTrue(bad.getMessage().contains("key=value"));
        writeFeature("@smoke", "Find term");
        writeWrapper();
        Capture capture = discover(0, "discover", temp.toString(), "--tags=@smoke", "--run-id=rv-1", "--runvar=pkb_environment=QA");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertEquals("QA", Workspace.readRunVars(Workspace.directory(temp, "rv-1")).get("pkb_environment"));
    }

    @Test
    void covers_flag_select() throws Exception {
        WorkbenchCommandLine.Parsed parsed = parse("plan", "--select", "features/a.feature:4", "--select=features/b.feature:9");
        assertEquals(List.of("features/a.feature:4", "features/b.feature:9"), parsed.selects());
        Path feature = writeFeature("@smoke", "Selected");
        String selector = feature.toString() + ":3";
        Capture capture = agent("plan", temp.toString(), "--select", selector);
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("Selected"), capture.out);
        assertTrue(capture.out.contains(selector), capture.out);
    }

    @Test
    void covers_flag_retention() throws Exception {
        assertEquals("failed", parse("discover", "--retention=failed").retention());
        assertEquals("none", parse("discover", "--retention", "NONE").retention());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> parse("discover", "--retention=always"));
        assertTrue(bad.getMessage().contains("failed"));
        writeFeature("@smoke", "Find term");
        writeWrapper();
        Capture capture = discover(0, "discover", temp.toString(), "--tags=@smoke", "--run-id=ret-1", "--retention=none");
        assertEquals(0, capture.code, capture.err + capture.out);
        String record = Files.readString(AgentCoordination.runDirectory(temp, "ret-1").resolve("record.json"));
        assertTrue(record.contains("ret-1"), record);
    }

    @Test
    void covers_flag_run_id() throws Exception {
        assertEquals("run-9", parse("finish", "--run-id=run-9").coordination().runId());
        assertEquals("run 9", parse("finish", "--run-id", "run 9").coordination().runId());
        AgentCoordination.begin(temp, AgentCoordination.Request.of("run-9", "ada", null, null, null, null, "discover"));
        Capture capture = agent("finish", temp.toString(), "--run-id=run-9", "--status=STOPPED");
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(AgentCoordination.runDirectory(temp, "run-9").resolve("record.json")).contains("STOPPED"));
    }

    @Test
    void covers_flag_agent() throws Exception {
        assertEquals("ada", parse("note", "--agent=ada").coordination().agentId());
        Capture capture = agent("note", temp.toString(), "--agent=ada", "--text=from-ada");
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(temp.resolve(".pickleball/agent-log")).contains("from-ada"), capture.out);
    }

    @Test
    void covers_flag_agent_id() throws Exception {
        assertEquals("bea", parse("discover", "--agent-id=bea").coordination().agentId());
        Capture capture = agent("note", temp.toString(), "--agent-id=bea", "--text=from-bea");
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(temp.resolve(".pickleball/agent-log")).contains("from-bea"), capture.out);
    }

    @Test
    void covers_flag_group() throws Exception {
        assertEquals("wave", parse("discover", "--group=wave").coordination().group());
        writeWrapper();
        Capture capture = discover(0, "discover", temp.toString(), "--tags=@missing", "--run-id=grp-1", "--group=wave");
        String record = Files.readString(AgentCoordination.runDirectory(temp, "grp-1").resolve("record.json"));
        assertTrue(record.contains("wave"), record + capture.out);
    }

    @Test
    void covers_flag_sequence() throws Exception {
        assertEquals(3, parse("discover", "--sequence=3").coordination().sequence());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> parse("discover", "--sequence=no"));
        assertTrue(bad.getMessage().contains("whole number"));
        writeWrapper();
        discover(0, "discover", temp.toString(), "--tags=@missing", "--run-id=seq-1", "--sequence=3");
        String record = Files.readString(AgentCoordination.runDirectory(temp, "seq-1").resolve("record.json"));
        assertTrue(record.contains("3"), record);
    }

    @Test
    void covers_flag_who() throws Exception {
        assertEquals("Dan She'll", parse("note", "--who=Dan", "She'll").coordination().who());
        writeWrapper();
        discover(0, "discover", temp.toString(), "--tags=@missing", "--run-id=who-1", "--who=Dan She'll");
        String record = Files.readString(AgentCoordination.runDirectory(temp, "who-1").resolve("record.json"));
        assertTrue(record.contains("Dan She'll"), record);
    }

    @Test
    void covers_flag_why() throws Exception {
        assertEquals("check the gap", parse("discover", "--why=check", "the", "gap").coordination().why());
        writeWrapper();
        discover(0, "discover", temp.toString(), "--tags=@missing", "--run-id=why-1", "--why=check the gap");
        String record = Files.readString(AgentCoordination.runDirectory(temp, "why-1").resolve("record.json"));
        assertTrue(record.contains("check the gap"), record);
    }

    @Test
    void covers_flag_learned() throws Exception {
        assertEquals("index was stale", parse("finish", "--learned=index", "was", "stale").coordination().learned());
        AgentCoordination.begin(temp, AgentCoordination.Request.of("learn-1", "ada", null, null, null, null, "discover"));
        Capture capture = agent("finish", temp.toString(), "--run-id=learn-1", "--learned=index was stale", "--status=STOPPED");
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(AgentCoordination.runDirectory(temp, "learn-1").resolve("record.json")).contains("index was stale"));
    }

    @Test
    void covers_flag_text() throws Exception {
        assertEquals("Given a step", parse("execute-step", "--text=Given", "a", "step").coordination().text());
        assertHeldCommand("execute-step", "--text=Given a step", "\"text\":\"Given a step\"");
    }

    @Test
    void covers_flag_to() throws Exception {
        assertEquals("ada", parse("inbox", "--to=ada").coordination().inboxTo());
        Capture capture = agent("inbox", temp.toString(), "--write", "--to=ada", "--text=ping-ada", "--from=bea");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("inbox="), capture.out);
        Capture listed = agent("inbox", temp.toString(), "--list", "--agent=ada");
        assertTrue(listed.out.contains("ping-ada"), listed.out);
    }

    @Test
    void covers_flag_from() throws Exception {
        assertEquals("bea", parse("inbox", "--from=bea").coordination().inboxFrom());
        Capture capture = agent("inbox", temp.toString(), "--write", "--to=ada", "--from=bea", "--text=from-bea");
        assertEquals(0, capture.code, capture.err);
        Capture listed = agent("inbox", temp.toString(), "--list", "--agent=ada");
        assertTrue(listed.out.contains("bea"), listed.out);
        assertTrue(listed.out.contains("from-bea"), listed.out);
    }

    @Test
    void covers_flag_limit() throws Exception {
        assertEquals(7, parse("short-log", "--limit=7").coordination().limit());
        agent("note", temp.toString(), "--agent=ada", "--text=limit-line");
        Capture capture = agent("short-log", temp.toString(), "--limit=1");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("limit-line") || capture.out.contains("short-log"), capture.out);
    }

    @Test
    void covers_flag_write() throws Exception {
        assertTrue(parse("inbox", "--write", "--text=hi").coordination().inboxWrite());
        Capture capture = agent("inbox", temp.toString(), "--write", "--to=ada", "--text=written-note");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("inbox="), capture.out);
        assertTrue(capture.out.contains(".pickleball"), capture.out);
    }

    @Test
    void covers_flag_list() throws Exception {
        assertTrue(parse("presence", "--list").coordination().inboxList());
        Capture empty = agent("presence", temp.toString(), "--list");
        assertEquals(0, empty.code, empty.err);
        assertTrue(empty.out.contains("no presence") || empty.out.contains("presence"), empty.out);
    }

    @Test
    void covers_flag_take() throws Exception {
        assertTrue(parse("inbox", "--take").coordination().inboxTake());
        agent("inbox", temp.toString(), "--write", "--to=ada", "--text=take-me");
        Capture taken = agent("inbox", temp.toString(), "--take", "--agent=ada");
        assertEquals(0, taken.code, taken.err);
        assertTrue(taken.out.contains("take-me"), taken.out);
        Capture again = agent("inbox", temp.toString(), "--list", "--agent=ada");
        assertTrue(again.out.contains("no inbox"), again.out);
    }

    @Test
    void covers_flag_touch() throws Exception {
        assertTrue(parse("presence", "--touch").coordination().touch());
        Capture capture = agent("presence", temp.toString(), "--touch", "--agent=ada");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("presence="), capture.out);
        assertTrue(Files.exists(Path.of(capture.out.substring(capture.out.indexOf("presence=") + "presence=".length()).trim())), capture.out);
    }

    @Test
    void covers_flag_sweep() throws Exception {
        assertTrue(parse("gc-runs", "--sweep").coordination().sweep());
        assertTrue(parse("presence", "--sweep").coordination().sweep());
        agent("presence", temp.toString(), "--touch", "--agent=ada");
        Capture capture = agent("presence", temp.toString(), "--sweep");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("presence-removed="), capture.out);
    }

    @Test
    void covers_flag_renew() throws Exception {
        assertTrue(parse("post", "--renew").coordination().renew());
        Capture written = agent("post", temp.toString(), "--write", "--to=all", "--text=renew-me", "--ttl=1h");
        assertEquals(0, written.code, written.err + written.out);
        String writtenPath = written.out.substring(written.out.indexOf("post=") + "post=".length()).trim();
        String id = Path.of(writtenPath).getFileName().toString().replace(".json", "");
        String before = Files.readString(Path.of(writtenPath));
        Capture renewed = agent("post", temp.toString(), "--renew", "--id=" + id, "--ttl=2h");
        assertEquals(0, renewed.code, renewed.err + renewed.out);
        assertTrue(renewed.out.contains(id), renewed.out);
        String after = Files.readString(Path.of(writtenPath));
        assertFalse(before.equals(after), after);
        assertTrue(after.contains("renew-me"), after);
    }

    @Test
    void covers_flag_append() throws Exception {
        assertTrue(parse("history", "--append").coordination().append());
        Capture capture = agent("history", temp.toString(), "--append", "--text=history-line");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("history="), capture.out);
        assertTrue(Files.readString(AgentCoordination.historyFile(temp)).contains("history-line"));
    }

    @Test
    void covers_flag_id() throws Exception {
        assertEquals("post-1", parse("post", "--id=post-1").coordination().id());
        Capture written = agent("post", temp.toString(), "--write", "--to=all", "--text=identified", "--ttl=1h");
        assertEquals(0, written.code, written.err + written.out);
        String writtenPath = written.out.substring(written.out.indexOf("post=") + "post=".length()).trim();
        String id = Path.of(writtenPath).getFileName().toString().replace(".json", "");
        Capture missing = agent("post", temp.toString(), "--renew", "--id=post-1");
        assertEquals(1, missing.code);
        assertTrue(missing.err.contains("No post post-1"), missing.err);
        Capture listed = agent("post", temp.toString(), "--list");
        assertTrue(listed.out.contains(id), listed.out);
        assertTrue(listed.out.contains("identified"), listed.out);
    }

    @Test
    void covers_flag_version() throws Exception {
        assertEquals("2.1.18", parse("use-version", "--version=2.1.18").version());
        Capture capture = agent("use-version", temp.toString(), "--version=2.1.18");
        assertTrue(capture.code == 0 || capture.err.contains("2.1.18") || capture.out.contains("2.1.18"), capture.out + capture.err);
    }

    @Test
    void covers_flag_ttl() {
        assertEquals("1h", parse("post", "--ttl=1h").coordination().ttl());
        Capture bad = agent("post", temp.toString(), "--write", "--to=all", "--text=x", "--ttl=later");
        assertEquals(2, bad.code);
        assertTrue(bad.err.contains("ttl"), bad.err);
    }

    @Test
    void covers_flag_step_timeout() {
        assertEquals(5L, parse("execute-step", "--step-timeout=5").stepTimeoutSeconds());
        Capture rejected = agent("discover", temp.toString(), "--step-timeout=5");
        assertEquals(2, rejected.code);
        assertTrue(rejected.err.contains("does not accept --step-timeout"), rejected.err);
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> parse("execute-step", "--step-timeout=0"));
        assertTrue(bad.getMessage().contains("positive"));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void covers_flag_run_timeout() throws Exception {
        assertEquals(12L, parse("discover", "--run-timeout=12").runTimeoutSeconds());
        writeWrapper();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", temp.toString(), "--run-timeout=1", "--run-id=budget", "--tags=@none"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("TIMEOUT"));
        assertTrue(Files.readString(AgentCoordination.runDirectory(temp, "budget").resolve("record.json")).contains("TIMEOUT"));
    }

    @Test
    void covers_flag_ack_only() throws Exception {
        WorkbenchSessionCommands.SessionFlags flags = WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"execute-step", "--text=Given one", "--ack-only"}
        );
        assertTrue(flags.ackOnly);
        assertFalse(flags.wait);
        try (HeldSession held = HeldSession.open(temp)) {
            held.succeedAfter = false;
            Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                    "play", temp.toString(), "--ack-only");
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(capture.out.contains("ACK "), capture.out);
            assertFalse(capture.out.contains("DONE "), capture.out);
            assertTrue(held.body().contains("\"op\":\"play\""), held.body());
        }
    }

    @Test
    void covers_flag_wait() throws Exception {
        WorkbenchSessionCommands.SessionFlags flags = WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"execute-step", "--no-wait", "--wait", "--text=Given one"}
        );
        assertTrue(flags.wait);
        try (HeldSession held = HeldSession.open(temp)) {
            held.succeedAfter = true;
            Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                    "pause", temp.toString(), "--wait");
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(capture.out.contains("DONE "), capture.out);
            assertTrue(capture.out.contains("SUCCESS"), capture.out);
            assertTrue(held.body().contains("\"op\":\"pause\""), held.body());
        }
    }

    @Test
    void covers_flag_no_wait() throws Exception {
        WorkbenchSessionCommands.SessionFlags flags = WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"play", "--no-wait"}
        );
        assertFalse(flags.wait);
        try (HeldSession held = HeldSession.open(temp)) {
            Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                    "refresh", temp.toString(), "--no-wait");
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(capture.out.contains("ACK "), capture.out);
            assertFalse(capture.out.contains("DONE "), capture.out);
            assertTrue(held.body().contains("\"op\":\"refresh\""), held.body());
        }
    }

    @Test
    void covers_flag_force() throws Exception {
        assertTrue(WorkbenchSessionCommands.SessionFlags.parse(new String[]{"kill", "--force"}).force);
        Process sleep = new ProcessBuilder("sleep", "30").start();
        try (HeldSession held = HeldSession.open(temp)) {
            held.setPid(sleep.pid());
            Capture capture = session((launch) -> new ProcessBuilder("true").start(),
                    "kill", temp.toString(), "--force");
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(capture.out.contains("ACK SESSION killed pid=" + sleep.pid()), capture.out);
            assertFalse(sleep.isAlive());
        } finally {
            if (sleep.isAlive()) sleep.destroyForcibly();
        }
    }

    @Test
    void covers_flag_stop_after() throws Exception {
        WorkbenchSessionCommands.SessionFlags flags = WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"play", "--stop-after=Then", "done"}
        );
        assertEquals("Then done", flags.stopAfter);
        assertHeldCommand("play", "--stop-after=Then done", "--ack-only", "\"stopAfter\":\"Then done\"");
    }

    @Test
    void covers_flag_feature() throws Exception {
        assertEquals("login.feature", WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"open-scenario", "--feature=login.feature"}
        ).feature);
        assertHeldCommand("open-scenario", "--feature=login.feature", "--ack-only", "\"feature\":\"login.feature\"");
    }

    @Test
    void covers_flag_run() throws Exception {
        assertEquals("run-9", WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"diagnostic-run", "--run=run-9"}
        ).run);
        assertHeldCommand("diagnostic-run", "--run=run-9", "--ack-only", "\"run\":\"run-9\"");
    }

    @Test
    void covers_flag_from_here() throws Exception {
        assertEquals("Given next", WorkbenchSessionCommands.SessionFlags.parse(
                new String[]{"from-here", "--from-here=Given next"}
        ).fromHere);
        assertHeldCommand("from-here", "--from-here=Given next", "--ack-only", "\"fromHere\":\"Given next\"");
    }

    @Test
    void covers_flag_session_file() throws Exception {
        Path sessionFile = temp.resolve("custom-session.json");
        WorkbenchCommandLine.Parsed parsed = parse(
                "isolate", temp.toString(), "--session-file=" + sessionFile, "--run-id=sf-1"
        );
        assertTrue(List.of(parsed.forwarded()).contains("--session-file=" + sessionFile),
                List.of(parsed.forwarded()).toString());
        assertEquals("sf-1", parsed.coordination().runId());
        AtomicReference<WorkbenchSessionCommands.SessionLaunch> launch = new AtomicReference<>();
        session(command -> {
            launch.set(command);
            return new ProcessBuilder("true").start();
        }, "isolate", temp.toString(), "--session-file=" + sessionFile, "--run-id=sf-1");
        assertEquals(sessionFile.toAbsolutePath().normalize(), launch.get().sessionFile().toAbsolutePath().normalize());
    }

    @Test
    void covers_flag_json() {
        Capture capture = agent("session", "list", "--json", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertEquals("[]\n", capture.out);
    }

    @Test
    void covers_flag_expect() throws Exception {
        seed("expect-1");
        Capture capture = agent(
                "workspace-edit", temp.toString(), "--run-id=expect-1",
                "--expect=stale", "--runvar=pkb_parallel=2"
        );
        assertEquals(3, capture.code);
        assertTrue(capture.err.contains("fingerprint="));
        assertEquals("auto", Workspace.readRunVars(Workspace.directory(temp, "expect-1")).get("pkb_parallel"));
    }

    @Test
    void covers_flag_config() throws Exception {
        seed("cfg-1");
        String fingerprint = Workspace.fingerprint(Workspace.directory(temp, "cfg-1"));
        Capture capture = agent(
                "workspace-edit", temp.toString(), "--run-id=cfg-1",
                "--expect=" + fingerprint, "--config=CHROME.yaml", "--text=browser: CHROME\n"
        );
        assertEquals(0, capture.code, capture.err);
        Path config = Workspace.configsDirectory(Workspace.directory(temp, "cfg-1")).resolve("CHROME.yaml");
        assertEquals("browser: CHROME\n", Files.readString(config));
    }

    @Test
    void covers_flag_reseed() throws Exception {
        seed("re-1");
        Workspace.writeRunVars(Workspace.directory(temp, "re-1"), Map.of(
                "pkb_glue", "old", "pkb_features", "old", "pkb_datapath", "old",
                "pkb_callpath", "old", "pkb_componentpath", "old", "pkb_configpath", "old",
                "pkb_browser", "EDGE"
        ));
        Capture capture = agent("workspace-seed", temp.toString(), "--run-id=re-1", "--reseed");
        assertEquals(0, capture.code, capture.err);
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(Workspace.directory(temp, "re-1")).get("pkb_browser"));
    }

    @Test
    void covers_flag_from_run() throws Exception {
        seed("from-src");
        Workspace.writeRunVars(Workspace.directory(temp, "from-src"), Map.of(
                "pkb_glue", "g", "pkb_features", "f", "pkb_datapath", "d",
                "pkb_callpath", "c", "pkb_componentpath", "p", "pkb_configpath", "cfg",
                "pkb_browser", "EDGE", "pkb_name", "copied"
        ));
        Capture capture = agent("workspace-seed", temp.toString(), "--run-id=from-dst", "--from-run=from-src");
        assertEquals(0, capture.code, capture.err);
        assertEquals("copied", Workspace.readRunVars(Workspace.directory(temp, "from-dst")).get("pkb_name"));
        assertEquals("EDGE", Workspace.readRunVars(Workspace.directory(temp, "from-dst")).get("pkb_browser"));
    }

    @Test
    void covers_flag_html() throws Exception {
        Path html = writePage();
        Capture capture = agent("inspect-page", "--html=" + html, temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("source=offline-html"));
    }

    @Test
    void covers_flag_category() throws Exception {
        Path html = writePage();
        Capture capture = agent("find-element", "--category=Link", "--text=Login", "--html=" + html, temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("Login"), capture.out);
    }

    @Test
    void covers_flag_status() throws Exception {
        AgentCoordination.begin(temp, AgentCoordination.Request.of("st-1", "ada", null, null, null, null, "discover"));
        Capture bad = agent("finish", temp.toString(), "--run-id=st-1", "--status=MAYBE");
        assertEquals(2, bad.code);
        assertTrue(bad.err.contains("PASSED"), bad.err);
        Capture capture = agent("finish", temp.toString(), "--run-id=st-1", "--status=empty");
        assertEquals(0, capture.code, capture.err);
        assertTrue(Files.readString(AgentCoordination.runDirectory(temp, "st-1").resolve("record.json")).contains("EMPTY"));
    }

    @Test
    void failureIgnoreStillFailsWhenMavenExitsZero() throws Exception {
        writeFeature("@boom", "It fails");
        writeWrapper();
        List<String> seen = new ArrayList<>();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", temp.toString(), "--tags=@boom", "--run-id=ignore-1"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    seen.addAll(command);
                    try {
                        Path index = AgentCoordination.runDirectory(project, "ignore-1")
                                .resolve("diagnostic").resolve("ignore-1").resolve("run-index.json");
                        Files.createDirectories(index.getParent());
                        Files.writeString(index, """
                                {"runId":"ignore-1","outcome":"FAILED","counts":{"failed":1,"errors":0}}
                                """);
                        Path catalog = project.resolve("reports/diagnostic-runs");
                        Files.createDirectories(catalog);
                        Files.writeString(catalog.resolve("run-catalog.json"), """
                                {"runs":[{"runId":"ignore-1","runProfile":"pkb_tags=@boom","lineage":{"runPurpose":"workbench-discover"}}]}
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("summary status=FAILED"));
        assertTrue(seen.stream().anyMatch(item -> item.contains("mvnw") || item.endsWith("mvnw")));
        assertFalse(seen.stream().anyMatch(item -> item.equals("mvn") || item.equals("gradle")));
    }

    @Test
    void windowsPathsSpacesAndWrapperJarStayOneArgument() throws Exception {
        Path project = temp.resolve("My Projects");
        Files.createDirectories(project.resolve(".mvn/wrapper"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        Files.writeString(project.resolve(".mvn/wrapper/maven-wrapper.jar"), "not-a-real-jar");
        Path workspace = project.resolve(".pickleball/runs/run-1/workspace");
        Files.createDirectories(workspace);
        List<String> command = ConsumerMavenTestRunner.command(
                project, workspace, Map.of("pkb_tags", "@a and @b", "pkb_name", "My Name")
        );
        assertEquals("-jar", command.get(1));
        assertTrue(command.get(2).contains("My Projects"), command.toString());
        assertTrue(command.stream().anyMatch(item -> item.equals("-Dpkb_tags=@a and @b")), command.toString());
        assertTrue(command.stream().noneMatch(item -> item.equals("and")));

        Path gradle = temp.resolve("caf\u00e9 build");
        Files.createDirectories(gradle);
        Files.writeString(gradle.resolve("build.gradle"), "\n");
        Path bat = gradle.resolve("gradlew.bat");
        Files.writeString(bat, "@echo off\r\n");
        List<String> gradleCommand = ConsumerMavenTestRunner.command(gradle, workspace, Map.of());
        assertEquals(bat.toString(), gradleCommand.get(0), gradleCommand.toString());
    }

    private Capture last;

    private void forwardedFailsClosed(String command) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                "tools.dscode.launcher.PickleballWorkbenchLauncher",
                command, temp.toString()
        ).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread stdout = new Thread(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(out);
            } catch (IOException ignored) {
                // The process ending closes the pipe.
            }
        });
        Thread stderr = new Thread(() -> {
            try (var input = process.getErrorStream()) {
                input.transferTo(err);
            } catch (IOException ignored) {
                // The process ending closes the pipe.
            }
        });
        stdout.start();
        stderr.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        stdout.join(2_000);
        stderr.join(2_000);
        String text = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertTrue(finished && process.exitValue() != 0, command + "\n" + text);
        assertTrue(text.contains("Could not launch") || text.contains("missing") || text.contains("Workbench"), text);
    }

    private void assertUnhealthy(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = args[0];
        full[1] = temp.toString();
        if (args.length > 1) System.arraycopy(args, 1, full, 2, args.length - 1);
        Capture capture = session((launch) -> new ProcessBuilder("true").start(), full);
        assertEquals(1, capture.code, capture.err);
        assertTrue(capture.err.contains("No healthy Workbench session"), capture.err);
    }

    private void seed(String runId) {
        Capture capture = agent("workspace-seed", temp.toString(), "--run-id=" + runId);
        assertEquals(0, capture.code, capture.err);
    }

    private void publishSession(String sessionId, String runId, String compositeDir, String compositeId) {
        Instant started = Instant.parse("2026-10-29T18:00:00Z");
        SessionDescriptor descriptor = new SessionDescriptor(
                sessionId,
                runId,
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "chrome",
                compositeDir == null ? null : "127.0.0.1:9222",
                null,
                List.of(),
                SessionDescriptor.State.RUNNING,
                started,
                started,
                compositeDir,
                compositeId
        );
        SessionDescriptorWriter.publish(temp, descriptor, true);
    }

    private Path writePage() throws Exception {
        Path html = temp.resolve("page.html");
        Files.writeString(html, """
                <html><body>
                  <button id="save">Save</button>
                  <a href="/login">Login</a>
                </body></html>
                """);
        return html;
    }

    private Path writeFeature(String tag, String scenario) throws Exception {
        Path features = temp.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Path file = features.resolve("one.feature");
        Files.writeString(file, tag + "\nFeature: One\n  Scenario: " + scenario + "\n    Given one\n");
        return file;
    }

    private void writeWrapper() throws Exception {
        Path script = temp.resolve("mvnw");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        script.toFile().setExecutable(true);
        Files.writeString(temp.resolve("pom.xml"), "<project/>\n");
    }

    private Capture discover(int mavenExit, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Path catalog = project.resolve("reports/diagnostic-runs");
                        Files.createDirectories(catalog);
                        String runId = "unknown";
                        for (String arg : args) {
                            if (arg.startsWith("--run-id=")) runId = arg.substring("--run-id=".length());
                        }
                        Files.writeString(catalog.resolve("run-catalog.json"), """
                                {"runs":[{"runId":"%s","runProfile":"pkb_browser=CHROME_HEADLESS, pkb_tags=@smoke","lineage":{"runPurpose":"workbench-%s"}}]}
                                """.formatted(runId, args[0]));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return mavenExit;
                }
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static WorkbenchCommandLine.Parsed parse(String... args) {
        return WorkbenchCommandLine.parse(args);
    }

    private Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        Capture capture = new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        last = capture;
        return capture;
    }

    private Capture session(WorkbenchSessionCommands.DetachedStarter starter, String... args) {
        return session(starter, null, args);
    }

    private Capture session(
            WorkbenchSessionCommands.DetachedStarter starter,
            WorkbenchSessionCommands.UiStarter ui,
            String... args
    ) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                starter,
                java.net.http.HttpClient.newHttpClient(),
                ui == null ? (project, runId, agentId) -> {
                    throw new java.io.IOException("ui starter not used");
                } : ui
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }

    private void assertHeldCommand(String command, String... rest) throws Exception {
        List<String> args = new ArrayList<>();
        args.add(command);
        args.add(temp.toString());
        List<String> needles = new ArrayList<>();
        boolean ack = false;
        for (String token : rest) {
            if (token.indexOf('"') >= 0) {
                needles.add(token);
            } else {
                args.add(token);
                if ("--ack-only".equals(token)) ack = true;
            }
        }
        if (!ack) args.add("--ack-only");
        try (HeldSession held = HeldSession.open(temp)) {
            Capture capture = session((launch) -> new ProcessBuilder("true").start(), args.toArray(String[]::new));
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(capture.out.contains("ACK "), capture.out);
            for (String needle : needles) {
                assertTrue(held.body().contains(needle), held.body() + "\n" + capture.out + capture.err);
            }
        }
    }

    /**
     * Live {@code cli-session.json} for this JVM. {@code /commands} records the posted op.
     */
    private static final class HeldSession implements AutoCloseable {
        private final HttpServer http;
        private final Path stateFile;
        private final StringBuilder seen = new StringBuilder();
        private volatile boolean succeedAfter;
        private volatile long pid = ProcessHandle.current().pid();

        private HeldSession(HttpServer http, Path stateFile) {
            this.http = http;
            this.stateFile = stateFile;
        }

        static HeldSession open(Path project) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HeldSession[] box = new HeldSession[1];
            http.createContext("/health", exchange -> write(exchange, 200, "{\"status\":\"ok\"}"));
            http.createContext("/commands", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                HeldSession current = box[0];
                if (current != null && "POST".equals(exchange.getRequestMethod())) {
                    current.seen.append(body);
                    write(exchange, 200, "{\"id\":\"step-held\",\"status\":\"QUEUED\"}");
                    return;
                }
                String status = current != null && current.succeedAfter ? "SUCCESS" : "STILL_WORKING";
                write(exchange, 200, "{\"id\":\"step-held\",\"status\":\"" + status + "\"}");
            });
            http.start();
            Path stateFile = PickleballLocalLayout.cliSessionState(project);
            Files.createDirectories(stateFile.getParent());
            HeldSession session = new HeldSession(http, stateFile);
            box[0] = session;
            session.writeState(project);
            return session;
        }

        String body() {
            return seen.toString();
        }

        void setPid(long pid) throws IOException {
            this.pid = pid;
            String text = Files.readString(stateFile).replaceAll("\"pid\"\\s*:\\s*\\d+", "\"pid\": " + pid);
            Files.writeString(stateFile, text);
        }

        private void writeState(Path project) throws IOException {
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            Files.writeString(stateFile, """
                    {
                      "url": "%s",
                      "token": "test-token",
                      "pid": %d,
                      "project": "%s",
                      "mode": "cli-session"
                    }
                    """.formatted(url, pid, project.toString().replace("\\", "\\\\").replace("\"", "\\\"")));
        }

        @Override
        public void close() {
            http.stop(0);
            try {
                Files.deleteIfExists(stateFile);
            } catch (IOException ignored) {
                // The next test writes its own session file.
            }
        }

        private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
