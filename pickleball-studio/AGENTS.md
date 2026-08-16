# Pickleball Studio Agent Contract

This directory contains the standalone Pickleball Studio application. Root `AGENTS.md` still applies.

## Architectural boundary

Studio is a generic development application distributed inside the normal Pickleball artifact but executed in a separate JVM.

Keep these rules:

- Do not make Studio part of the consumer test JVM classpath or runtime lifecycle.
- Do not add Studio dependencies to the public Pickleball Maven dependency graph.
- Do not depend on Pickleball Core from generic Studio infrastructure unless a later Pickleball-specific adapter explicitly requires it.
- Put project files, search, builds, processes, output, activity, MCP hosting, and GUI infrastructure in Studio.
- Keep live scenario/browser/mapping execution inside Pickleball Core and connect it later through an explicit bridge.
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

Future CLI, GUI, MCP, build, and language adapters should reuse these services rather than building parallel file/process semantics.

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

## MCP

Phase 2B introduced Spring AI Streamable-HTTP through the WebMVC server starter. Phase 2C added one-shot process and Maven tools. Phase 2D added managed process lifecycle tools. Phase 2E added synchronous and managed Gradle Wrapper execution. Phase 2F adds read-only Gradle Tooling API model/navigation tools. Phase 2G adds read-only Java/Gherkin source navigation tools.

- Bind the Studio MCP server to loopback only.
- Keep the per-launch endpoint token behavior unless a later authentication design explicitly replaces it.
- Expose deterministic Studio capabilities, not autonomous AI policy.
- Keep synchronous `process_run` / `maven_run` / `gradle_run` for one-call execution.
- Gradle model/navigation tools are `gradle_model` and `gradle_tasks`; they must remain read-only adapters over `GradleProjectModelService`.
- Source navigation tools are `source_outline`, `symbol_search`, and `symbol_definitions`; they must remain read-only adapters over `WorkspaceLanguageService`.
- Managed runs use `process_start`, `process_list`, `process_status`, `process_output`, `process_cancel`, `maven_start`, and `gradle_start`.
- Managed run ids and history belong to the running Studio server/JVM; do not imply persistence across Studio restarts.
- MCP does not yet imply Pickleball runtime connectivity. Phase 3 must use an explicit Studio-JVM-to-consumer-test-JVM bridge.

## Current phase

Phase 2A established isolated packaging and workspace detection.

Phase 2B added generic workspace file services and MCP exposure of those services.

Phase 2C added bounded one-shot process execution plus self-contained Maven 3.9.16 build/test execution through CLI and MCP.

Phase 2D added session-scoped managed process lifecycle, bounded run history, incremental stdout/stderr output cursors, cancellation, and managed Maven starts for MCP and future Studio UI integrations.

Phase 2E adds project Gradle Wrapper execution through CLI and MCP, including managed Gradle starts, while requiring no host Gradle installation.

Phase 2F adds Gradle Tooling API environment/project/source/task models for CLI, MCP, and future GUI navigation. Model reads follow the target build's project-specific distribution by default; when a build declares no Gradle version, Tooling API default behavior uses the client Tooling API version.

Phase 2G adds parse-only Java and Gherkin definition outlines, workspace symbol search, exact definition lookup, and syntax diagnostics for CLI, MCP, and future GUI navigation.

Do not claim that full Gradle dependency/classpath import, persistent run/activity history, interactive terminal input, GUI editing, semantic Java reference/type analysis, Gherkin step binding, or live Pickleball control is implemented until those later slices are added.

See `docs/pickleball-studio.md`.
