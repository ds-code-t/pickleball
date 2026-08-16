# Pickleball Studio

Pickleball Studio is being built as an isolated Java development application that is physically bundled inside the normal `tools.dscode:pickleball` artifact.

The consumer dependency remains unchanged. Studio does not run inside the consumer test JVM and its dependencies must not be added to the public Pickleball dependency graph.

## Build baseline

The Studio packaging foundation targets:

- Java 21;
- Gradle 9.7.0 through the repository Gradle Wrapper;
- Shadow 9.6.1 using the `com.gradleup.shadow` plugin ID;
- Gradle Nexus Publish Plugin 2.0.0, retained until publishing validation demonstrates a reason to replace it;
- AspectJ 1.9.24 for this build-modernization slice. AspectJ should be evaluated separately so bytecode-weaving changes are not mixed with the Gradle/Shadow migration.

Shadow 9 is important to the Studio distribution model because `ShadowJar.from(...)` now follows ordinary Gradle copy semantics. The nested Studio JAR is therefore copied into the outer Pickleball JAR without being unpacked into the consumer runtime classpath.

The existing root Shadow behavior remains first-entry-wins for ordinary duplicates. `META-INF/services/**` is explicitly allowed through to `mergeServiceFiles()` so service-provider descriptors continue to merge correctly under Shadow 9. The build also uses Gradle's `Configuration.incoming.artifacts` API for resolved artifact discovery instead of the legacy `ResolvedConfiguration` view.

## Phase 2A: standalone application boundary

The published artifact has this conceptual layout:

```text
pickleball-<version>.jar
├── normal Pickleball runtime
├── tools.dscode.studio.launcher.PickleballMain
└── META-INF/pickleball/studio/pickleball-studio.jar
```

The nested Studio JAR is treated as a separate application. The outer launcher extracts it to a versioned cache under `~/.pickleball/studio` and starts it with the same JDK in a child JVM.

This keeps the consumer/runtime and Studio runtime classpaths separate even though both are distributed in one artifact.

Root-owned packaging is configured by `gradle/pickleball-studio.gradle`. The `pickleball-studio` module builds only the isolated application JAR; it does not mutate the published POM or contribute Studio dependencies to the consumer dependency graph.

## Launching the current foundation

Build the normal Pickleball artifact, then run:

```shell
java -jar build/libs/pickleball-2.1.7.jar studio status .
```

The current Studio application opens the requested workspace and reports whether it contains Maven, Gradle, and Git project markers.

The cache location can be overridden for diagnostics or tests:

```shell
java -Dpickleball.studio.cache=/path/to/cache -jar build/libs/pickleball-2.1.7.jar studio status .
```

## Validation

After the Gradle/Shadow migration, validate in this order:

```shell
./gradlew --version
./gradlew --warning-mode all help
./gradlew --rerun-tasks :pickleball-studio:test
./gradlew --rerun-tasks :pickleball-studio:verifyBundledStudio
./gradlew test publishToMavenLocal
```

For consumer compatibility, then run the Maven consumer using the repository's normal validation command from root `AGENTS.md`.

`verifyBundledStudio` checks both sides of the isolation contract: the nested Studio JAR must exist, and Studio implementation classes must not be unpacked into the outer Pickleball classpath.

## Current boundaries

Phase 2A does not yet implement Spring AI MCP hosting, GUI editing, Maven/Gradle execution from Studio, process/run history, terminal/output/activity views, or Pickleball runtime IPC/live controls.

Those capabilities should be layered on the Studio application without moving generic Studio infrastructure into Pickleball Core.
