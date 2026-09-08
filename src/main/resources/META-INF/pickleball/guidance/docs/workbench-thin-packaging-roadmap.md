# Workbench thin-packaging roadmap (2.1.11)

Packaging-only change on branch `2.1.11`. VERSION stays `2.1.11`.
Do not bump VERSION. Do not create a new repository or branch.

This file is the gated build contract for stopping fat-jarring of Workbench
libraries into the nested controller JAR. Implement gates **A → F** in order.
Do not start gate N+1 until gate N acceptance passes.

## Goal

Nested controller payload stays at the same resource path but becomes a thin
JAR: Workbench classes plus shaded/embedded `pickleball-control-protocol`.
OpenJFX, MCP SDK, and Jackson are resolved at controller launch into a local
cache and placed on the forked controller classpath with `java -cp`.

Consumer story is unchanged: one dependency `tools.dscode:pickleball`.
Isolation is unchanged: **Pickleball may contain Workbench; Workbench must
not contain Pickleball.**

## Hard rules / non-goals

- Do not un-embed Workbench from Pickleball.
- Do not change nest path, extract path, SHA-256 content-addressing, or the
  ownership mnemonic.
- Nest path stays `META-INF/pickleball/workbench/pickleball-workbench.jar`
  (`ControlProtocol.EMBEDDED_WORKBENCH_RESOURCE`).
- Extract path stays `.pickleball/workbench/controller/<sha256>/pickleball-workbench.jar`.
- Do not flatten Workbench or MCP classes into the outer Pickleball JAR.
- Do not add JavaFX, MCP, Jackson-from-Workbench, or `pickleball-workbench`
  as published POM/Gradle transitives of `tools.dscode:pickleball`.
- Do not put those libraries on the consumer test/compile/runtime classpath.
- Do not change AspectJ weaving, woven Cucumber set, xpathy bundling,
  protocol isolation, worker launch, Control Bridge, MCP tool names, UI
  behavior, agent verbs, or consumer-facing launcher class/args.
- Do not add a second coordinate consumers must declare.
- Do not restore a Workbench compile/runtime dependency on root Pickleball,
  `tools.dscode:pickleball`, or `pickleball-control-api`.
- Do not embed Aether, Maven Resolver, or extra Jackson usage into the
  published Pickleball artifact to perform resolve.
- Worker launch / `WorkbenchWorkerManager` stay as-is. Do not merge the
  lib cache into captured test-runtime or worker `-cp`.
- VERSION stays `2.1.11` on this branch tip.

## Current design (keep)

- Consumer depends only on `tools.dscode:pickleball`.
- Root `shadowJar` embeds Workbench as opaque bytes at
  `META-INF/pickleball/workbench/pickleball-workbench.jar`.
- `PickleballWorkbenchLauncher` extracts by SHA-256 and starts a separate JVM.
- Agent-core and session-client commands stay in the consumer JVM; only
  forwarded controller commands extract and fork the controller.
- Workbench main class: `tools.dscode.workbench.WorkbenchApplication`.
- `WorkbenchRuntimeBoundary` still forbids Pickleball execution classes and
  `pickleball-*.jar` except `pickleball-workbench-` / `pickleball-control-protocol-`.
- Worker must not see the Workbench controller artifact.
- Workbench compile/runtime project dependency stays exactly
  `:pickleball-control-protocol`.
- OpenJFX 21.0.6 modules, MCP 2.0.0 (`mcp-core`, `mcp-json-jackson2`), and
  Jackson 2.20.0 remain Workbench compile/runtime dependencies.

## Desired design (only change)

### Thin nested JAR

Keep the `shadowJar` task name and output path
`pickleball-workbench/build/libs/pickleball-workbench-2.1.11.jar` so root
`dependsOn ':pickleball-workbench:shadowJar'` and
`from(standaloneWorkbenchJar)` stay. Keep `tasks.jar` disabled.

Shadow **only** Workbench classes plus `pickleball-control-protocol`.
Main-Class stays `tools.dscode.workbench.WorkbenchApplication`.

Remove:

- `stageJavaFxNatives`
- `configurations.javafxNatives`
- all-platform native staging
- `processResources` copy of `javafx-natives/`

The thin JAR must not contain `javafx/`, `javafx-natives/`,
`io/modelcontextprotocol/`, or `com/fasterxml/jackson/` packages.

### Published Workbench POM

First-level pinned controller libraries only (no protocol, no Pickleball):

```text
org.openjfx:javafx-base:21.0.6
org.openjfx:javafx-graphics:21.0.6
org.openjfx:javafx-controls:21.0.6
org.openjfx:javafx-swing:21.0.6
org.openjfx:javafx-media:21.0.6
org.openjfx:javafx-web:21.0.6
com.fasterxml.jackson.core:jackson-databind:2.20.0
io.modelcontextprotocol.sdk:mcp-core:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0
```

Write those nine dependencies explicitly in `pom.withXml`. Do not reflect
classified `runtimeClasspath`. Protocol stays embedded, not a published
Workbench Maven dependency. Rewrite
`verifyWorkbenchPublishedDependencyContract` to assert that set. Do not
delete the task.

Root Pickleball POM is unchanged: no JavaFX, no MCP, no
`pickleball-workbench`. Existing Pickleball Jackson API dependency stays
Pickleball's own and is not a Workbench leak.

### Runtime-lib manifest (lockfile)

Generated at Workbench build and shipped inside the thin JAR:

```text
META-INF/pickleball/workbench-runtime-libs.txt
```

Line-oriented UTF-8. `#` comments allowed. Header comments include
`format=1` and `workbench-version=2.1.11`. Body is the flattened resolved
Workbench `runtimeClasspath` minus the protocol project artifact minus
Workbench output:

- One row per file.
- `org.openjfx:*` rows use the literal classifier token `CLASSIFIER`
  (one row per JavaFX module, never four platforms).
- Include MCP/Jackson transitives the controller will actually load
  (`slf4j-api`, `reactor-core`, `reactive-streams`, `jackson-core`,
  `jackson-annotations`, and any other resolved non-protocol files).
- Do not hand-write the lockfile. Generate it from a dedicated Gradle
  task whose output is consumed by `processResources` / `shadowJar`.

Launcher is a file fetcher, not a Maven transitive resolver.

### Launch / cache / resolve

`PickleballWorkbenchLauncher.command(...)` is the **single fork seam**.
`WorkbenchSessionCommands.startControllerSession` already calls
`extractEmbeddedPayload` + `command()`. Changing `command()` covers `ui`,
`mcp`, `sync`, and isolate/session-start. Do not leave a second `-jar`
builder.

After extracting the thin JAR as today:

1. Read the manifest from the thin JAR.
2. Substitute `CLASSIFIER` with the launch-time platform key
   `win | linux | mac | mac-aarch64`.
3. Resolve each GAV into
   `.pickleball/workbench/lib/<workbench-version>/`.
4. Fork:

```text
java -cp <thinJar><pathsep><lib><pathsep><lib>... tools.dscode.workbench.WorkbenchApplication <args>
```

Platform key mapping is an 8-line JDK-only duplicate of
`JavaFxSupport.platformKey()`. Comment that the two copies must stay
identical. The launcher **must not** load `JavaFxSupport` (it imports
`javafx.*` and lives only in the nested JAR).

Resolve order uses consumer Maven/Gradle **user settings**, never
consumer `testRuntimeClasspath`:

1. Maven local repository from `settings.xml` `<localRepository>` if
   present, else `~/.m2/repository`.
2. Gradle module cache `~/.gradle/caches/modules-2/files-2.1/...`.
3. Download from the first usable `settings.xml` mirror, else Maven
   Central, into the lib cache.

Download stays JDK-only HTTP + filesystem. No Aether. No URLClassLoader
of JavaFX/MCP/Jackson in the consumer JVM. File ops only.

Write a completeness marker under the versioned lib cache so reruns skip
probes when every pinned file is present.

Fail clearly with GAV + classifier + searched locations. Do not start a
half-classpath controller.

Lib cache is controller-only. Never merge it onto a worker classpath.
Do not expand `WorkbenchWorkerManager` reject rules in this change
(hard rule: do not change worker launch). Existing worker rejection of
the Workbench controller artifact remains sufficient.

`MAX_PAYLOAD_BYTES` drops from 512 MiB to 32 MiB as a fat-jar regression
guard. Update the launcher-test comment; drop the `>= 164 MiB`
assertion that encoded the old natives-in-payload budget.

`--module-path` is **out of scope** unless Gate F `--version` / MCP probe
hits "JavaFX runtime components are missing". Current fat-jar already
runs JFXPanel from the classpath and excludes `module-info.class`.

### JavaFxSupport

Delete `extractNatives`, `extractFromJar`, and every `javafx-natives/`
code path. Keep `ensureInitialized`, Text fallback, default
`prism.order=sw`, and `platformKey()`. Ordinary OpenJFX platform JARs on
the child classpath are sufficient.

## Docs that must change

Rewrite every claim that Workbench is self-contained, shades all-platform
natives, publishes an empty POM dependency list, or starts with
`java -jar` only:

- `docs/pickleball-workbench.md`
- `docs/pickleball-workbench-player.md`
- `docs/agent/feature-map.md` architecture contract
- `pickleball-workbench/AGENTS.md`
- `REVIEW.md` isolation bullets only if they still imply a fat
  self-contained payload
- `WorkbenchApplication` usage/help strings that print `java -jar ...`
- packaged guidance mirrors under
  `src/main/resources/META-INF/pickleball/guidance/`
- maintainer direct-run examples become
  `java -cp <thin+resolved libs> tools.dscode.workbench.WorkbenchApplication ...`

Consumer story stays: one Pickleball dependency, same launcher class and
args. `docs/getting-started.md` / `docs/consumer-project.md` only change
wording that implies a fat `java -jar` nested executable. Do not bump
any example version pin.

After doc edits run:

```text
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

Do not hand-edit `docs/agent/repository-index.md`.

## Tests to update, not weaken

- `PickleballWorkbenchLauncherTest` — `command()` asserts `-cp`, thin JAR,
  cached libs, main class `WorkbenchApplication`, then args. Keep
  content-addressed extract + repair. Drop `MAX_PAYLOAD_BYTES >= 164 MiB`.
  Add manifest parse + cache resolve + command-shape coverage.
- `verifyWorkbenchEntrypoint` / `verifyWorkbenchMcpStdio` — switch to
  `java -cp <thin + Workbench runtimeClasspath files> WorkbenchApplication`.
  In-repo verifies MAY use the module `runtimeClasspath`. Consumer
  launcher must not. Keep `--version` and MCP JSON-RPC assertions.
- `verifyWorkbenchArtifact` — keep required Workbench + protocol classes
  and Main-Class. Add denylist `javafx/`, `javafx-natives/`,
  `io/modelcontextprotocol/`, `com/fasterxml/jackson/`. Require the
  generated manifest resource to be present and parseable. Optional
  thin-jar size guard: standalone JAR `< 16 MiB`.
- `verifyWorkbenchPublishedDependencyContract` — expected becomes the
  nine first-level GAVs above. Protocol must not appear. Pickleball
  must not appear. Compare `(g,a,v)` triples; ignore classifier/scope.
- `verifyEmbeddedWorkbench` — keep exact one payload, byte-identical to
  the standalone thin JAR, Main-Class `WorkbenchApplication`. Outer
  flattened scan keeps `tools/dscode/workbench/` +
  `io/modelcontextprotocol/` and adds `javafx/` + `javafx-natives/`.
  Do **not** forbid `com/fasterxml/` on the outer Pickleball JAR.
- `verifyWorkbenchRuntimeBoundary` / `verifyStrictControllerIsolation`
  stay; do not weaken. Workbench runtimeClasspath may still resolve
  OpenJFX / MCP-core / Jackson.
- `reportWorkbenchMcpImpact` — keep the print; size drops to a few MB.
- Any Workbench test that `ProcessBuilder`-launches
  `pickleball.workbench.test.jar` with `-jar`
  (`WorkbenchMcpServerTest` and similar) must use `-cp` after resolve.
- Any test that assumes `javafx-natives/` inside the controller JAR must
  be rewritten to ordinary platform JARs on the child classpath.

## Gates

### Gate A — thin JAR + POM contract

- Remove native staging from `pickleball-workbench/build.gradle`.
- Shadow only Workbench + protocol.
- Generate `META-INF/pickleball/workbench-runtime-libs.txt`.
- Publish POM with the nine pinned first-level libs.
- Rewrite `verifyWorkbenchArtifact` and
  `verifyWorkbenchPublishedDependencyContract`.

Acceptance:

```text
./gradlew :pickleball-workbench:verifyWorkbenchArtifact \
          :pickleball-workbench:verifyWorkbenchPublishedDependencyContract \
          :pickleball-workbench:verifyWorkbenchRuntimeBoundary
```

Thin JAR exists at the historical path, Main-Class is unchanged, forbidden
packages are absent, manifest resource is present, POM matches the nine
GAVs, project dependency is still only protocol.

### Gate B — embed / verifyEmbeddedWorkbench

- Root `shadowJar` still `dependsOn` the Workbench thin/shadow equivalent
  and copies it to the nest path.
- Keep `verifyEmbeddedWorkbench`. Tighten flattened scan as specified.
- Do not add Workbench libs to the Pickleball published POM.

Acceptance:

```text
./gradlew verifyEmbeddedWorkbench
```

Exactly one opaque payload; bytes match the standalone thin JAR; outer
JAR has no flattened Workbench/MCP/JavaFX packages.

### Gate C — launcher `-cp` + manifest + cache resolve

- Extract thin JAR as today.
- Parse manifest, substitute `CLASSIFIER`, resolve into
  `.pickleball/workbench/lib/2.1.11/`, fork via `java -cp`.
- `command()` is the only fork seam.
- `MAX_PAYLOAD_BYTES = 32 MiB`.
- Launcher never loads JavaFX/MCP/Jackson into the consumer JVM.
- Update `PickleballWorkbenchLauncherTest` and any session-command tests.
- Maintainer `verifyWorkbenchEntrypoint` / `verifyWorkbenchMcpStdio` use
  build-time `-cp` from Workbench `runtimeClasspath` + thin JAR.
- Update `WorkbenchApplication` help strings off `java -jar only`.

Acceptance:

```text
./gradlew :pickleball-workbench:verifyWorkbenchEntrypoint \
          :pickleball-workbench:verifyWorkbenchMcpStdio
```

plus focused `PickleballWorkbenchLauncherTest`. Command shape is
`-cp` + main class, not `-jar`. Resolve failure message names the GAV
and searched locations.

### Gate D — JavaFxSupport

- Delete shaded-natives extraction.
- Keep Text fallback and `platformKey()`.
- Child classpath uses ordinary OpenJFX platform JARs.

Acceptance: Workbench unit tests that touch JavaFX initialization still
pass; no remaining `javafx-natives/` references under
`pickleball-workbench/src`.

### Gate E — docs / agent indexes

- Update every self-contained / empty-POM / `java -jar` / shaded-natives
  claim listed above.
- Refresh packaged guidance mirrors and agent indexes.

Acceptance:

```text
python scripts/sync_consumer_guidance.py --check
python scripts/refresh_agent_index.py --check
python scripts/verify_agent_contract.py
```

Consumer-facing launcher class and args unchanged.

### Gate F — full isolation + consumer path + size

```text
./gradlew verifyStrictControllerIsolation :pickleball-workbench:test
./gradlew publishToMavenLocal
```

Then the Maven consumer path as practical (do **not** run `@all`;
Workbench isolation changes stay on `@control-bridge` and/or
`@step-override-bridge` with `pkb_parallel=80` if a consumer launch
probe is needed).

Acceptance scoreboard must show:

- Outer Pickleball JAR near pre-embed scale except the thin nested
  controller (few MB, not ~160 MB).
- Nested path still present.
- No flattened Workbench / MCP / JavaFX in the outer JAR.
- Pickleball POM still has no JavaFX, MCP, Jackson-from-Workbench, or
  `pickleball-workbench`.
- Workbench POM lists the nine pinned controller libs.
- Forked controller `--version` / `mcp` run via `-cp`, not fat
  `java -jar`.
- Isolation tasks still pass.
- VERSION file/string is still `2.1.11`.

## Validation reminder

Never use `@all` as Workbench migration validation. Prefer:

```text
./gradlew verifyStrictControllerIsolation :pickleball-workbench:test
```

or `scripts/agent_validate.sh --workbench` when the environment supports
the complete flow.

## Commit policy

Commit and push on `2.1.11` without a VERSION bump. Do not publish remote
release artifacts. End the Build turn with the Gate F scoreboard.
