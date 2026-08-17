package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.collaboration.StudioActivity;
import tools.dscode.studio.collaboration.StudioActivityPage;
import tools.dscode.studio.collaboration.StudioAgentSession;
import tools.dscode.studio.collaboration.StudioClientKind;
import tools.dscode.studio.collaboration.StudioCollaborationService;
import tools.dscode.studio.collaboration.StudioEditorState;
import tools.dscode.studio.process.ManagedProcessService;
import tools.dscode.studio.process.ManagedProcessSummary;
import tools.dscode.studio.process.ProcessState;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeScenarioStatus;
import tools.dscode.studio.workspace.WorkspaceCheckedWriteResult;
import tools.dscode.studio.workspace.WorkspaceConcurrencyService;
import tools.dscode.studio.workspace.WorkspaceVersionedTextFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Deterministic Phase 4 collaboration conveniences. AI strategy remains client-side. */
public final class StudioCollaborationMcpTools {
    private static final int DEFAULT_PROCESS_WAIT_SECONDS = 120;
    private static final int DEFAULT_RUNTIME_WAIT_SECONDS = 60;
    private static final int MAX_WAIT_SECONDS = 300;

    private final StudioCollaborationService collaboration;
    private final WorkspaceConcurrencyService workspace;
    private final ManagedProcessService processes;
    private final RuntimeBridgeService runtime;

    public StudioCollaborationMcpTools(
            StudioCollaborationService collaboration,
            WorkspaceConcurrencyService workspace,
            ManagedProcessService processes,
            RuntimeBridgeService runtime
    ) {
        this.collaboration = collaboration;
        this.workspace = workspace;
        this.processes = processes;
        this.runtime = runtime;
    }

    @Tool(
            name = "agent_session_start",
            description = "Start a visible Pickleball Studio agent session. The returned id can be attached to collaboration-aware operations and explicit human-visible notes."
    )
    public StudioAgentSession startAgentSession(
            @ToolParam(description = "Human-readable agent/session label. Defaults to Agent.", required = false)
            String name
    ) {
        return collaboration.startAgentSession(name);
    }

    @Tool(
            name = "agent_session_end",
            description = "End a visible Pickleball Studio agent session. This records session completion; it does not infer whether the agent's task succeeded."
    )
    public StudioAgentSession endAgentSession(
            @ToolParam(description = "Agent session id returned by agent_session_start.") String agentSessionId
    ) {
        return collaboration.endAgentSession(agentSessionId);
    }

    @Tool(
            name = "agent_note",
            description = "Publish an explicit human-visible note to Studio activity. Use only for rationale/status text the AI intentionally chooses to share; Studio does not expose private model reasoning."
    )
    public StudioActivity agentNote(
            @ToolParam(description = "Active agent session id.") String agentSessionId,
            @ToolParam(description = "Concise note intentionally shared with the human developer.") String message
    ) {
        return collaboration.note(agentSessionId, message);
    }

    @Tool(
            name = "studio_activity",
            description = "Read the bounded cross-client Studio activity journal using a sequence cursor. It contains observable Studio operations and explicit agent notes, not private model reasoning."
    )
    public StudioActivityPage activity(
            @ToolParam(description = "Return activity after this sequence. Empty starts from retained history.", required = false)
            Long afterSequence,
            @ToolParam(description = "Maximum returned events. Defaults to 100; maximum 500.", required = false)
            Integer limit
    ) {
        return collaboration.activity(afterSequence, limit);
    }

    @Tool(
            name = "studio_agent_sessions",
            description = "List visible Studio agent sessions newest first."
    )
    public List<StudioAgentSession> agentSessions(
            @ToolParam(description = "Include completed sessions. Defaults to false.", required = false)
            Boolean includeInactive
    ) {
        return collaboration.agentSessions(Boolean.TRUE.equals(includeInactive));
    }

    @Tool(
            name = "studio_editor_states",
            description = "List desktop editor presence and unsaved-state metadata visible to Studio. Check this before editing a file that a human may currently have open."
    )
    public List<StudioEditorState> editorStates() {
        return collaboration.editorStates();
    }

    @Tool(
            name = "workspace_read_versioned",
            description = "Read one existing UTF-8 workspace file with a SHA-256 version token for optimistic concurrent editing."
    )
    public WorkspaceVersionedTextFile readVersioned(
            @ToolParam(description = "Workspace-relative existing file path.") String path
    ) {
        return workspace.read(path);
    }

    @Tool(
            name = "workspace_write_file_checked",
            description = "Replace an existing UTF-8 workspace file only when its SHA-256 still matches the version previously read and no human desktop editor reports unsaved changes for that path."
    )
    public WorkspaceCheckedWriteResult writeChecked(
            @ToolParam(description = "Active agent session id.") String agentSessionId,
            @ToolParam(description = "Workspace-relative existing file path.") String path,
            @ToolParam(description = "SHA-256 returned by workspace_read_versioned.") String expectedSha256,
            @ToolParam(description = "Complete replacement UTF-8 content.") String content
    ) {
        collaboration.requireActiveAgent(agentSessionId);
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            throw new IllegalArgumentException("expectedSha256 must not be blank");
        }
        WorkspaceVersionedTextFile current = workspace.read(path);
        StudioEditorState dirtyEditor = collaboration.editorStates().stream()
                .filter(editor -> editor.path().equals(current.path()) && editor.dirty())
                .findFirst()
                .orElse(null);
        if (dirtyEditor != null) {
            WorkspaceCheckedWriteResult blocked = new WorkspaceCheckedWriteResult(
                    current.path(),
                    false,
                    true,
                    expectedSha256,
                    current.sha256(),
                    current.sha256(),
                    0,
                    "A desktop editor has unsaved changes for this file"
            );
            collaboration.record(
                    StudioClientKind.MCP,
                    agentSessionId,
                    "workspace.write.blocked",
                    current.path(),
                    blocked.message()
            );
            return blocked;
        }

        WorkspaceCheckedWriteResult result = workspace.write(current.path(), expectedSha256, content);
        collaboration.record(
                StudioClientKind.MCP,
                agentSessionId,
                result.written() ? "workspace.write" : "workspace.write.conflict",
                result.path(),
                result.message()
        );
        return result;
    }

    @Tool(
            name = "process_wait",
            description = "Deterministically wait for one existing Studio-managed process to leave RUNNING state, up to a bounded timeout. This is a convenience over process_status, not a second process backend."
    )
    public ManagedProcessSummary waitForProcess(
            @ToolParam(description = "Active agent session id.") String agentSessionId,
            @ToolParam(description = "Studio managed process id.") String processId,
            @ToolParam(description = "Wait timeout in seconds. Defaults to 120; maximum 300.", required = false)
            Integer timeoutSeconds
    ) {
        collaboration.requireActiveAgent(agentSessionId);
        int timeout = boundedTimeout(timeoutSeconds, DEFAULT_PROCESS_WAIT_SECONDS);
        collaboration.record(
                StudioClientKind.MCP,
                agentSessionId,
                "process.wait",
                processId,
                "Waiting up to " + timeout + " seconds"
        );
        Instant deadline = Instant.now().plusSeconds(timeout);
        ManagedProcessSummary summary;
        do {
            summary = processes.status(processId);
            if (summary.state() != ProcessState.RUNNING) {
                collaboration.record(
                        StudioClientKind.MCP,
                        agentSessionId,
                        "process.wait.complete",
                        processId,
                        "State: " + summary.state()
                );
                return summary;
            }
            sleep(Duration.ofMillis(200));
        } while (Instant.now().isBefore(deadline));

        collaboration.record(
                StudioClientKind.MCP,
                agentSessionId,
                "process.wait.timeout",
                processId,
                "Still running after " + timeout + " seconds"
        );
        return processes.status(processId);
    }

    @Tool(
            name = "runtime_wait_paused",
            description = "Deterministically wait until a selected Pickleball runtime scenario reports paused=true, up to a bounded timeout. This only polls the existing runtime bridge."
    )
    public RuntimeScenarioStatus waitForRuntimePause(
            @ToolParam(description = "Active agent session id.") String agentSessionId,
            @ToolParam(description = "Runtime session id returned by runtime_start.") String runtimeSessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Scenario id to wait for. Empty selects the first active scenario.", required = false)
            String scenarioId,
            @ToolParam(description = "Wait timeout in seconds. Defaults to 60; maximum 300.", required = false)
            Integer timeoutSeconds
    ) {
        collaboration.requireActiveAgent(agentSessionId);
        int timeout = boundedTimeout(timeoutSeconds, DEFAULT_RUNTIME_WAIT_SECONDS);
        collaboration.record(
                StudioClientKind.MCP,
                agentSessionId,
                "runtime.wait.paused",
                runtimeId,
                "Waiting up to " + timeout + " seconds"
        );
        Instant deadline = Instant.now().plusSeconds(timeout);
        RuntimeScenarioStatus last = null;
        do {
            List<RuntimeScenarioStatus> scenarios = runtime.scenarios(runtimeSessionId, runtimeId);
            last = selectScenario(scenarios, scenarioId);
            if (last != null && last.paused()) {
                collaboration.record(
                        StudioClientKind.MCP,
                        agentSessionId,
                        "runtime.wait.paused.complete",
                        last.scenarioId(),
                        last.stepText()
                );
                return last;
            }
            sleep(Duration.ofMillis(200));
        } while (Instant.now().isBefore(deadline));

        collaboration.record(
                StudioClientKind.MCP,
                agentSessionId,
                "runtime.wait.paused.timeout",
                runtimeId,
                last == null ? "No active scenario" : "Last scenario state was not paused"
        );
        return last;
    }

    private static RuntimeScenarioStatus selectScenario(
            List<RuntimeScenarioStatus> scenarios,
            String scenarioId
    ) {
        if (scenarios == null || scenarios.isEmpty()) {
            return null;
        }
        String selected = scenarioId == null ? "" : scenarioId.trim();
        if (selected.isBlank()) {
            if (scenarios.size() > 1) {
                throw new IllegalArgumentException(
                        "scenarioId is required when more than one scenario is active"
                );
            }
            return scenarios.getFirst();
        }
        return scenarios.stream()
                .filter(scenario -> scenario.scenarioId().equals(selected))
                .findFirst()
                .orElse(null);
    }

    private static int boundedTimeout(Integer requested, int defaultValue) {
        int value = requested == null ? defaultValue : requested;
        if (value < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be greater than zero");
        }
        return Math.min(MAX_WAIT_SECONDS, value);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Studio wait was interrupted", error);
        }
    }
}
