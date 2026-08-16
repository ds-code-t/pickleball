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
- Gradle Nexus Publish Plugin 2.0.0;
- AspectJ 1.9.24 for Pickleball Core.

The outer Pickleball JAR keeps the nested Studio JAR opaque. Studio's Spring and Spring AI dependencies are packaged inside the executable nested Studio JAR under `BOOT-INF/lib` and are loaded only by the Studio child JVM.

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
    └── BOOT-INF/lib/       Studio-only Spring/Spring AI dependencies
```

The outer launcher extracts the nested Studio JAR to a versioned cache under `~/.pickleball/studio` and starts it with the same JDK in a child JVM.

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

MCP is an adapter, not the internal application architecture. `StudioMcpTools` delegates to ordinary Studio services that CLI, future GUI, and future Java integrations can reuse.

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

## Validation

Focused Studio validation:

```shell
./gradlew --rerun-tasks :pickleball-studio:test
./gradlew --rerun-tasks :pickleball-studio:verifyBundledStudio
```

The Studio tests include a real child-JVM `java -version` process check and a Maven `--version` check using the bundled Maven runtime, so host Maven is not needed for the focused Maven bootstrap test.

`verifyBundledStudio` checks both sides of the isolation contract:

- the outer Pickleball JAR contains the opaque nested Studio JAR;
- Studio implementation classes do not leak into the outer consumer runtime classpath;
- the nested JAR is a Spring Boot executable JAR whose `Start-Class` is `StudioApplication`;
- the nested JAR contains the Spring AI WebMVC MCP server starter;
- the nested JAR contains the Studio-managed Maven runtime index and Maven embedder jar as opaque resources.

After focused validation, run the normal root and Maven-consumer validation from root `AGENTS.md`.

## Current boundaries

Phase 2C does **not** yet implement:

- Gradle Tooling API build execution;
- asynchronous process start/stop/status/history or activity persistence;
- interactive terminal support;
- GUI editing/navigation;
- Java/Gherkin syntax services beyond text/file operations;
- Pickleball runtime IPC or live scenario/browser/mapping control.

Those capabilities should continue to layer on the generic Studio service model. Live Pickleball control remains a later explicit bridge between the Studio JVM and the consumer test JVM.
