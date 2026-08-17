# Pickleball Studio AI collaboration

Phase 4 makes AI agents additional clients of the same Pickleball Studio environment used by the desktop developer. It does **not** add an AI execution backend or move AI decision policy into Pickleball.

The operating model is:

```text
Human developer                         AI client
      |                                    |
  Studio Swing UI                    Streamable HTTP MCP
      |                                    |
      +---------- same Studio JVM ----------+
                       |
               shared Studio services
                       |
          files / builds / processes
                       |
               RuntimeBridgeService
                       |
             consumer test JVM(s)
```

When the desktop UI launches, it also starts the loopback-only MCP server in the same Spring application context. Desktop and MCP therefore share the same `ManagedProcessService`, build services, `RuntimeBridgeService`, and collaboration state. The desktop **Studio > AI Collaboration...** window displays the local MCP URL and observable shared activity.

The standalone `studio serve` command remains available for headless MCP use.

## Collaboration journal

`StudioCollaborationService` maintains a bounded Studio-session journal shared by the desktop and MCP clients in that server context. It retains at most 1,000 activity events plus bounded agent-session metadata.

Events describe observable Studio operations such as:

- agent session start/end;
- explicit agent notes;
- desktop editor dirty/clean transitions;
- checked file writes and conflicts;
- desktop build/runtime operations;
- existing mutating/high-impact MCP operations such as writes, builds, runtime mutations, service calls, breakpoints, snapshots, and screenshots;
- deterministic process/runtime waits.

The journal does not contain unsaved editor contents, MCP bearer tokens, arbitrary consumer object graphs, or model private reasoning. `agent_note` records only text the AI client explicitly chooses to publish to the human developer.

`studio_activity` uses a sequence cursor and reports a gap when the caller falls behind retained history.

## Agent sessions

Phase 4 adds explicit visible agent sessions:

```text
agent_session_start
agent_session_end
agent_note
studio_agent_sessions
```

An agent session is attribution and collaboration state, not an authorization boundary and not a claim about task success or connection liveness. `active=true` means the client has not explicitly ended that session; all session metadata disappears when the Studio server closes. The MCP endpoint token remains the transport authorization mechanism.

Use the returned agent-session id with collaboration-aware mutation/wait tools. Studio updates `lastActivityAt` when an attributed operation is recorded.

## Concurrent editing

The existing desktop `*` dirty marker and close/reload warnings remain. Phase 4 additionally publishes editor-presence metadata through:

```text
studio_editor_states
```

Published editor state contains:

- workspace-relative path;
- desktop session id;
- dirty/clean state;
- SHA-256 of the last saved version known by that editor;
- update timestamp.

Unsaved editor text is deliberately not published.

For existing files, AI clients should use:

```text
workspace_read_versioned
workspace_write_file_checked
```

`workspace_read_versioned` returns complete text plus a SHA-256 version token. `workspace_write_file_checked` writes only when:

1. the caller provides an active agent-session id;
2. no desktop editor currently reports unsaved changes for that path; and
3. the file SHA-256 still equals the token previously read by the agent.

A conflict is returned as structured data rather than silently overwriting another client's change.

The desktop editor uses the same version-token rule. If a clean editor becomes stale because another client changed the file, the next human save presents **Overwrite disk**, **Reload disk**, and **Cancel** choices. This keeps human intervention explicit.

The original `workspace_write_file` tool remains available for compatibility and for intentional creates/replacements. Collaboration-aware agents should prefer versioned checked writes when editing an existing file that another client may also touch.

## Deterministic workflow conveniences

Phase 4 adds two bounded wait operations intended to reduce MCP polling traffic without embedding troubleshooting policy:

```text
process_wait
runtime_wait_paused
```

`process_wait` waits for an existing Studio-managed process to leave `RUNNING`. It uses `ManagedProcessService`; it does not launch or execute a second process implementation.

`runtime_wait_paused` polls the existing `RuntimeBridgeService` until the selected scenario reports `paused=true`. When more than one scenario is active, `scenarioId` is required rather than guessing a target. It does not create a second debugger/runtime channel.

Both operations have finite caller-controlled timeouts capped at 300 seconds. A timeout returns the latest observable state; it does not decide what the AI should do next.

## MCP surface

Phase 3H exposed 42 tools. Phase 4 adds ten tools, bringing the Studio MCP contract to **52 tools**:

```text
agent_session_start
agent_session_end
agent_note
studio_activity
studio_agent_sessions
studio_editor_states
workspace_read_versioned
workspace_write_file_checked
process_wait
runtime_wait_paused
```

The existing workspace, process, Maven/Gradle, source-navigation, runtime, mapping, browser-evidence, element, service-call, and breakpoint tools remain compatible.

## Human visibility

The desktop **AI Collaboration** window shows:

- the active local MCP endpoint;
- the bounded collaboration activity stream;
- active/completed agent sessions;
- open desktop editor state and unsaved markers.

Existing mutating/high-impact MCP tool invocations are recorded by a callback-provider wrapper without retaining tool inputs, so file contents, arguments, and tokens are not copied into the journal. Runtime hook events remain in the existing Runtime Control event view. Build stdout/stderr remains in the existing desktop output view. Phase 4 does not duplicate those high-volume streams into the collaboration journal.

## AI policy boundary

Pickleball Studio supplies capabilities and evidence. The AI client decides strategy.

Studio does not implement policies such as:

- retry a failed step a fixed number of times;
- replace selectors automatically;
- rewrite scenarios automatically;
- infer which mapping should change;
- declare that a test is fixed.

An AI may compose deterministic Studio operations to inspect, experiment, edit, build, pause, rerun, compare, and repeat. Any rationale the human should see must be sent explicitly through `agent_note` or another user-visible client channel.

## Focused validation

Phase 4 changes Studio orchestration and collaboration while reusing the completed Phase 3 runtime bridge. The Maven consumer control-bridge scenario therefore also carries `@phase4` as a focused compatibility tag.

Recommended validation after applying Phase 4 changes:

```powershell
python scripts/refresh_agent_index.py
python scripts/sync_consumer_guidance.py

python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check

.\gradlew.bat test :pickleball-studio:test :pickleball-studio:verifyBundledStudio publishToMavenLocal

Push-Location .\maven-consumer-project
.\mvnw.cmd -U test `
  "-Dpkb_runvars.pkb_browser=CHROME_HEADLESS" `
  "-Dpkb_runvars.pkb_tags=@phase4"
Pop-Location
```

For final release confidence, run the normal broader consumer suite after the focused tag passes.
