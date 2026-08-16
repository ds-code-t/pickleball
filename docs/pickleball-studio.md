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
- JDK Swing for the desktop workspace/editor UI, with no additional GUI dependency;
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

## Phase 2D: managed process lifecycle and output

Phase 2D adds `ManagedProcessService` on top of the workspace process layer. It is intended for the long-lived Studio server and future GUI integrations where builds or tools need to continue while the caller polls their state.

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

Phase 2F embeds Gradle Tooling API 9.6.1 inside the isolated Studio application and adds `GradleProjectModelService`. This is separate from `GradleBuildService`: Wrapper execution remains the build runner, while the Tooling API supplies structured, read-only project information for Studio, MCP, and future GUI navigation.

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

The desktop foundation currently provides:

- a workspace tree backed by `WorkspaceFileService`, including the same generated/heavy-directory and symbolic-link traversal rules as CLI/MCP;
- tabbed UTF-8 text editing with dirty-state markers, save, reload, `Ctrl+S`, and `Ctrl+R`;
- Java/Gherkin source outlines and syntax diagnostics backed by `WorkspaceLanguageService`;
- workspace symbol search with navigation to the selected definition;
- double-click navigation from the workspace tree, outline, and symbol results;
- a managed test action that runs Gradle `test` through the project Wrapper for Gradle workspaces or Maven `test` through Studio's bundled Maven runtime for Maven workspaces;
- incremental stdout/stderr display through `ManagedProcessService`;
- cancellation of the active managed test run;
- bounded desktop output so long-running builds do not grow the editor process indefinitely;
- cleanup of managed child processes when the Studio window closes.

The UI is launched through `StudioDesktopApplication`, while `StudioDesktopSession` is the reusable desktop-facing facade over workspace, language, build, and managed-process services. The Swing frame itself must not become a second source of file/build semantics.

The first editor is intentionally plain text. Phase 2H does not add syntax highlighting, completion, semantic Java type/reference resolution, Gherkin-to-Java step binding, refactoring, persistent layout/session state, or an interactive PTY terminal.

The source outline reflects the currently saved workspace file. Saving an editor refreshes its outline. Live parsing of unsaved editor buffers can be added later without changing the underlying source-navigation model.

If a workspace is both Gradle- and Maven-detected, the desktop test action currently prefers Gradle build execution. Workspaces with neither build marker can still use file editing and source navigation, but the test action is disabled.

## Validation

Focused Studio validation:

```shell
./gradlew --rerun-tasks :pickleball-studio:test
./gradlew --rerun-tasks :pickleball-studio:verifyBundledStudio
```

The Studio tests include real child-JVM process checks, managed incremental-output/cancellation and descendant-termination checks, synchronous plus managed Maven `--version` checks, cross-platform Gradle Wrapper fixture checks, a Gradle Tooling API project/source/task model fixture, Java/Gherkin source-outline and workspace-symbol navigation fixtures, and non-headless-independent desktop-session/tree-model checks. The Tooling API test uses the Gradle installation already running the repository test build, so it does not require a host Gradle installation or a separate distribution download.

`verifyBundledStudio` checks both sides of the isolation contract:

- the outer Pickleball JAR contains the opaque nested Studio JAR;
- Studio implementation classes do not leak into the outer consumer runtime classpath;
- the nested JAR is a Spring Boot executable JAR whose `Start-Class` is `StudioApplication`;
- the nested JAR contains the Swing desktop UI implementation;
- the nested JAR contains the Spring AI WebMVC MCP server starter;
- the nested JAR contains Gradle Tooling API 9.6.1;
- the nested JAR contains Gherkin 35.1.0 and Messages 29.0.1 for Studio source navigation;
- the nested JAR contains the Studio-managed Maven runtime index and Maven embedder jar as opaque resources.

After focused validation, run the normal root and Maven-consumer validation from root `AGENTS.md`.

## Current boundaries

Phase 2H does **not** yet implement:

- syntax highlighting, code completion, editor refactoring, or live parsing of unsaved buffers;
- full Gradle external dependency/classpath import;
- persistent process/activity history or desktop layout/session restoration across Studio restarts;
- interactive terminal stdin/PTY support;
- full Java semantic/classpath reference resolution, usages, or Gherkin-to-Java step binding;
- Pickleball runtime IPC or live scenario/browser/mapping control.

Those capabilities should continue to layer on the generic Studio service model. Live Pickleball control remains a later explicit bridge between the Studio JVM and the consumer test JVM.
