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

`WorkspaceService` owns opening/detecting a workspace. `WorkspaceFileService` owns generic tree, UTF-8 read/write, and text-search behavior. `WorkspaceProcessService` owns bounded, non-interactive child-process execution and output capture.

Workspace paths must stay inside the opened workspace. Tree/search traversal must not follow symbolic links outside the workspace and should skip generated/heavy directories already defined by `WorkspaceFileService`. Process working directories must resolve inside the workspace.

Future CLI, GUI, MCP, build, and language adapters should reuse these services rather than building parallel file/process semantics.

## Maven tool runtime

Phase 2C bundles the Apache Maven 3.9.16 runtime as opaque resource JARs inside the Studio application. `MavenToolchainService` extracts those JARs to the private Studio tool cache and `MavenBuildService` launches `org.apache.maven.cli.MavenCli` in a separate JVM.

Keep Maven's dependency graph separate from the Spring Boot/Spring AI application classpath. Do not replace the isolated Maven runtime with a host `mvn` dependency or merge Maven jars into `BOOT-INF/lib`.

Maven execution is non-interactive and uses `--batch-mode --no-transfer-progress` before caller-supplied arguments. It must work without a host Maven installation.

## MCP

Phase 2B introduced Spring AI Streamable-HTTP through the WebMVC server starter. Phase 2C adds process and Maven tools over the same service layer.

- Bind the Studio MCP server to loopback only.
- Keep the per-launch endpoint token behavior unless a later authentication design explicitly replaces it.
- Expose deterministic Studio capabilities, not autonomous AI policy.
- Current MCP tools cover workspace status/tree/read/write/search plus one-shot process execution and bundled-Maven execution.
- MCP does not yet imply Pickleball runtime connectivity. Phase 3 must use an explicit Studio-JVM-to-consumer-test-JVM bridge.

## Current phase

Phase 2A established isolated packaging and workspace detection.

Phase 2B added generic workspace file services and MCP exposure of those services.

Phase 2C adds bounded one-shot process execution plus self-contained Maven 3.9.16 build/test execution through CLI and MCP.

Do not claim that Gradle Tooling API execution, asynchronous process lifecycle/history, interactive terminal support, GUI editing, syntax-aware Java/Gherkin navigation, or live Pickleball control is implemented until those later slices are added.

See `docs/pickleball-studio.md`.
