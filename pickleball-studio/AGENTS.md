# Pickleball Studio Agent Contract

Root `AGENTS.md` applies. For Studio collaboration also read `docs/studio-collaboration.md`. For live runtime work also read `docs/studio-runtime-bridge.md`, `docs/studio-runtime-investigation.md`, and `docs/dynamic-control-api.md`.

## Architectural boundary

Pickleball Studio is bundled inside the normal Pickleball artifact but executes in a separate JVM.

- Never add Studio/Spring/Spring AI/Gradle Tooling dependencies to the public consumer dependency graph.
- Keep generic workspace, file, search, build, process, language, MCP-hosting, collaboration, and GUI infrastructure in Studio.
- Keep live scenario, browser, mapping, element, and service execution inside Pickleball Core. Studio reaches it only through the explicit opt-in runtime bridge.
- GUI, CLI, MCP, and future Java adapters must reuse the same Studio services. MCP and Swing must not reimplement runtime semantics or bridge HTTP.
- AI policy belongs in the AI client. Studio exposes deterministic operations and bounded evidence.
- Ordinary consumer runs and ordinary Studio **Run Tests** must remain bridge-free.

## Build and packaging

Use Java 21. Preserve the current nested Boot-JAR packaging, loopback-only MCP server, bundled Maven runtime, Gradle Wrapper execution, and Gradle Tooling API model boundary. Studio must remain usable with a compatible JDK and the normal Pickleball artifact without a host Maven/Gradle install. Do not unpack Studio implementation dependencies into the outer consumer runtime.

## Workspace, process, and language services

`WorkspaceFileService`, `WorkspaceProcessService`, `ManagedProcessService`, Maven/Gradle build services, `GradleProjectModelService`, and `WorkspaceLanguageService` remain the owners of their existing behavior. Keep workspace paths bounded, process/output history bounded, and managed child cleanup intact. Source navigation remains parse/navigation support; do not claim Java semantic classpath resolution, completion, refactoring, or Gherkin-to-Java binding until explicitly implemented.

`WorkspaceConcurrencyService` is a Phase 4 adapter over `WorkspaceFileService`; it must not become a second file backend. Existing-file checked writes use SHA-256 optimistic version tokens. The original workspace write API remains compatible.

## Desktop UI

`StudioDesktopSession` is the desktop-facing facade. Swing classes remain adapters only.

- The desktop UI starts the loopback MCP server in the same Spring application context and obtains `StudioDesktopSession` from that context. Desktop and MCP therefore share `ManagedProcessService`, Maven/Gradle services, `RuntimeBridgeService`, and `StudioCollaborationService`.
- The standalone `studio serve` command remains valid for headless MCP use.
- Controlled builds use the same managed process service as MCP.
- Runtime/scenario selection comes from `RuntimeBridgeService`.
- Pause/resume, detached steps, mappings, mapping snapshots, Phase 3H element inspection, service-call execution, and breakpoint management must delegate through `StudioDesktopSession` / `RuntimeBridgeService`.
- Runtime evidence cursor/gap behavior and existing bounded event/output views must remain unchanged.
- Phase 3H **Investigation** controls are not browser devtools: element inspection uses Pickleball categories/text/operations, service execution uses existing `CALL:` selectors, and breakpoints use semantic `ControlHook` filters.
- Hiding the modeless runtime-control window does not cancel a run. Explicit cancel remains the user action; pause/breakpoint leases are finite.
- Dirty editors remain visibly marked. Phase 4 editor presence publishes only path, dirty state, saved-version SHA-256, desktop session id, and timestamp; never publish unsaved editor contents.
- Desktop saves use the same SHA-256 version-token contract as collaboration-aware MCP writes. If disk content changed, show an explicit overwrite/reload/cancel choice rather than silently replacing the other client's work.

## Phase 4 collaboration

Phase 4 completes the planned Studio roadmap by making AI agents additional clients of the same Studio environment used by the human developer. It does not introduce an AI execution backend.

`StudioCollaborationService` owns bounded collaboration state. Keep these rules:

- Collaboration state is bounded and Studio-session-scoped, not consumer test state and not a source-controlled project artifact.
- Retain at most 1,000 activity events and 50 agent-session records unless a later explicit design changes the bound.
- Activity events describe observable Studio operations only. Do not record unsaved file contents, MCP bearer tokens, arbitrary consumer object graphs, credentials, or model private reasoning.
- `agent_note` records only rationale/status text explicitly supplied by the AI client for human visibility. Never imply that Studio can inspect hidden chain-of-thought.
- Agent sessions are attribution/session metadata, not authorization, connection-liveness detection, or evidence that a task succeeded. MCP endpoint authentication remains the loopback token contract.
- `studio_editor_states` is advisory collaboration metadata. `workspace_write_file_checked` must additionally enforce the dirty-editor block and expected SHA-256 check before replacing an existing file.
- The original `workspace_write_file` stays available for compatibility and intentional create/replace operations. Collaboration-aware edits to existing files should prefer versioned checked writes.
- `process_wait` and `runtime_wait_paused` are deterministic bounded conveniences over existing services. They must not implement retry strategy, troubleshooting policy, or alternate process/runtime semantics. `runtime_wait_paused` must require `scenarioId` when multiple scenarios are active rather than selecting one implicitly.
- `StudioObservedToolCallbackProvider` records selected existing mutating/high-impact MCP tool names for human visibility, but must never copy raw tool inputs into collaboration activity.
- The desktop **AI Collaboration** view may show agent sessions, editor states, and collaboration events. Existing process output and runtime event views remain the owners of their high-volume evidence streams.
- Desktop close removes that desktop session's editor-presence records. It must not delete retained collaboration activity or pretend an agent session ended.

## Live runtime bridge

Phases 3A-3G established the private loopback/authenticated bridge, explicit scenario targeting, pause/resume, detached execution, live mapping control, bounded event history, desktop timeline, mapping snapshots, and page/screenshot evidence. **Phase 3H completes the planned runtime investigation/control surface** with shared Pickleball-native element inspection, direct Pickleball service-call execution/evidence, and temporary semantic breakpoints. Protocol version remains `1`; the new capabilities are additive.

Keep these rules:

- Consumer bridge servers bind to `127.0.0.1` on OS-assigned ports and require the per-session bearer token. Never persist or display that consumer bridge token.
- Commands requiring live Pickleball state execute on the selected scenario thread through `ControlBridgeCoordinator`'s semantic-hook command queue. Never call `DynamicControl`, `ElementControl`, `ServiceCallControl`, or `MappingControl` from HTTP/MCP/Swing worker threads.
- Event reads remain observation-only and do not enter the scenario-thread queue.
- Parallel scenarios require explicit `scenarioId` when selection is ambiguous. Return `UNAVAILABLE` rather than guessing.
- Exploratory failures remain logical `FAILED` results and must not automatically fail the enclosing scenario.
- Evidence must stay bounded. Do not serialize arbitrary consumer object graphs.
- Browser evidence and element inspection use the WebDriver already owned by the selected scenario. Never create a browser for inspection.
- `runtime_element_inspect` must resolve through Pickleball's existing `ExecutionDictionary`; do not add raw Studio-only CSS/XPath input. Returned resolved XPath is evidence, not a second selector API.
- Element evidence is capped by requested match count (default 20, max 100), text/HTML/attribute bounds, and includes match count plus truncation metadata.
- `runtime_service_call` delegates to the existing `CALL:` / service-call scenario machinery through `ServiceCallControl`. Do not create a second HTTP DSL/client behavior. REQUEST, CONFIGURATION, and RESPONSE evidence is independently bounded to 256 KiB of UTF-8 JSON representation.
- Semantic breakpoints are runtime-scoped, in-memory only, and bounded to 100 retained rules. At least one scenario/hook/signature/step/phrase filter is required. Filters are literal, filters combine with AND, pauses have finite leases, and one-shot rules remove themselves after the first hit.
- Breakpoint pauses use the same scenario lane and pause loop as explicit pause, so all existing and Phase 3H commands remain callable while paused. Resume uses the existing runtime resume operation.
- Breakpoints do not persist after consumer runtime exit; they are cleared when the bridge closes.
- Existing mapping snapshot bounds/restore safety, 2048-event runtime ring, 1000-event desktop view, browser screenshot bounds, and retry-friendly detached behavior remain unchanged.
- Runtime bridge transport is internal. External AI clients use Studio MCP, not bridge ports directly.

## MCP

Phase 3G exposed 36 MCP tools and Phase 3H added six, bringing the pre-Phase-4 contract to 42 tools. Phase 4 adds ten deterministic collaboration/convenience tools, bringing the contract to **52 tools**:

- `agent_session_start`
- `agent_session_end`
- `agent_note`
- `studio_activity`
- `studio_agent_sessions`
- `studio_editor_states`
- `workspace_read_versioned`
- `workspace_write_file_checked`
- `process_wait`
- `runtime_wait_paused`

`StudioCollaborationMcpTools` adapts `StudioCollaborationService`, `WorkspaceConcurrencyService`, `ManagedProcessService`, and `RuntimeBridgeService`. It must not own duplicate runtime/build/file state or AI policy. Existing runtime, event, mapping, snapshot, browser, workspace, build, language, element, service-call, and breakpoint tools keep their current ownership and semantics.

The desktop-generated MCP URL may be displayed to the local human because it is the Studio MCP endpoint the user intentionally connects to. Do not log/persist it in the collaboration journal. This is distinct from the private consumer runtime bridge token, which remains internal.

## Validation tags

The Maven consumer `control-bridge.feature` carries `@phase3h` and `@phase4` in addition to its existing `@all`, `@smoke`, and `@control-bridge` tags.

`@phase3h` validates the completed runtime investigation/control slice. `@phase4` reuses that same scenario as focused compatibility coverage because Phase 4 changes Studio orchestration while intentionally reusing the existing runtime bridge rather than changing consumer runtime semantics.

Studio unit/integration tests must additionally cover:

- one shared collaboration service instance used by Desktop and MCP inside the Studio Spring context;
- explicit agent sessions/notes;
- desktop dirty-editor presence;
- checked-write dirty-editor blocking and stale SHA conflict behavior;
- the shared Spring context exposing `StudioDesktopSession` alongside MCP tools;
- the current 52-tool MCP contract.

## Completed product direction

Phase 4 is the final roadmap phase described by the Studio architecture handover. The intended end state is a human and AI operating the same deterministic Studio services and Studio-managed runtime sessions. Future refinements may improve ergonomics, but do not reinterpret completion as permission to embed autonomous decision policy, private-reasoning capture, an AI-only execution backend, or a second consumer-runtime path.
