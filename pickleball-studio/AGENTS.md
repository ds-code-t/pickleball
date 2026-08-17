# Pickleball Studio Agent Contract

This directory contains the standalone Pickleball Studio application. Root `AGENTS.md` still applies.

## Architectural boundary

Studio is a generic development application distributed inside the normal Pickleball artifact but executed in a separate JVM.

Keep these rules:

- Do not make Studio part of the consumer test JVM classpath or runtime lifecycle.
- Do not add Studio dependencies to the public Pickleball Maven dependency graph.
- Do not depend on Pickleball Core from generic Studio infrastructure unless a later Pickleball-specific adapter explicitly requires it.
- Put project files, search, builds, processes, output, activity, MCP hosting, and GUI infrastructure in Studio.
- Keep live scenario/browser/mapping execution inside Pickleball Core and connect it through the explicit runtime bridge.
- GUI, CLI, MCP, and future Java integrations should call the same Studio services rather than reimplementing behavior.
- MCP classes are adapters over Studio services. Do not put workspace/business behavior directly into MCP handlers.
- AI policy belongs in the AI client. Studio exposes deterministic capabilities and evidence.

## Build and packaging

- Use Java 21.
- The repository wrapper currently targets Gradle 9.7.0.
- The root artifact uses `com.gradleup.shadow` 9.6.1.
- The nested Studio application uses Spring Boot 4.1.0 and Spring AI 2.0.0.
- Package Studio with `bootJar` as `pickleball-studio.jar` so its application dependencies remain under `BOOT-INF/lib` inside the isolated nested application.
- Keep `pickleball-studio.jar` opaque at `META-INF/pickleball/studio/pickleball-studio.jar`; never unpack Studio implementation classes into the outer consumer runtime.
- Keep ordinary root Shadow duplicate handling first-entry-wins, while allowing `META-INF/services/**` duplicates through to `mergeServiceFiles()`.
- Do not mix AspectJ version changes into Studio packaging changes unless the task explicitly requires both.

## Workspace and process services

`WorkspaceService` owns opening/detecting a workspace. `WorkspaceFileService` owns generic tree, UTF-8 read/write, and text-search behavior. `WorkspaceProcessService` owns workspace-bound child-process creation and synchronous execution. `ManagedProcessService` owns asynchronous lifecycle, bounded history, incremental output cursors, timeout, and cancellation for long-running Studio processes.

Workspace paths must stay inside the opened workspace. Tree/search traversal must not follow symbolic links outside the workspace and should skip generated/heavy directories already defined by `WorkspaceFileService`. Process working directories must resolve inside the workspace.

Managed process history is session-scoped and bounded. Output buffers must remain bounded; clients use returned stdout/stderr cursors and must honor gap/truncation metadata instead of assuming all historical output is retained. Studio shutdown and explicit cancellation must terminate child processes and owned descendants so wrapper/build children are not left running.

CLI, GUI, MCP, build, and language adapters should reuse these services rather than building parallel file/process semantics.

## Maven tool runtime

Phase 2C bundles the Apache Maven 3.9.16 runtime as opaque resource JARs inside the Studio application. `MavenToolchainService` extracts those JARs to the private Studio tool cache and `MavenBuildService` launches `org.apache.maven.cli.MavenCli` in a separate JVM.

Keep Maven's dependency graph separate from the Spring Boot/Spring AI application classpath. Do not replace the isolated Maven runtime with a host `mvn` dependency or merge Maven jars into `BOOT-INF/lib`.

Maven execution is non-interactive and uses `--batch-mode --no-transfer-progress` before caller-supplied arguments. It must work without a host Maven installation.

## Gradle execution and project models

Phase 2E runs Gradle projects through their checked-in Wrapper (`gradlew` / `gradlew.bat`). `GradleBuildService` uses the same synchronous and managed process services as Maven execution.

- Do not require or invoke a host-installed `gradle`.
- Require the platform-appropriate Wrapper script for `gradle_run` / `gradle_start`.
- Let the Wrapper select and provision the project-declared Gradle distribution; do not pin build execution to Studio's Tooling API version.
- Set `JAVA_HOME` to the JDK running Studio and use `--no-daemon --console=plain` for Studio-managed wrapper invocations.

Phase 2F embeds the public Gradle Tooling API in the isolated Studio application for project-model/navigation reads.

- Keep Tooling API dependencies inside Studio's nested Boot JAR; never expose them through the Pickleball consumer dependency graph.
- Use the Tooling API's default project-specific distribution selection. It is Wrapper-aware and may provision the declared Gradle distribution without a host Gradle installation.
- `GradleProjectModelService` owns structured Gradle environment/project/source/task reads.
- Use `BasicIdeaProject` for source/resource roots so model navigation does not intentionally resolve/download external dependencies.
- Tooling API model reads may use a Gradle daemon; do not describe them as `--no-daemon` wrapper executions.
- Keep build execution (`GradleBuildService`) and project-model/navigation (`GradleProjectModelService`) as separate services.

## Language navigation

Phase 2G adds read-only Java/Gherkin definition navigation through `WorkspaceLanguageService`.

- `JavaSourceParser` uses the Java 21 compiler tree API in parse-only mode. Do not turn source navigation into an implicit project compile, annotation-processing run, or code execution path.
- `GherkinSourceParser` uses Cucumber Gherkin 35.1.0 and Messages 29.0.1 to stay aligned with the current Pickleball runtime grammar without depending on Pickleball Core.
- Keep `.java` and `.feature` source parsing workspace-bound and reuse `WorkspaceFileService` traversal/skip semantics for workspace-wide symbol scans.
- `source_outline`, `symbol_search`, and `symbol_definitions` are read-only adapters over the language service.
- Definition navigation is not semantic reference resolution. Do not claim find-usages, rename/refactoring, Java type/classpath analysis, completion, or Gherkin-step-to-Java binding until those are implemented explicitly.

## Desktop UI

Phase 2H established the Swing desktop workspace/editor adapter. Phase 3C adds a modeless runtime-control adapter over the Phase 3 bridge. Phase 3E renders Phase 3D runtime evidence in that same desktop surface. Phase 3F adds bounded mapping snapshot capture/list/restore through the same runtime service.

- `StudioDesktopSession` is the desktop-facing facade over existing workspace, language, build, managed-process, and runtime-bridge services.
- `StudioFrame`, `RuntimeControlDialog`, and other Swing classes must remain UI adapters; do not duplicate workspace boundary checks, source parsing, build command construction, process lifecycle, bridge HTTP/authentication, scenario-thread routing, or mapping semantics in widgets.
- Keep the desktop implementation JDK-only unless a later UI dependency is explicitly justified.
- Workspace tree behavior must reuse `WorkspaceFileService` traversal and skip rules.
- Java/Gherkin outline and symbol navigation must reuse `WorkspaceLanguageService`.
- Ordinary Gradle/Maven **Run Tests** actions must use managed build services and remain bridge-free.
- **Runtime > Runtime Control... > Start Control Run** is the explicit bridge-enabled desktop path and must delegate through `RuntimeBridgeService` via `StudioDesktopSession`.
- Controlled-build output/cancellation must use `ManagedProcessService`, including bounded output and descendant cleanup.
- Runtime/scenario selectors must use runtime descriptor/scenario discovery from `RuntimeBridgeService`; do not invent a Swing-only selection model.
- Pause/resume, detached-step execution, mapping get/put/resolve, and mapping snapshot capture/restore must target the selected scenario through the same runtime service methods used by MCP.
- Hiding the runtime-control window must not silently cancel the managed build. Explicit cancellation remains the user action; finite pause leases remain the safety mechanism for abandoned pauses.
- The desktop **Events** tab must read through `StudioDesktopSession.runtimeEvents(...)` / `RuntimeBridgeService.events(...)`; never add Swing-only bridge HTTP or event retention.
- Desktop evidence follows the selected runtime and shows all scenarios by default; **Selected scenario only** may filter with the currently selected active `scenarioId`. Runtime/session/filter changes must reset the desktop cursor instead of mixing streams.
- Preserve Phase 3D cursor/gap semantics exactly. Follow `nextSequence`, continue while `hasMore=true`, and surface `gap=true` rather than hiding missing retained history.
- Keep the Swing loaded-event view bounded to 1000 events. This is a presentation bound only and must not be described as changing the consumer runtime's 2048-event retention.
- **Clear View** is local-only and keeps the runtime cursor. **Reload Retained** resets the local cursor to zero and re-reads the currently retained consumer history.
- The desktop Mapping Snapshot controls must use `RuntimeBridgeService`'s Studio-owned snapshot store; do not retain a second Swing snapshot history.
- Snapshot selection is scoped to the selected runtime/scenario. Restore uses the selected stored snapshot id and therefore its captured target, not mutable text-field state.
- The current editor is plain text. Do not claim syntax highlighting, completion, semantic Java resolution, Gherkin step binding, refactoring, persistent desktop session state, or an interactive PTY terminal until implemented explicitly.
- Closing the UI may discard unsaved text only after explicit user confirmation.

## Live runtime bridge

Phase 3A established the explicit Studio-JVM-to-consumer-test-JVM control boundary through `RuntimeBridgeService` and `tools.dscode.control.bridge`; Phase 3B added explicit scenario targeting and direct live mapping control; Phase 3C exposes those services through the desktop UI; Phase 3D adds bounded semantic runtime evidence on the same private transport; Phase 3E renders that evidence in the desktop; Phase 3F adds bounded Studio-owned mapping snapshots and explicit restore without changing protocol version `1`.

- The bridge is opt-in per `runtime_start` or desktop **Start Control Run**; ordinary Maven/Gradle execution and the desktop **Run Tests** action must remain bridge-free.
- Consumer bridge servers bind only to `127.0.0.1` on operating-system-assigned ports and require a per-session bearer token.
- Never write the bearer token into runtime descriptor files, logs, MCP results, Swing text areas, or documentation examples.
- Runtime descriptors are discovery metadata only. The Studio-owned session retains the token.
- Commands that require live Pickleball state must execute on the actual scenario thread through the bridge's semantic-hook queue. Do not call `DynamicControl` directly from HTTP/MCP/Swing worker threads.
- The bridge registers through additive `ControlRuntime` observers. Do not replace or clear a consumer's primary global/thread-local control handler.
- Observer decisions are ignored; the existing primary handler remains authoritative for `ControlDecision` and value replacement.
- Pause requests must use finite leases and timed-out pause requests must be withdrawn.
- Use runtime scenario discovery/`scenarioId` for explicit parallel-scenario targeting. When a caller omits the id and selection is still ambiguous, return `UNAVAILABLE` rather than selecting an arbitrary scenario thread.
- `RuntimeBridgeService` owns Studio session/token/discovery/client behavior. MCP and Swing controls are adapters over that service.
- Existing `ManagedProcessService` remains the owner of the launched build process, output, cancellation, and descendant cleanup.
- Phase 3B exposes active-scenario discovery, explicit `scenarioId` targeting, status/pause/resume, generic retry-friendly detached step execution, and direct mapping get/put/resolve. Mapping commands must execute through the same scenario-thread queue and must not bypass `MappingControl` semantics.
- Mapping values crossing the bridge may be structured only when they are JSON-compatible; arbitrary consumer objects get a bounded textual fallback instead of generic serialization.
- `runtime_mapping_put` and the desktop Mapping Put action accept one JSON literal and deliberately mutate the selected live map; do not imply automatic rollback. Phase 3F snapshot restore is a separate explicit operation.
- Phase 3C desktop controls must preserve the bridge's logical `SUCCESS` / `FAILED` / `UNAVAILABLE` results rather than converting exploratory `FAILED` into a managed-process failure.
- Phase 3D semantic evidence is retained in a runtime-scoped bounded ring of 2048 immutable snapshots. `runtime_events` is cursor-based, defaults to 100 events, accepts at most 500, and must report a retention `gap` when a nonzero cursor falls behind the ring.
- Event snapshots may include sequence/timestamp/thread/scenario/hook/signature/step/phrase metadata only. Do not retain or serialize `ControlEvent.target`, `ControlEvent.arguments`, browser/service/mapping objects, or arbitrary consumer object graphs.
- Register the event recorder before the pausing coordinator so the hook that causes a pause is recorded before the scenario thread blocks.
- Event reads are observation-only and must not use the scenario-thread command queue. Reading evidence must remain possible while a scenario is paused.
- The event ring is runtime-scoped and may retain completed-scenario events until eviction or runtime shutdown; do not describe it as persistent after the consumer runtime exits.
- Because `ControlRuntime` suppresses recursive observer dispatch for observer-triggered work, the event ring does not promise complete nested hook enumeration inside detached bridge commands. The explicit control-call result remains authoritative for those commands.
- Phase 3F mapping capture/restore must run through the same scenario-thread command queue as other live mapping commands. The consumer bridge remains stateless about snapshot ids.
- `RuntimeBridgeService` owns at most 50 mapping snapshots per Studio runtime session, in memory only; clear them on service shutdown. MCP and Swing must share this store. Consumer capture values are limited to 512 KiB of compact UTF-8 JSON so retained restorable state fits the bridge request bound.
- Snapshot capture may materialize any `NodeMap` subclass for inspection, but restore is allowed only for exact ordinary `NodeMap` instances. Specialized subclasses are inspection-only.
- Restore is bound to the captured runtime/scenario/map and must verify the live map class, map type, and data-source metadata before overwriting values. Restore must mutate the same live map object rather than replace it.
- Snapshot JSON must not be described as recreating specialized live cursor/reference semantics, and restore is not automatic rollback.
- Do not claim dedicated browser/service/screenshot bridge commands, persistent mapping snapshots, restoration of specialized live map semantics, unbounded/persistent runtime evidence, or remote control until implemented.
- Runtime bridge transport is an internal Studio/Pickleball protocol. External AI clients should use Studio MCP rather than connecting to consumer bridge ports directly.

## MCP

Phase 2B introduced Spring AI Streamable-HTTP through the WebMVC server starter. Phase 2C added one-shot process and Maven tools. Phase 2D added managed process lifecycle tools. Phase 2E added synchronous and managed Gradle Wrapper execution. Phase 2F adds read-only Gradle Tooling API model/navigation tools. Phase 2G adds read-only Java/Gherkin source navigation tools. Phase 2H added a Swing desktop adapter over those same services. Phase 3A added six runtime bridge tools; Phase 3B added four targeted-scenario/mapping tools, bringing the MCP contract to 30 tools. Phase 3C adds no MCP tools. Phase 3D adds `runtime_events`, bringing the MCP contract to 31 tools. Phase 3E adds no MCP tools. Phase 3F adds three mapping snapshot tools, bringing the MCP contract to 34 tools.

- Bind the Studio MCP server to loopback only.
- Keep the per-launch endpoint token behavior unless a later authentication design explicitly replaces it.
- Expose deterministic Studio capabilities, not autonomous AI policy.
- Keep synchronous `process_run` / `maven_run` / `gradle_run` for one-call execution.
- Gradle model/navigation tools are `gradle_model` and `gradle_tasks`; they must remain read-only adapters over `GradleProjectModelService`.
- Source navigation tools are `source_outline`, `symbol_search`, and `symbol_definitions`; they must remain read-only adapters over `WorkspaceLanguageService`.
- Managed runs use `process_start`, `process_list`, `process_status`, `process_output`, `process_cancel`, `maven_start`, and `gradle_start`; `runtime_start` also returns an ordinary managed process id for the bridge-enabled build.
- Managed run ids and history belong to the running Studio server/JVM; do not imply persistence across Studio restarts.
- Runtime control MCP tools are `runtime_start`, `runtime_list`, `runtime_status`, `runtime_scenarios`, `runtime_pause`, `runtime_resume`, `runtime_execute_step`, `runtime_mapping_get`, `runtime_mapping_put`, and `runtime_mapping_resolve`; they must remain adapters over `RuntimeBridgeService`.
- Runtime evidence MCP is `runtime_events`; `RuntimeEvidenceMcpTools` must remain a thin read-only adapter over `RuntimeBridgeService.events(...)`, not a second retention implementation.
- Runtime mapping snapshot MCP tools are `runtime_mapping_snapshot`, `runtime_mapping_snapshots`, and `runtime_mapping_restore`; `RuntimeMappingMcpTools` must remain a thin adapter over `RuntimeBridgeService`, not a second snapshot store.

## Current phase

Phase 2A established isolated packaging and workspace detection.

Phase 2B added generic workspace file services and MCP exposure of those services.

Phase 2C added bounded one-shot process execution plus self-contained Maven 3.9.16 build/test execution through CLI and MCP.

Phase 2D added session-scoped managed process lifecycle, bounded run history, incremental stdout/stderr output cursors, cancellation, and managed Maven starts for MCP and GUI integrations.

Phase 2E adds project Gradle Wrapper execution through CLI and MCP, including managed Gradle starts, while requiring no host Gradle installation.

Phase 2F adds Gradle Tooling API environment/project/source/task models for CLI, MCP, and GUI navigation. Model reads follow the target build's project-specific distribution by default; when a build declares no Gradle version, Tooling API default behavior uses the client Tooling API version.

Phase 2G adds parse-only Java and Gherkin definition outlines, workspace symbol search, exact definition lookup, and syntax diagnostics for CLI, MCP, and GUI navigation.

Phase 2H adds the first Swing workspace/editor UI with file editing, saved-source outline/symbol navigation, managed Gradle/Maven test execution, output, and cancellation.

Phase 3A adds opt-in private loopback runtime sessions, descriptor discovery, live status, finite-lease pause/resume, and retry-friendly detached step execution through the existing Pickleball control API.

Phase 3B adds active-scenario listing, explicit scenario targeting for parallel execution, and direct live mapping get/put/resolve operations with safe JSON-compatible value transport.

Phase 3C adds the desktop Runtime Control window, bridge-enabled managed test launch, runtime/scenario selectors, targeted pause/resume, detached-step and mapping controls, and bounded controlled-build output through the same Studio services already used by MCP.

Phase 3D adds runtime-scoped bounded semantic hook evidence with monotonic cursors, explicit retention-gap reporting, scenario filtering, post-scenario retention within the live runtime, and the read-only `runtime_events` MCP tool.

Phase 3E adds the desktop Events tab over that same evidence service, including runtime/scenario filtering, cursor continuation, retention-gap visibility, bounded loaded-event display, reload-retained, clear-view, and optional auto-tail behavior.

Phase 3F adds materialized live mapping capture, a bounded 50-per-session Studio snapshot store, explicit same-object restore for ordinary `NodeMap` instances, three MCP tools, and matching desktop Mappings-tab controls.

Do not claim that syntax highlighting/completion, live unsaved-buffer parsing, full Gradle dependency/classpath import, persistent run/activity/runtime-control/runtime-event/mapping-snapshot or desktop session state, interactive terminal input, semantic Java reference/type analysis, Gherkin step binding, specialized live-map semantic restoration, dedicated browser/service/screenshot bridge commands, or remote runtime control are implemented until those later slices are added.

See `docs/pickleball-studio.md`.
