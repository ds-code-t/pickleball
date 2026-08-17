# Pickleball Studio Agent Contract

Root `AGENTS.md` applies. For live runtime work also read `docs/studio-runtime-bridge.md`, `docs/studio-runtime-investigation.md`, and `docs/dynamic-control-api.md`.

## Architectural boundary

Pickleball Studio is bundled inside the normal Pickleball artifact but executes in a separate JVM.

- Never add Studio/Spring/Spring AI/Gradle Tooling dependencies to the public consumer dependency graph.
- Keep generic workspace, file, search, build, process, language, MCP-hosting, and GUI infrastructure in Studio.
- Keep live scenario, browser, mapping, element, and service execution inside Pickleball Core. Studio reaches it only through the explicit opt-in runtime bridge.
- GUI, CLI, MCP, and future Java adapters must reuse the same Studio services. MCP and Swing must not reimplement runtime semantics or bridge HTTP.
- AI policy belongs in the AI client. Studio exposes deterministic operations and bounded evidence.
- Ordinary consumer runs and ordinary Studio **Run Tests** must remain bridge-free.

## Build and packaging

Use Java 21. Preserve the current nested Boot-JAR packaging, loopback-only MCP server, bundled Maven runtime, Gradle Wrapper execution, and Gradle Tooling API model boundary. Studio must remain usable with a compatible JDK and the normal Pickleball artifact without a host Maven/Gradle install. Do not unpack Studio implementation dependencies into the outer consumer runtime.

## Workspace, process, and language services

`WorkspaceFileService`, `WorkspaceProcessService`, `ManagedProcessService`, Maven/Gradle build services, `GradleProjectModelService`, and `WorkspaceLanguageService` remain the owners of their existing behavior. Keep workspace paths bounded, process/output history bounded, and managed child cleanup intact. Source navigation remains parse/navigation support; do not claim Java semantic classpath resolution, completion, refactoring, or Gherkin-to-Java binding until explicitly implemented.

## Desktop UI

`StudioDesktopSession` is the desktop-facing facade. Swing classes remain adapters only.

- Controlled builds use the same managed process service as MCP.
- Runtime/scenario selection comes from `RuntimeBridgeService`.
- Pause/resume, detached steps, mappings, mapping snapshots, Phase 3H element inspection, service-call execution, and breakpoint management must delegate through `StudioDesktopSession` / `RuntimeBridgeService`.
- Runtime evidence cursor/gap behavior and existing bounded event/output views must remain unchanged.
- Phase 3H **Investigation** controls are not browser devtools: element inspection uses Pickleball categories/text/operations, service execution uses existing `CALL:` selectors, and breakpoints use semantic `ControlHook` filters.
- Hiding the modeless runtime-control window does not cancel a run. Explicit cancel remains the user action; pause/breakpoint leases are finite.

## Live runtime bridge

Phases 3A-3G established the private loopback/authenticated bridge, explicit scenario targeting, pause/resume, detached execution, live mapping control, bounded event history, desktop timeline, mapping snapshots, and page/screenshot evidence. **Phase 3H completes the planned runtime investigation/control surface** with shared Pickleball-native element inspection, direct Pickleball service-call execution/evidence, and temporary semantic breakpoints. Protocol version remains `1`; the new capabilities are additive.

Keep these rules:

- Consumer bridge servers bind to `127.0.0.1` on OS-assigned ports and require the per-session bearer token. Never persist or display that token.
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

Phase 3G exposed 36 MCP tools. Phase 3H adds six deterministic tools, bringing the contract to **42 tools**:

- `runtime_element_inspect`
- `runtime_service_call`
- `runtime_breakpoint_add`
- `runtime_breakpoints`
- `runtime_breakpoint_remove`
- `runtime_breakpoints_clear`

`RuntimeInvestigationMcpTools` is a thin adapter over `RuntimeBridgeService`; it must not own runtime state or AI policy. Existing runtime, event, mapping, snapshot, browser, workspace, build, and language tools keep their current ownership and semantics.

## Phase 3H validation

The Maven consumer `control-bridge.feature` carries `@phase3h` in addition to its existing `@all`, `@smoke`, and `@control-bridge` tags. Its bridge test must cover at minimum:

- existing authenticated loopback/scenario targeting, pause/resume, events, mappings/snapshots, browser evidence, and retry-friendly detached execution;
- Pickleball-native element inspection through the bridge;
- direct service-call execution with structured request/response evidence;
- semantic breakpoint creation/listing, an actual breakpoint hit/pause, one-shot removal, and resume.

Use `@phase3h` for focused consumer validation of this final Phase 3 runtime-control slice.

## Remaining product direction

Phase 3H completes the planned live investigation/control capability set. The next major phase is Phase 4 human/AI collaboration and workflow optimization. Do not treat that as permission to embed autonomous AI decision policy in Studio; humans and AI should continue to operate the same deterministic Studio services and Studio-managed runtime sessions.
