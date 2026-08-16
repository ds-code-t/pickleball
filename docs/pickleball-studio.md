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
- Gradle Nexus Publish Plugin 2.0.0;
- AspectJ 1.9.24 for Pickleball Core.

The outer Pickleball JAR keeps the nested Studio JAR opaque. Studio's Spring and Spring AI dependencies are packaged inside the executable nested Studio JAR under `BOOT-INF/lib` and are loaded only by the Studio child JVM.

## Distribution and process boundary

The published artifact has this conceptual layout:

```text
pickleball-<version>.jar
├── normal Pickleball runtime
├── tools.dscode.studio.launcher.PickleballMain
└── META-INF/pickleball/studio/pickleball-studio.jar
    ├── Spring Boot launcher
    ├── BOOT-INF/classes/   Studio implementation
    └── BOOT-INF/lib/       Studio-only Spring/Spring AI dependencies
```

The outer launcher extracts the nested Studio JAR to a versioned cache under `~/.pickleball/studio` and starts it with the same JDK in a child JVM.

This preserves one distributed Pickleball artifact while keeping the consumer/runtime and Studio application classpaths separate.

Root-owned packaging is configured by `gradle/pickleball-studio.gradle`. Studio dependencies are not added to the root runtime classpath or published Maven POM.

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

Phase 2B adds a generic workspace file service and a Streamable-HTTP MCP adapter over the same service layer.

The workspace file service supports:

- deterministic directory-tree listing;
- UTF-8 text-file reads;
- UTF-8 text-file create/replace writes;
- literal text search with case-sensitive or case-insensitive matching;
- workspace-bound path resolution;
- skipping generated/heavy directories such as `.git`, `.gradle`, `.idea`, `build`, `target`, `out`, and `node_modules` during tree/search traversal.

The MCP adapter exposes these tools:

```text
workspace_status
workspace_tree
workspace_read_file
workspace_write_file
workspace_search_text
```

MCP is an adapter, not the internal application architecture. `StudioMcpTools` delegates to the same `WorkspaceService` and `WorkspaceFileService` that future CLI/GUI adapters should use.

### Starting the MCP server

Build the normal Pickleball artifact and run:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio serve .
```

Studio starts a Spring AI 2.0 Streamable-HTTP MCP server bound only to `127.0.0.1`. Port `0` is the default, so the OS chooses an available local port.

The command prints the concrete endpoint, for example:

```text
Pickleball Studio MCP server ready
Workspace: /path/to/project
MCP endpoint: http://127.0.0.1:54321/mcp/<random-token>
```

A random URL-safe token is generated for each launch and embedded in the MCP endpoint path. This avoids exposing a predictable local file-control endpoint. The token is an endpoint discriminator, not a substitute for network authentication; the current server is intentionally loopback-only. A stable port/token may be supplied when a client configuration requires it:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio serve . --port=19070 --token=my-local-studio-token
```

The configured token must contain 8-128 URL-safe letters, digits, `_`, or `-`.

The MCP server advertises tool capability only for this phase; resources, prompts, and completion capabilities are disabled.

## Validation

Focused Studio validation:

```shell
./gradlew --rerun-tasks :pickleball-studio:test
./gradlew --rerun-tasks :pickleball-studio:verifyBundledStudio
```

`verifyBundledStudio` checks both sides of the isolation contract:

- the outer Pickleball JAR contains the opaque nested Studio JAR;
- Studio implementation classes do not leak into the outer consumer runtime classpath;
- the nested JAR is a Spring Boot executable JAR whose `Start-Class` is `StudioApplication`;
- the nested JAR contains the Spring AI WebMVC MCP server starter.

After focused validation, run the normal root and Maven-consumer validation from root `AGENTS.md`.

## Current boundaries

Phase 2B does **not** yet implement:

- embedded Maven build execution;
- Gradle Tooling API build execution;
- process/run history or terminal/output management;
- GUI editing/navigation;
- Java/Gherkin syntax services beyond text/file operations;
- Pickleball runtime IPC or live scenario/browser/mapping control.

Those capabilities should continue to layer on the generic Studio service model. Live Pickleball control remains a later explicit bridge between the Studio JVM and the consumer test JVM.
