# Pickleball Studio

Pickleball Studio is an isolated Java development application physically bundled inside the normal `tools.dscode:pickleball` artifact.

The consumer dependency remains unchanged. Studio does not run inside the consumer test JVM, and Studio dependencies are not published into the Pickleball consumer dependency graph.

## Build baseline

Studio currently targets:

- Java 21;
- Gradle 9.7.0 through the repository Gradle Wrapper;
- Shadow 9.6.1 using the `com.gradleup.shadow` plugin ID for the outer Pickleball artifact;
- Spring Boot 4.1.0 for the executable nested Studio application;
- Spring AI 2.0.0 for MCP server integration;
- Apache Maven 3.9.16 for Studio-managed Maven execution;
- project Gradle Wrappers for Studio-managed Gradle execution without a host Gradle installation;
- Gradle Tooling API 9.6.1 for structured Gradle project/navigation models;
- Cucumber Gherkin 35.1.0 with Messages 29.0.1 for feature-file syntax navigation aligned with the current Pickleball runtime;
- JDK Swing for the desktop workspace/editor and runtime-control UI, with no additional GUI dependency;
- Gradle Nexus Publish Plugin 2.0.0;
- AspectJ 1.9.24 for Pickleball Core.

The outer Pickleball JAR keeps the nested Studio JAR opaque. Studio's Spring, Spring AI, Gradle Tooling API, and Gherkin navigation dependencies are packaged inside the executable nested Studio JAR under `BOOT-INF/lib` and are loaded only by the Studio child JVM.

The Maven runtime is packaged separately inside Studio as opaque tool resources rather than merged into the Spring application classpath. Studio extracts that runtime to its private tool cache when Maven is first used and launches Maven in another child JVM. No host Maven installation is required.

## Distribution and process boundary

The published artifact has this conceptual layout:

```text
pickleball-<version>.jar
├── normal Pickleball runtime
├── tools.dscode.studio.launcher.PickleballMain
└── META-INF/pickleball/studio/pickleball-studio.jar
    ├── Spring Boot launcher
    ├── BOOT-INF/classes/   Studio implementation and opaque Studio tool resources
    └── BOOT-INF/lib/       Studio-only application/navigation dependencies
```

The outer launcher extracts the nested Studio JAR to a versioned cache under `~/.pickleball/studio` and starts it with the same JDK in a child JVM. Cached Studio JAR names are content-addressed (`pickleball-studio-<sha256>.jar`) so a new build never needs to overwrite a JAR that another Studio process may still have open, including on Windows.

This preserves one distributed Pickleball artifact while keeping the consumer/runtime and Studio application classpaths separate.

Root-owned packaging is configured by `gradle/pickleball-studio.gradle`. Studio dependencies and Studio-managed build-tool runtimes are not added to the root runtime classpath or published Maven POM.

## Phase 2A: standalone application boundary

Phase 2A established:

- the `pickleball-studio` module;
- the nested application packaging boundary;
- the outer launcher and child JVM;
- generic workspace detection;
- Gradle 9 / Shadow 9 packaging and verification.

Workspace status remains available with:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio status .
```

## Phase 2B: workspace files and MCP

Phase 2B added a generic workspace file service and a Streamable-HTTP MCP adapter over the same service layer.

The workspace file service supports deterministic tree listing, UTF-8 reads/writes, literal text search, workspace-bound paths, and skipping generated/heavy directories during traversal.

The MCP adapter initially exposed:

```text
workspace_status
workspace_tree
workspace_read_file
workspace_write_file
workspace_search_text
```

MCP is an adapter, not the internal application architecture. `StudioMcpTools` delegates to ordinary Studio services that CLI, GUI, and future Java integrations can reuse.

### Starting the MCP server

Build the normal Pickleball artifact and run:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio serve .
```

Studio starts a Spring AI 2.0 Streamable-HTTP MCP server bound only to `127.0.0.1`. Port `0` is the default, so the OS chooses an available local port.

A random URL-safe token is generated for each launch and embedded in the MCP endpoint path. A stable port/token may be supplied when a client configuration requires it:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio serve . --port=19070 --token=my-local-studio-token
```

The configured token must contain 8-128 URL-safe letters, digits, `_`, or `-`.

## Phase 2C: process and Maven execution

Phase 2C adds `WorkspaceProcessService`, which runs one non-interactive child process with:

- a workspace-bound working directory;
- direct argv execution without implicit shell parsing;
- a configurable timeout;
- captured stdout and stderr;
- a 2 MiB capture limit per output stream while continuing to drain the child process;
- timeout and truncation metadata in `ProcessResult`.

The CLI form is:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio exec . java -version
```

This is one-shot process execution, not an interactive terminal and not yet an asynchronous process manager.

### Self-contained Maven

Studio bundles the runtime dependency graph of Apache Maven 3.9.16 as opaque tool resources. `MavenToolchainService` extracts the jars to:

```text
~/.pickleball/studio/tools/maven/3.9.16/
```

`MavenBuildService` then launches `org.apache.maven.cli.MavenCli` in a separate JVM using that isolated classpath. The Maven process inherits the user's normal Maven home/settings/local repository behavior and the current JDK, but does not require `mvn`, `mvn.cmd`, or a Maven installation on `PATH`.

Studio prepends `--batch-mode --no-transfer-progress` and then passes caller-supplied Maven arguments unchanged.

CLI example:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio maven ./maven-consumer-project test
```

MCP now additionally exposes:

```text
process_run
maven_run
```

`process_run` accepts an argv list, optional workspace-relative working directory, and optional timeout. `maven_run` accepts Maven goals/options and an optional timeout; its default timeout is 600 seconds.

## Phase 2D: managed process lifecycle and output

Phase 2D adds `ManagedProcessService` on top of the workspace process layer. It is intended for the long-lived Studio server and GUI integrations where builds or tools need to continue while the caller polls their state.

Managed runs provide:

- immediate start with a generated Studio process id;
- states `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, and `TIMED_OUT`;
- bounded in-memory history of the 100 most recent retained runs;
- independent incremental stdout and stderr cursors;
- bounded rolling output buffers with gap/truncation metadata when a caller falls behind;
- explicit cancellation;
- automatic termination of still-running owned children when the Studio application context shuts down.

History and output are **session-scoped**. Phase 2D does not persist process/activity history across Studio restarts.

The MCP server now exposes 13 tools. In addition to the previous tools, managed lifecycle adds:

```text
process_start
process_list
process_status
process_output
process_cancel
maven_start
```

`process_start` uses the same workspace-bound argv execution rules as `process_run`. `maven_start` uses the same bundled Maven 3.9.16 runtime and command construction as `maven_run`, but returns immediately with a process id.

A typical AI/client flow is:

1. call `maven_start` or `process_start`;
2. retain the returned process id;
3. poll `process_status`;
4. call `process_output` with the previous `nextStdoutOffset` / `nextStderrOffset`;
5. call `process_cancel` if the investigation no longer needs the run.

Managed lifecycle is intentionally exposed through the long-lived MCP server rather than as a standalone asynchronous CLI command. A one-shot CLI invocation would exit the owning Studio JVM and therefore cannot provide meaningful later status/output/cancellation. The existing CLI `exec` and `maven` commands remain synchronous.

## Phase 2E: Gradle Wrapper execution

Phase 2E adds `GradleBuildService` for Gradle workspaces that contain the normal checked-in Gradle Wrapper. Studio does not invoke a host-installed `gradle`; it runs `gradlew` on Unix-like systems or `gradlew.bat` on Windows, so the workspace remains authoritative for the Gradle version. If the declared distribution is not already in the user's Gradle Wrapper cache, normal Wrapper behavior may download it.

Studio-managed Gradle invocations:

- run from the opened workspace root;
- set `JAVA_HOME` to the JDK running Studio;
- prepend `--no-daemon --console=plain` for deterministic non-interactive capture;
- use the same 600-second default build timeout as managed Maven;
- share the same bounded process output, history, timeout, and cancellation infrastructure;
- require the platform-appropriate Wrapper script instead of falling back to a host Gradle installation.

CLI example:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio gradle . test
```

MCP adds two tools, bringing the current tool count to 15:

```text
gradle_run
gradle_start
```

`gradle_run` is synchronous. `gradle_start` returns a managed process id that is consumed through the existing `process_status`, `process_output`, and `process_cancel` tools. Process cancellation now terminates owned descendant processes as well as the immediate wrapper process so Gradle/Java children are not intentionally left running.

Phase 2E remains Gradle **build execution** through a checked-in Wrapper. Wrapperless `gradle_run` / `gradle_start` execution is still outside that build-runner contract; Phase 2F separately adds Tooling API model reads.

## Phase 2F: Gradle Tooling API project/navigation models

Phase 2F embeds Gradle Tooling API 9.6.1 inside the isolated Studio application and adds `GradleProjectModelService`. This is separate from `GradleBuildService`: Wrapper execution remains the build runner, while the Tooling API supplies structured, read-only project information for Studio, MCP, and GUI navigation.

The Tooling API is project-version aware. By default it uses the Gradle version configured by the target build, including its Wrapper configuration. If the target build declares no Gradle version, Gradle's Tooling API default is to use the Tooling API client's Gradle version. It does not require a host-installed `gradle`.

Model reads expose:

- the actual Gradle version selected for the target build;
- the Java home and Gradle user home reported by the build environment;
- deterministic project paths, names, descriptions, project directories, build directories, and build scripts;
- task counts for project navigation;
- production/test source directories;
- production/test resource directories;
- excluded directories and generated-source metadata;
- deterministic task path/name/group/description/public metadata for a selected project.

Source/resource navigation uses Gradle's `BasicIdeaProject` tooling model. Gradle documents that model as a fast preview model that does not resolve external dependencies from repositories, which keeps this phase focused on project/source navigation instead of dependency import.

MCP adds two tools, bringing the current tool count to 17:

```text
gradle_model
gradle_tasks
```

`gradle_model` returns the environment, project hierarchy, and source/resource roots. `gradle_tasks` accepts an optional Gradle project path such as `:` or `:app` and returns tasks for that project.

A human-readable CLI model check is also available:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio gradle-model .
```

Tooling API model reads may start/use a Gradle daemon. This is different from Phase 2E's wrapper execution, where Studio explicitly passes `--no-daemon --console=plain`.

Phase 2F does not import full external dependency/classpath graphs. That can be added later if editor/navigation features require it.

## Phase 2G: Java and Gherkin source navigation

Phase 2G adds `WorkspaceLanguageService` for read-only source navigation over the opened workspace. It builds on the existing workspace-bound file traversal and keeps generated/heavy directories out of workspace-wide symbol scans.

Java navigation uses the Java 21 compiler tree API in parse-only mode. It does not compile the source, run annotation processors, resolve a project classpath, or execute project code. Java outlines currently identify classes, interfaces, enums, records, annotation types, fields, constructors, and methods with source locations.

Gherkin navigation uses Cucumber Gherkin 35.1.0 with Messages 29.0.1, matching the parser/message versions used by the current Pickleball runtime. It uses the Gherkin AST and dialect metadata rather than a Studio-specific keyword parser. Gherkin outlines identify features, rules, backgrounds, scenarios, scenario outlines, examples, and steps with source locations. Parser errors are returned as source diagnostics.

The CLI can inspect one source file:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio outline . path/to/Source.java
java -jar build/libs/pickleball-2.1.7.jar studio outline . path/to/scenario.feature
```

MCP adds three tools, bringing the current tool count to 20:

```text
source_outline
symbol_search
symbol_definitions
```

`source_outline` parses one `.java` or `.feature` file and returns symbols plus syntax diagnostics. `symbol_search` performs case-insensitive workspace definition search with optional language/kind filters. `symbol_definitions` performs exact simple-name or qualified-name definition lookup. Workspace-wide scans are deterministic, result-limited, and use the same skipped-directory/symlink rules as the existing file service.

This is definition/navigation support, not a language server. Phase 2G does not yet resolve Java references across a compile classpath, infer types, find usages, rename symbols, provide code completion, or bind Gherkin steps to Java step definitions. Those deeper semantic capabilities can layer on this source model later if the editor requires them.

## Phase 2H: desktop workspace and editor foundation

Phase 2H adds the first graphical Pickleball Studio workspace. It is a JDK Swing adapter over the same services already used by CLI and MCP rather than a separate implementation of files, language parsing, or build execution.

Launch it with:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio ui .
```

The desktop foundation provides:

- a workspace tree backed by `WorkspaceFileService`, including the same generated/heavy-directory and symbolic-link traversal rules as CLI/MCP;
- tabbed UTF-8 text editing with dirty-state markers, save, reload, `Ctrl+S`, and `Ctrl+R`;
- Java/Gherkin source outlines and syntax diagnostics backed by `WorkspaceLanguageService`;
- workspace symbol search with navigation to the selected definition;
- double-click navigation from the workspace tree, outline, and symbol results;
- a managed **Run Tests** action that runs Gradle `test` through the project Wrapper for Gradle workspaces or Maven `test` through Studio's bundled Maven runtime for Maven workspaces;
- incremental stdout/stderr display through `ManagedProcessService`;
- cancellation of the active managed test run;
- bounded desktop output so long-running builds do not grow the editor process indefinitely;
- cleanup of managed child processes when the Studio window closes.

The UI is launched through `StudioDesktopApplication`, while `StudioDesktopSession` is the reusable desktop-facing facade over workspace, language, build, managed-process, and Phase 3 runtime services. Swing widgets must not become a second source of file/build/runtime semantics.

The editor remains plain text. Studio does not yet add syntax highlighting, completion, semantic Java type/reference resolution, Gherkin-to-Java step binding, refactoring, persistent layout/session state, or an interactive PTY terminal.

The source outline reflects the currently saved workspace file. Saving an editor refreshes its outline. Live parsing of unsaved editor buffers can be added later without changing the underlying source-navigation model.

If a workspace is both Gradle- and Maven-detected, desktop build actions prefer Gradle. Workspaces with neither build marker can still use file editing and source navigation, but build/runtime launch actions are disabled.

## Phase 3A: live consumer runtime bridge foundation

Phase 3A adds the first explicit bridge between the isolated Studio JVM and a consumer Pickleball test JVM. The bridge is opt-in per managed test launch and consumes the existing Phase 1 `DynamicControl` / `ControlRuntime` contract instead of moving Pickleball Core into Studio.

Studio creates the session through `RuntimeBridgeService`. The managed Maven/Gradle build receives a private session directory, session id, random bearer token, and optional first-scenario pause request through process environment variables. Ordinary Studio builds remain unchanged.

Participating consumer JVMs:

- start lazily only when the bridge environment is present;
- bind to `127.0.0.1` on a random port;
- publish non-secret runtime descriptors into the Studio session directory;
- require the per-session bearer token on every bridge request;
- execute commands on the actual scenario thread at semantic `ControlRuntime` hook boundaries.

The scenario-thread queue is required because Pickleball's active `CurrentScenarioState` is thread-local. HTTP worker threads do not call `DynamicControl` directly.

`ControlRuntime` supports additive observation-only handlers through `addObserver` / `removeObserver`. The existing global/thread-local handler remains authoritative for `ControlDecision` and value replacement. Bridge-triggered detached work can still reach that primary handler, while observer re-entry is suppressed.

Pauses are finite. The default lease is 120 seconds and the maximum is 3600 seconds. A pause request that times out before reaching a semantic hook is withdrawn rather than remaining armed for a later scenario.

The initial bridge capabilities are:

```text
status
pause
resume
execute_step
```

Studio MCP adds six runtime tools in Phase 3A:

```text
runtime_start
runtime_list
runtime_status
runtime_pause
runtime_resume
runtime_execute_step
```

`runtime_start` defaults to a managed `test` build and requests that the first observed scenario pause. It returns both the ordinary Studio managed-process id and a runtime session id. The existing `process_output`, `process_status`, and `process_cancel` tools continue to own build evidence/lifecycle.

`runtime_execute_step` returns the same retry-friendly logical outcomes as the in-process API (`SUCCESS`, `FAILED`, `UNAVAILABLE`). A failed detached attempt does not by itself fail the active scenario, so a controller can inspect the result and try another action.

See [Studio Runtime Bridge](studio-runtime-bridge.md) for the protocol, security, lifecycle, and controller flow.

## Phase 3B: targeted scenarios and live mapping control

Phase 3B keeps the Phase 3A transport and session model but removes the main ambiguity during parallel execution. `runtime_scenarios` lists every active scenario in one consumer runtime, including its scenario id, scenario thread id, current step/phrase, latest semantic hook, and pause state. Runtime pause/resume/detached-step calls accept an optional `scenarioId`; when several scenarios are active, a caller can target the exact scenario instead of relying on implicit selection. Wrong or stale ids return `UNAVAILABLE`.

Phase 3B also adds direct live mapping operations on the selected scenario thread:

```text
runtime_mapping_get
runtime_mapping_put
runtime_mapping_resolve
```

`runtime_mapping_get` reads from a live `NodeMap` reference. `runtime_mapping_put` accepts one JSON literal so mapping values cross the JVM boundary with explicit JSON-compatible type information. `runtime_mapping_resolve` resolves a value through the scenario's current live `ParsingMap` and therefore uses the same mapping precedence as normal Pickleball execution.

Mapping results include the runtime Java type, a bounded textual representation, and a structured JSON value only when the returned value is safely JSON-compatible. Custom consumer objects are not generically serialized. Live mapping writes are deliberate mutations and are not rolled back automatically.

These additions bring the Studio MCP tool count to **30**:

```text
runtime_scenarios
runtime_mapping_get
runtime_mapping_put
runtime_mapping_resolve
```

Dedicated browser/service commands remain later work. Phase 3F adds bounded materialized mapping snapshots and explicit restore for ordinary live `NodeMap` instances; browser and service behavior can continue to be explored through retry-friendly detached Pickleball steps where that already provides clear semantics.

## Phase 3C: desktop live runtime control

Phase 3C exposes the Phase 3A/3B runtime service through the existing Swing desktop application. It does not add a second bridge implementation or change the MCP contract.

Open the desktop application normally, then use:

```text
Runtime > Runtime Control...
```

The modeless Runtime Control window provides:

- **Start Control Run**, which calls `RuntimeBridgeService` through `StudioDesktopSession` and starts the normal managed `test` build with first-scenario pause enabled;
- **Cancel Run**, using the same `ManagedProcessService` process id returned by the runtime launch;
- polling/discovery of every participating consumer runtime descriptor for the Studio runtime session;
- runtime and scenario selectors for parallel test workers/scenarios;
- live scenario id/thread/step/phrase/hook/pause state;
- targeted **Pause** and **Resume**;
- retry-friendly detached-step execution with optional argument text;
- live mapping Get/Put/Resolve using the Phase 3B APIs and JSON-literal write contract;
- bounded incremental stdout/stderr for the controlled build;
- direct display of `SUCCESS`, `FAILED`, and `UNAVAILABLE` operation results without converting exploratory failures into process failures.

`RuntimeControlDialog` is a Swing adapter. It does not construct bridge HTTP requests, retain bearer tokens, call `DynamicControl`, or build Maven/Gradle commands itself. `StudioDesktopSession` owns the desktop-facing composition of `RuntimeBridgeService` with the existing process/build services.

The original main-window **Run Tests** action remains bridge-free. Hiding the Runtime Control window does not cancel its managed build. Pause safety still comes from finite bridge leases; explicit cancellation still terminates the managed process tree. Closing Studio closes the runtime bridge service and managed-process service.

Phase 3C adds no MCP tools, so the Studio MCP count remains **30**.

## Phase 3D: bounded semantic runtime evidence

Phase 3D adds bounded semantic hook history to each participating consumer runtime without changing the scenario-thread command model.

The bridge records immutable metadata snapshots for normal semantic hook traversal:

```text
sequence
timestamp
threadId
scenarioId
scenarioName
hook
signature
stepText
phraseText
```

The recorder deliberately does not retain hook targets, arguments, browser/service objects, or other consumer object graphs. It is registered before the pausing coordinator so the semantic boundary that causes a pause is visible in history before the scenario thread blocks.

Retention is runtime-scoped and bounded to **2,048 events**. Events remain available after an individual scenario completes until the runtime ring evicts them or the bridge closes. Reads are cursor-based rather than a long-lived streaming connection:

```text
runtime_events
```

`runtime_events` accepts an optional `scenarioId`, an exclusive `afterSequence` cursor, and an optional limit. The default page size is 100 and the maximum is 500. Results report `nextSequence`, `earliestAvailableSequence`, `latestSequence`, `hasMore`, and `gap`; a gap means the caller's nonzero cursor fell behind bounded retention and the history is incomplete.

The read is independent of the scenario-thread queue, so an AI can inspect evidence while a scenario is paused without consuming another hook or waiting for scenario execution to resume.

Because `ControlRuntime` intentionally suppresses recursive observer dispatch for work initiated from an observer, this evidence describes the semantic boundaries observed during normal scenario traversal. It does not claim to enumerate every nested hook fired inside a detached bridge command; the command's returned result remains authoritative for that exploratory action.

Phase 3D adds one MCP tool, bringing Studio to **31** tools.

## Phase 3E: desktop runtime evidence timeline

Phase 3E renders the Phase 3D evidence service in the existing Swing Runtime Control window without changing the consumer bridge protocol or MCP contract. The new **Events** tab polls `RuntimeBridgeService.events(...)` through `StudioDesktopSession`, using the same runtime/scenario selection already used by the control UI.

The desktop timeline:

- follows the selected consumer runtime and shows all scenarios by default so completed-scenario evidence remains visible while the runtime ring retains it;
- can optionally filter to the currently selected active scenario with **Selected scenario only**;
- requests up to 500 events per page and immediately continues while `hasMore=true`;
- advances only with the returned `nextSequence` cursor and reports the bridge's `gap` state instead of hiding lost history;
- keeps at most 1,000 loaded events in the Swing view, independently of the consumer runtime's 2,048-event retention ring;
- provides **Clear View**, which removes only the local displayed events while keeping the current runtime cursor;
- provides **Reload Retained**, which clears the view and restarts the read at cursor `0` so the currently retained runtime history is loaded again;
- supports optional auto-tail while preserving the user's scroll position when auto-tail is disabled.

Switching runtime sessions, runtimes, or the event scenario filter resets the desktop cursor so evidence from two different streams is never combined in one view. The desktop view does not add persistence: history still disappears when the consumer runtime exits, and the local 1,000-event display bound may omit older events even while they remain in the consumer's 2,048-event ring.

Phase 3E adds no MCP tools, so Studio remains at **31** tools. Phase 3F adds three mapping snapshot tools, bringing the current total to **34**.

## Phase 3F: bounded live mapping snapshots and explicit restore

Phase 3F builds on the Phase 3B mapping controls without introducing a second mapping model. A controller can capture one live `NodeMap` before an experiment, receive a Studio-owned `snapshotId`, mutate live state with the existing mapping tools or detached steps, and explicitly restore the captured state when appropriate.

The new MCP tools are:

```text
runtime_mapping_snapshot
runtime_mapping_snapshots
runtime_mapping_restore
```

Capture and restore still execute through the selected scenario's command queue. The bridge remains protocol version `1`; the new endpoints/capabilities are additive. The consumer JVM does not keep a snapshot registry. `RuntimeBridgeService` owns a bounded in-memory store of at most **50 snapshots per runtime session**, and those snapshots disappear when Studio closes. Capture is also bounded to **512 KiB of compact UTF-8 JSON values per map** so a restorable capture always stays comfortably below the bridge's 1 MiB request limit.

A captured state records the map reference, map type, concrete class, sorted data-source metadata, and materialized JSON object values. Any `NodeMap` subclass may be captured for inspection, but only an exact ordinary `NodeMap` is restorable. Specialized live subclasses are marked inspection-only because materialized JSON cannot reproduce their live cursor/reference semantics.

Restore is deliberately conservative. A snapshot id is bound to its originally captured runtime/scenario/map target. Restore requires that the current target is still an ordinary `NodeMap` with matching class, map type, and data sources, then clears and repopulates that **same object**. This preserves object identity already referenced by the running `ParsingMap`. Restore is an explicit destructive overwrite of the current materialized values; there is no automatic rollback after a failed detached action.

The desktop Runtime Control **Mappings** tab uses the same service/store. **Snapshot** captures the current map, the snapshot selector shows retained entries for the selected runtime/scenario, inspection-only captures are labeled, and **Restore Snapshot** restores by Studio snapshot id.

Phase 3F adds three MCP tools, bringing Studio to **34** tools.

## Phase 3G: read-only browser page and screenshot evidence

Phase 3G adds browser evidence without creating a second browser-control language. Commands still run on the selected scenario thread through the existing bridge queue and use only the WebDriver already owned by that scenario. If the scenario has not created a browser, the result is `UNAVAILABLE`; Studio never creates one just to inspect it.

The additive bridge capabilities are `browser_page` and `browser_screenshot`, with protocol version `1` unchanged. Studio MCP adds:

- `runtime_browser_page` — URL, title, window identity/size, and bounded current DOM source; DOM source is clipped at 256 Ki characters and reports `pageSourceTruncated`;
- `runtime_browser_screenshot` — captures a PNG capped at 5 MiB on the consumer side, transfers it only across the authenticated loopback bridge, then materializes it under the Studio bridge session `evidence` directory and returns the local file path rather than base64 through MCP. Studio retains at most 50 browser screenshots per runtime session, deleting the oldest screenshot files first.

These are observation-only operations. Navigation, clicks, typing, service calls, and other mutations continue through retry-friendly detached Pickleball steps. Phase 3G deliberately does not expose a Studio-specific CSS/XPath element selector: Pickleball element matching is currently composed through its execution dictionary/XPath builders rather than a small stable public resolver. A later element-inspection slice should first extract a shared resolver so Studio and normal Pickleball syntax cannot diverge.

Phase 3G adds two MCP tools, bringing Studio to **36** tools. The consumer control-bridge scenario is also tagged `@smoke` so the bridge contract is exercised by the supported smoke validation path.

## Validation

Focused Studio validation:

```shell
./gradlew --rerun-tasks :pickleball-studio:test
./gradlew --rerun-tasks :pickleball-studio:verifyBundledStudio
```

The Studio tests include real child-JVM process checks, managed incremental-output/cancellation and descendant-termination checks, synchronous plus managed Maven `--version` checks, cross-platform Gradle Wrapper fixture checks, a Gradle Tooling API project/source/task model fixture, Java/Gherkin source-outline and workspace-symbol navigation fixtures, non-headless-independent desktop-session/tree-model checks, runtime-bridge service/MCP registration checks, JSON-literal type-preservation checks for direct mapping writes, event-query/cursor client checks, mapping snapshot transport/store checks, desktop timeline cursor/gap/display-bound checks, and a desktop-facade controlled-run fixture that verifies bridge environment injection without requiring a live consumer JVM.

The Maven consumer additionally exercises the real loopback bridge against an active Pickleball scenario, including authenticated scenario targeting, bounded scenario-filtered event reads and cursor advancement while paused, live mapping baseline capture/mutation/restore plus write/read/resolve, retry-friendly detached failure, successful retry, and resume. The Tooling API test uses the Gradle installation already running the repository test build, so it does not require a host Gradle installation or a separate distribution download.

`verifyBundledStudio` checks both sides of the isolation contract:

- the outer Pickleball JAR contains the opaque nested Studio JAR;
- Studio implementation classes do not leak into the outer consumer runtime classpath;
- the nested JAR is a Spring Boot executable JAR whose `Start-Class` is `StudioApplication`;
- the nested JAR contains the Swing desktop UI implementation, including the runtime event view;
- the nested JAR contains the Spring AI WebMVC MCP server starter;
- the nested JAR contains Gradle Tooling API 9.6.1;
- the nested JAR contains Jackson databind for runtime-bridge JSON;
- the nested JAR contains Gherkin 35.1.0 and Messages 29.0.1 for Studio source navigation;
- the nested JAR contains the Studio-managed Maven runtime index and Maven embedder jar as opaque resources.

After focused validation, run the normal root and Maven-consumer validation from root `AGENTS.md`.

## Current boundaries

Phase 3G does **not** yet implement:

- syntax highlighting, code completion, editor refactoring, or live parsing of unsaved buffers;
- full Gradle external dependency/classpath import;
- persistent process/activity history, runtime-control history, runtime-event history, or desktop layout/session restoration across Studio/runtime restarts;
- interactive terminal stdin/PTY support;
- full Java semantic/classpath reference resolution, usages, or Gherkin-to-Java step binding;
- persistent mapping snapshots, exact restoration of specialized live `NodeMap` subclasses, shared Pickleball element-targeted browser inspection, or dedicated service-call bridge commands beyond generic detached step execution;
- remote/non-loopback runtime control.

Those capabilities should continue to layer on the shared Studio service model and the explicit Phase 3 runtime bridge rather than bypassing either boundary.
