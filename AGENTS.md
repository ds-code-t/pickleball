# Pickleball Agent Contract
## Purpose

Pickleball is a Java 21 testing framework distributed for use by consumer projects as a Maven dependency.

The repository also contains `maven-consumer-project`, which is both:

1. An example of how a consumer configures and uses Pickleball.
2. An executable compatibility and integration test for the framework.

Consumer scenarios start a local test-site server and exercise browser behavior through the in-repo Java DOM engine, with WebDriver for browser lifecycle, as well as REST/SOAP-style service calls, mappings, templates, dynamic steps, nested flows, and component scenarios. The default execution engine is native. Cucumber is a compatibility adapter (`pkb_engine=cucumber`).

## Default agent behavior

For any request to add, change, fix, refactor, or remove project functionality:

1. Read this file.
2. Read `docs/agent/feature-map.md`.
3. Locate the current implementation, tests, consumer scenarios, and documentation for the affected capability.
4. Infer repository setup, build configuration, project goals, and established conventions from the repository.
5. Make the requested change across every affected surface.
6. Run the applicable validation.
7. Report changed behavior, compatibility implications, documentation updates, and validation results.

Do not require the user to repeat the project background, repository layout, test-server setup, dependency model, documentation policy, or definition of done.

Ask for clarification only when the requested product behavior remains materially ambiguous after reviewing the repository. Prefer existing conventions and backward-compatible behavior when a reasonable interpretation is available.

### Diagnostic investigation protocol

When investigating Pickleball diagnostic evidence, use the shallowest evidence layer that completely answers the question. Do not advance to a denser layer merely because it exists.

Use this escalation order:

1. `run-catalog.json` to choose relevant runs.
2. Selected `run-index.json` files and `clusters.json` for outcomes, scenario identity, failure grouping, capabilities, retention state, and representative visual references.
3. Selected scenario `summary.json` files for additional sparse scenario detail.
4. Only the relevant scenario `events.jsonl` when exact step/lifecycle/order detail is still unanswered.
5. Existing `comparisonToPrevious` visual metadata or Pickleball fingerprint/run comparison before opening any screenshot.
6. A representative PNG only when the semantic content of a visual difference must be understood.
7. `trace.jsonl.gz` or interrupted raw `trace.jsonl` only when structured/INFO+ evidence is insufficient.

Stop reading as soon as the current layer answers the investigation with sufficient confidence.

For visual evidence:

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` metadata for adjacent screenshots.
- For cross-run or explicit fingerprint comparison, use Pickleball's `DiagnosticCli`, `DiagnosticRunComparator`, or `VisualFingerprintComparator`; do not manually decode `.pkbf` files or invent an image-comparison algorithm.
- `decodedPixelsExactlyEqual=true` means the decoded rendered pixels are equal; no PNG inspection is needed to establish visual equality.
- `IDENTICAL` ends a visual-difference investigation unless the user explicitly needs the image itself.
- `VERY_SIMILAR`, `SOMEWHAT_SIMILAR`, or `VERY_DIFFERENT` establish that pixels differ and provide magnitude. Open a representative PNG only if the question requires interpreting what changed.
- Raw PNG byte equality proves the files are identical, but raw PNG byte inequality does not prove their rendered pixels differ because encoding or metadata may differ. Prefer decoded-pixel equality from the fingerprint comparator.

For routine diagnostic utility operations, prefer `tools.dscode.common.reporting.diagnostic.DiagnosticCli` over constructing a Maven classpath and JShell script. See `docs/diagnostic-reporting.md` for commands.

### Controlled diagnostic reruns

When an AI agent launches Pickleball tests and the intended execution settings are known, use `pkb_runvars` as the authoritative test-run input. Put intentional selection and execution RunVars such as `pkb_tags`, `pkb_name`, `pkb_example`, browser, reporting/logging controls, and other non-secret overrides in `pkb_runvars` rather than relying on ambient optional defaults. Use ordinary JVM `-Dpkb_*` RunVars or `pkb_profile` instead only when the task specifically needs to exercise normal configuration/profile precedence or the user explicitly requests those semantics. Protected secret values and diagnostic lineage remain separate.

Optional sealed `pkb_overriderunvars` freezes a complete already-resolved RunVar snapshot for that execution only. When it is absent or blank (and there are no expanded `pkb_overriderunvars.*` members), resolution is unchanged. When it is non-blank, that run ignores files, defaults, JVM `-D pkb_*` RunVars, profiles, `pkb_runvars`, execution-context inheritance, and templates for the Pickleball RunVar set. Maven/JVM/env still exist as the process; they must not change the Pickleball RunVar set. This is ignore-sources, not erase-process. The six context keys (`pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, `pkb_configpath`) must be present (blank tombstones allowed; missing keys are errors). No templates in the sealed bag. Sealed + `pkb_runvars` or a composing `pkb_profile` is a configuration error. `pkb_parallel=auto` is the only allowed derived mutation. Dry-run with `PKB_props.resolveRunVars` / `DiagnosticCli resolve-runvars` / Workbench `hint` without starting tests, then launch `-Dpkb_overriderunvars=<compact complete map>` and compare `runProfileFingerprint`. `pkb_overriderunvars` is INPUT; `pkb_run_profile` remains derived OUTPUT only.

When an investigation requires a rerun, start from the selected run's retained `runProfile` in `run-index.json`. Replay that retained final RunVar set through `pkb_runvars`, using compact `pkb_runvars` for ordinary values or expanded `pkb_runvars.<pkb_var>` members when avoiding nested assignment parsing is safer. Never mix compact and expanded `pkb_runvars` forms. `pkb_run_profile` is internal derived output and must never be supplied as input; compact or expanded external `pkb_run_profile` values are configuration errors.

A partial controlled input inherits only missing project execution-context RunVars: `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`. An explicit blank value suppresses inheritance and remains a blank tombstone in the canonical `pkb_run_profile`, preserving the existing subsystem fallback semantics when replayed. Do not reconstruct optional RunVars by manually combining runner defaults, property files, named profiles, system properties, and Cucumber aliases when a retained final run profile is available.

Diagnostic lineage is metadata, not part of the execution RunVars. Supply `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, `pkb_changed_variables`, `pkb_run_id`, `pkb_agent_id`, `pkb_run_group`, `pkb_run_sequence`, `pkb_run_who`, and `pkb_run_why` separately from `pkb_runvars`; profiles must not contain those keys. After the rerun, verify `runProfileFingerprint` / compatibility field `directRunProfile` and the declared changed variables before attributing observed differences to the intended configuration change. Use `runProfileFingerprint`, not `configurationHash`, as the equality check for the final RunVar set. See `docs/ai-run-configuration.md`.

## Sources of truth

Use all relevant evidence rather than trusting one file in isolation:

- Current implementation under `src/main/java` and `src/main/aspectj`
- Dependency-neutral controller/worker wire contracts under `pickleball-control-protocol`; this module must remain JDK-only
- Dynamic-control companion source module under `pickleball-control-api`; its classes are bundled into the main `tools.dscode:pickleball` artifact and are not a separate consumer dependency
- Controller-only Workbench source under `pickleball-workbench`; it may depend on `pickleball-control-protocol` but never on Pickleball core or `pickleball-control-api`
- Consumer-hosted internal Java checks under `maven-consumer-project/src/test/java`
- Executable consumer examples under `maven-consumer-project/src/test`
- `README.md` and the guides under `docs`
- Build and dependency configuration in `build.gradle`, `settings.gradle`, `pickleball-control-protocol/build.gradle`, `pickleball-control-api/build.gradle`, `pickleball-workbench/build.gradle`, and `maven-consumer-project/pom.xml`
- `docs/agent/feature-map.md` for navigation, not as a replacement for source inspection

When implementation, tests, examples, and documentation disagree:

1. Identify the inconsistency.
2. Determine the intended contract from the strongest available evidence.
3. Preserve established consumer behavior unless the user explicitly requests a breaking change.
4. Update the inconsistent surfaces together.
5. State the resolution in the final report.

## Repository structure

- `src/main/java` — framework implementation and Cucumber integrations. In-page agent surfaces and the popup live in `tools.dscode.common.agentcontrol` and `tools.dscode.common.agentsurface` (`docs/direct-control-handoff.md`).
- `src/main/aspectj` — AspectJ integrations and weaving behavior
- `src/main/resources` — framework resources
- `pickleball-control-protocol` — JDK-only shared boundary for core/worker and Workbench: versioned wire records, capability/version constants, request/response envelopes, plus layout/store helpers (`PickleballLocalLayout`, `PickleballVersion`, `PickleballArtifactLocator`, `PickleballLocalStore`) so `.pickleball` paths stay dependency-free. No non-JDK libraries; no Pickleball/Cucumber/Selenium runtime.
- `pickleball-control-api` — internal companion source module for retry-friendly detached execution, Gherkin utilities, ParsingMap/NodeMap inspection and emulation, and dynamic controller tooling; bundled into the main Pickleball artifact rather than published separately
- `pickleball-workbench` — controller-only GUI/MCP/synchronization/process client; its executable must contain no Pickleball, Cucumber, Selenium, REST-assured, worker, or behavioral control-API implementation
- `src/test` — reserved for tests that must run inside the framework build
- `docs` — detailed user-facing documentation
- `maven-consumer-project` — executable Maven consumer example
- `maven-consumer-project/src/test/resources/features` — consumer acceptance scenarios
- `maven-consumer-project/src/test/resources/calls` — service-call definitions
- `maven-consumer-project/src/test/resources/configs` — example configuration
- `maven-consumer-project/src/test/resources/site` — local browser/service test site
- `maven-consumer-project/src/test/java` — runner, local server, support code, and internal framework checks compiled against the locally published dependency

## Dalton

Dalton is on by default. An explicit `pkb_dalton=false` (or `PKB_DALTON`, or `dalton.properties` `enabled=false`) is the opt-out. `DaltonConfig.source` reports `property`, `env`, `file`, or `default`. The as-built design is `docs/maintainer/3.0.0-dalton-design.md`. Dalton is not the core. Git files stay the source of truth.

- Git files are the source of truth. The working copy is `.pickleball/dalton/dalton.db` (gitignored, including the explicit `.pickleball/dalton/` entry). A fresh clone has no database and must still run.
- CLI: `dalton status`, `dalton hydrate` (`--path`, `--force`, `--dry-run`), `dalton extract` (`--path`, `--force`; `hydrate-out` is the same command), `dalton history` (`list`, `show`, `record`, `retain`), `dalton entity` (`list`, `get`, `put`, `remove`, `export`), and `dalton feature put <relative-path>` (`--text` or `--file`). Extract writes stored bytes and does not reformat. It leaves an unedited file, and it refuses when the file and the database both changed unless `--force` is set. `dalton entity export` is that write limited to `dalton-entities/`. `dalton feature put` sets `db_edited`, leaves `last_synced_hash`, re-projects scenarios, steps, tags, docstrings, and data tables, and rolls back when the Gherkin is not valid. `dalton scenario put` and `dalton step put` are rejected. Hydrate reports `untracked DB edit on <path>` when `content_hash` moved and `db_edited` is still 0, and it does not overwrite that row unless `--force` is set. A flagged edit whose file is still the last sync is kept. History sits beside diagnostic reports and does not apply `pkb_reportretention`. Gradle `daltonStatus`, `daltonHydrate`, `daltonExtract`, `daltonFeature`, and `daltonHistory`, the Workbench action `DaltonCliForwarder`, and MCP `dalton` only forward to that CLI. Auto-hydrate on a run happens unless Dalton is explicitly off. `dalton status` prints `enabled=<bool> source=<source>`. With Dalton on, the JUnit feature parser and the CLI feature supplier read hydrated `.feature` bytes, and `<entity:Type#id.field>` resolves inside a running step. A secret field stays a reference. With the flag off, loading does not open the database and entity references are not special.
- CLI reads: `dalton files list|show`, `dalton features list|show`, `dalton scenarios list` (`--feature`), `dalton steps list` (`--feature`, `--scenario`), and `dalton conflicts`. `--json` is one stable object. These commands do not create a database. Gradle `daltonQuery` only forwards to that CLI. The Workbench Dalton tab uses `DaltonCliForwarder` for those reads and for feature and entity edits. It does not open SQLite.
- Discovery: `include` (default is the fixed directories), `exclude` (defaults always include `.git`, `target`, `build`, `.pickleball`, and common binary extensions), `max-file-size` (default 1 MiB), and `binary-policy` (`skip` by default, or `hash-only`). Set them in `dalton.properties` or with `pkb_dalton_include`, `pkb_dalton_exclude`, `pkb_dalton_max_file_size`, and `pkb_dalton_binary_policy`. `hash-only` stores a binary as a reference row (empty raw content, sha256, git blob, byte size) and extract reports `binary_ref_changed` instead of writing it back. `dalton status` prints the skipped count, sample reasons, `binaryRefs`, and Git provenance (`git.commit`, `git.branch`, `git.remote` with userinfo stripped, `git.githubRepo`, `git.dirty`, `git.changed`) and does not create a database. `--path` obeys those limits. `--path --force` is the only bypass. `pkb_dalton_log=discover,git,binary` logs each reason. Hydrate records `git_commit`, `git_branch`, `git_remote`, `github_repo`, and `git_dirty` on `provenance`, and `git_blob` on each file row. No git binary, or not a repository, leaves those fields empty and warns once.
- `org.xerial:sqlite-jdbc:3.47.2.0` is a published runtime dependency (POM scope `runtime`). It is not shaded into the consumer shadow JAR. When Dalton is on and the driver is missing, auto-hydrate and the CLI commands that open the database fail with `Dalton is enabled but org.xerial:sqlite-jdbc is not on the classpath. Add it as testRuntimeOnly / test scope or use the Gradle dalton* tasks. Dalton is on by default; add org.xerial:sqlite-jdbc or set pkb_dalton=false to run file-only.` They do not fall back to the files.
- `dalton run` is the warm path. It sets `pkb_dalton=true` and launches `pickleball-test-engine` through `WorkbenchDirectRun`, so the result matches a normal engine run. It prints one line per scenario (`scenario PASSED|FAILED <name>`) and one line per pickle step (`step PASSED|FAILED <text>`). Glue is fingerprinted by path, size, and modification time. A change runs one wrapper compile and restarts the worker. `--dry-run` prints the plan and writes nothing. The classpath is `pkb_dalton_run_classpath` or the Workbench `classpath.cache`. Gradle `daltonRun`, the Workbench Run button, and MCP `dalton` only forward to the CLI. `pkb_dalton_log=run` logs that worker. Full Gradle or Maven stays the CI build.
- Do not store cleartext secrets in Dalton. Entities hold secret references only, using the existing secrets and config approach. `secret-fields=` in `dalton.properties` (or `pkb_dalton_secret_fields`) adds names to that list. A high-entropy value warns and is still stored. The database is not encrypted.

## Public contracts

Treat these as consumer-visible contracts unless source evidence clearly shows otherwise:

- Public Java APIs, including the optional dynamic-control API and semantic hook contract. `tools.dscode.Values` is the static value API (`getValue`, `saveValue`, the map-specific pairs, Jackson `Class` overloads, `softFail`, `throttle`, `registerPrefix`, `cacheGet` / `cachePut`, and `secret`). `PrefixValue` is the only prefix return type. `~MASK;` and `~UNMASK;` keep known secrets out of logs.
- Element text `matching` is a full match and honors quote types the way phrase `matches` does. This changed in 2.1.16.
- Element find, click, submit, clear, type, hover, drag, scroll, keys, wait, page source, and inspect go through `ElementOperations`. Unset, `auto`, and `off` use `SeleniumAdapter` and do not call a direct protocol. `pkb_composite=on` uses `DomCompositeAdapter` and the in-repo Java DOM engine. `ElementWrapper` click, submit, sendKeys, and clear are one `ElementOperations` call each. Submit, double-click, context-click, element drag, and named keys use the real verb when `capabilities.actions` lists `submit`, `dblclick`, `contextclick`, `drag`, or `key`. A binary that lists only click, type, and scroll keeps the old mapping: submit is click, double-click is two clicks, context-click is one click, and drag is click, scroll, click. An offset drag stays on that mapping because `drag` takes two locators and no dx/dy. Gherkin phrases stay the same. XPath and CSS from `ExecutionDictionary` pass through. Live `inspect-page` and `find-element` use `PageElementQuery`, which calls `ElementOperations.describe` once and counts from that batch. `workbench_element_inspect` uses `ElementControl`, which calls the same `describe`. `pkb_composite=off`, and also unset or `auto`, uses the Selenium calls inside `ElementOperations` for that run (one script when the driver can run it). A missing or failed engine, or a failed join, does that only after `pkb_composite=on`. An `on` fallback prints `error:` and `fix:` once per run and reason and records a `composite.fallback` diagnostic event. It does not consult `pkb_composite_fallback_log`. WebDriver stays for browser lifecycle (navigation, window handles, CDP). There is no `BrowserSteps.getJavascriptExecutor()`; scripts go through `ElementOperations.script`. Desktop `click` and `type` fail with `error:` / `fix:` until `capabilities.desktop` lists those verbs. A press of letters and digits uses composite type with `--append` (a lone A-Z is lowercased). Chords the `key` verb can represent use `key` when it is listed. `A+B` and `CONTROL[A+B]` focus with a composite click, then WebDriver Actions, because a chord has one non-modifier. A state check reads the snapshot and does not find again. An element snapshot is one `eval` when `capabilities.actions` lists `eval`, and the WebDriver script when it does not. An all-contexts scan is one query when the binary accepts `--pierce`, and the Java walk when it does not. `the 3rd` and `every 2nd` use one `PositionSelection` on a normal find and on that scan. The scan wraps only the hits position keeps. An element snapshot reads tag, text, attributes, and bounds from the hit and evaluates `selectedValue`, `siblingIndex`, and `stableSelector` on demand. `pkb_composite_log=position` writes `TRACE position apply`. A visible or present wait on a composite hit is one query (`poll_ms` 150, `stability_ms` 0) when the binary accepts `--poll-ms`, and the 150 ms loop when it does not. A context find uses query --within when capabilities.query_flags lists within, including a context with no id and a parenthesized XPath. A binary that does not list it keeps the id/name anchor, and a missing anchor is still an empty find. Nesting is query --nesting on that same find when query_flags lists nesting, after the displayed filter. A binary that does not list it keeps one extra anchor query. A primary binary that has not joined a browser finds on that WebDriver with Selenium, the same hits as `pkb_composite=off`. A process-only session is not a join. `pkb_composite_log=element` writes `INFO element find selenium unattached`. `--within` and `--nesting` are still sent only when `query_flags` lists them. Several element snapshots are one eval when capabilities.eval_nodes is set, and one eval each when it is not. pkb_composite_log=adapter writes INFO adapter within <id>, within fallback, nesting deepest|outermost, nesting fallback, and eval nodes=<n>. `the 3rd`, `last`, and `every 2` are `--nth` and `--every` on that same query when `query_flags` lists `nth` and `every` and the phrase has no `matching` regex. A regex, a missing flag, or an all-contexts scan keeps `PositionSelection` in Java. `INFO adapter nth <n>`, `nth <n> every <step>`, `count <n>`, or `position java`. A count is one `query --count-only` when `query_flags` lists `count-only`. A query adds `geometry: true` when `query_flags` lists `geometry`. The hit then carries `ElementSnapshot.Geometry`: `document_css_px`, `visual_viewport_relative`, `layout_viewport_relative`, `nearest_scrollport_relative`, and `frame_document_relative`. A scrollport hit also carries `scrollport.client` and, only when a content size was stored, `scrollport.content`. Content is not invented. A control also carries `expanded`, and `revealed` only when it is open and a region was stored. A collapsed control does not grow a predicted box. `corners` reports hit-grid samples; a null hit is not a hit. `scrollChain` is the ancestor scrollports from that same payload. `DomCompositeAdapter.hitAt` is one `query --point` when `query_flags` lists `point`, and null otherwise. `Bounds` stays the integer AABB. Selenium, and a binary that does not list `geometry`, leave `Geometry` null and do not invent quads. `pkb_composite_log=geometry` writes `TRACE geometry request` and `INFO geometry real` or `INFO geometry fallback`, and `INFO geometry scrollport client` or `scrollport client+content` when that box is on the hit. An expandable hit writes `INFO geometry expanded revealed` or `collapsed`, and `INFO geometry corners <n>hit/<n>miss` when a sample is known. `INFO geometry scroll-chain <n>` or `scroll-chain fallback`. `INFO geometry point` or `point fallback`. `pkb_composite_log=adapter` writes `INFO adapter geometry real` or `geometry fallback`. A refresh, when `eval` is listed and the node is still connected, does not re-find by `stableSelector`. Do not add a second locator. An engine query string on Workbench, MCP, or the control bridge is that same `ElementOperations.describe(driver, EngineQuery)` call. A controller query or action with no exposure is `human`. `lower` is per query and labels every node. `left-of`, `right-of`, `above`, `near`, and `below` on that query are visual distance. A binary without geometry invents no box. `--explain` returns rank and score. An image match promotes to a node or stays a labeled visual-only hit. A limit returns handles for one later act, not a batch click. Category+text stays the dictionary XPath and does not send exposure. Gherkin stays `SelectorTranslator`, and `after` / `before` stay document order.
- `~XML-ESCAPE;` is a conversion directive. XML readers reject `DOCTYPE` and `ENTITY` declarations.
- Experimental JDBC service calls: a `REQUEST.endpoint` that starts with `jdbc:` runs JDBC. Other endpoints stay HTTP. Drivers are published dependencies, not shaded into the runtime JAR.
- Dynamic-control mapping behavior: isolated caller-defined NodeMap contexts, scoped live-map replacement, snapshot materialization, and mapping resolve/lookup/write interception are opt-in and must not alter normal execution when unused
- Maven artifact behavior and runtime dependencies
- Cucumber step phrases and dynamic-step behavior
- Mapping, template, key-expression, and reference syntax
- Configuration keys, defaults, and resource lookup rules
- Selenium element lookup, interaction, retry, and stale-element behavior
- Local Chrome and Edge driver startup. Selenium Manager runs first unless `pkb_driver_download_native=false`. A best-effort fallback may then cache a matching Chrome for Testing or Edge driver. Discovery, download, unzip, quarantine, and certificate failures are logged and do not fail the test. Remote WebDriver is unchanged. An explicit `driver.service.driverExecutable` skips the download. The only configuration that fails closed is native and fallback both disabled with no `driverExecutable`.
- Service-call definitions, request/response mapping, and REST/SOAP behavior
- Component-scenario, nested-step, and conditional-flow semantics
- Cucumber compatibility and AspectJ weaving behavior
- The behavior demonstrated by the Maven consumer scenarios

Do not silently rename or remove public syntax, steps, configuration, APIs, or documented behavior.

## Required impact analysis

Before editing, search for:

- The implementation symbol or behavior
- Unit and component tests
- Gherkin phrases and examples
- Related documentation terms and headings
- Consumer configuration and test-site support
- Callers, adapters, interfaces, and serialization formats
- Compatibility assumptions in `maven-consumer-project`

Do not make a behavior change based only on a method or class name.

## Functionality-change requirements

For externally observable functionality, update all applicable areas:

- Framework implementation
- Consumer-hosted internal Java checks
- Maven consumer feature scenarios
- Service-call definitions
- Local test-site endpoints or pages
- Example configuration and test data
- README or detailed guides
- `docs/agent/feature-map.md` when ownership, locations, syntax, or contracts change
- `docs/agent/repository-index.md` when indexed repository files change

A task is not complete merely because the Java source compiles.

### Documentation policy

Update documentation when a change affects supported behavior/syntax, inputs/outputs/value types, defaults/constraints/errors/edge cases, public APIs/configuration/compatibility, or user-visible examples and recommended workflows.

Do not create documentation churn for a purely internal refactor. Prefer updating the existing canonical guide over creating a competing guide.

### Experimental value-conversion syntax

Do not document `ValConverter` special-value syntax in `README.md` or the `docs` directory unless the task explicitly approves it. The markers are experimental and may change or be removed. They may still receive focused implementation and executable test coverage.

### Test policy

Use the narrowest useful test first, then run broader validation.

For consumer-visible behavior, add or update an executable scenario in `maven-consumer-project` whenever practical. A consumer scenario is preferred over a prose-only example.

Internal Java checks should normally live in `maven-consumer-project` and be exercised by the dedicated Cucumber feature so they compile and run against the locally published Pickleball dependency. Keep a test under root `src/test` only when it must execute inside the framework build itself.

Tests must cover the requested behavior and meaningful compatibility or edge cases. Do not weaken or delete assertions merely to make a change pass. Plain forms that `@all` must run are listed in the Verify behavior table in `docs/agent/change-checklist.md`. Group those scenarios into the feature files named there. Do not add a feature file per form.

For Workbench, control-protocol, worker bridge, launcher, or nested-payload changes, never use `@all` as migration validation. Run only the smallest affected tags (currently `@control-bridge` and/or `@step-override-bridge`) and set `-Dpkb_runvars.pkb_parallel=80` when the focused environment can safely benefit. Preserve this rule in future Workbench plans and handovers.

### Workbench controller-isolation invariant

Pickleball Workbench is the control plane, not a second Pickleball runtime. The only shared Java boundary is `pickleball-control-protocol`. Workbench must not compile against, resolve, shade, load, or execute the root Pickleball project, `tools.dscode:pickleball`, `pickleball-control-api`, the consumer's classes, or runtime libraries such as Cucumber, Selenium, and REST-assured. Never “fix” a Workbench compilation problem by restoring `implementation project(':')`, `pickleballPublishedElements`, a Pickleball Maven dependency, or core shading.

Only the separate consumer worker JVM executes Pickleball. `WorkbenchWorkerManager` must launch `ControlProtocol.WORKER_MAIN_CLASS` by name on the consumer build's captured test-runtime classpath, and must verify the worker PID, Pickleball code source, synchronized version, and absence of the Workbench controller artifact. Commands and state cross the local authenticated versioned protocol, not direct Java calls.

The published Pickleball JAR embeds the completed controller-only Workbench JAR as exactly one opaque payload at `META-INF/pickleball/workbench/pickleball-workbench.jar`. It must not flatten Workbench/MCP classes into the outer runtime, and the nested Workbench must not contain core. The ownership mnemonic is: **Pickleball may contain Workbench; Workbench must not contain Pickleball.**

The Swing window is a client of the launcher and command-queue session commands (`open-scenario`, `example`, `play`, `from-here`, `execute-step`, `stop`, `diagnostic-run`, and the other `WorkbenchSessionActions` operations). Do not add a second GUI-only implementation, and do not add a pixel, WebView, or JavaFX click tool. When a Workbench window is already open, an agent drives that session with those commands so the person sees the result. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. When nobody is watching, headless `discover`, `confirm`, and `isolate` stay the path. Build invocation uses the consumer project wrapper (`mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`, or `java -jar` on the wrapper jar). Pick the script from the OS. Do not require a machine-wide Maven or Gradle install. Do not embed Maven or Gradle in MCP or the launcher, and do not add a general `mvn` or `gradle` tool. Read the short log `.pickleball/agent-log` before a run, after a run, and during a long session, instead of the dense diagnostic log. Use your own run id. When a Workbench window is already open, only one agent drives it. Other agents stay headless on their own run ids. Write start and stop lines. Put purpose and findings on the run record. Use the inbox only for a short note to one named agent. Taking that note deletes it. A note for every agent is a post, not an inbox file, and listing or taking does not delete it.

The Workbench GUI is only a lightweight head over state and controls that already exist under the hood. Agents have direct access to that state and those controls, and direct control of the GUI controls, so they can collaborate with a person or present data and reports in the window. The GUI must not own behavior the agents cannot reach. Do not click JavaFX or WebView from tests. Do not embed Maven or Gradle. Agents run the project wrapper. A private `.pickleball/runs/<run-id>/` directory is only for an agent or Workbench run. A normal `mvn test` does not create it, does not copy configs, does not set `user-data-dir`, and still writes both HTML reports and still reads the project configs. A private run copies the project configs into `config/` once, when the test or Workbench worker JVM opens the run after `pkb_configpath` is resolved, and reads and writes only that copy. The copy uses the same path rule as a normal run. A root `configs/` directory is chosen when `wd-file:` points at it. `session-start`, `discover`, and `confirm` do not copy before that JVM exists. A destination that contains only the bundled `CHROME_HEADLESS.yaml` is not a finished copy. `pkb_configpath` stays the project path. The Config tab beside Mapping is a view of that copy. Sync, run start, and other long loads stay off the event thread and show a banner that names the work or the error. Split panes drag so one panel can take almost the whole window. Parallel workers in one private run each get `browser-profile/<worker>`. `pkb_compositeReport=false` and `pkb_scenarioReport=false` are the agent and Workbench defaults. Do not change the `pkb_parallel` default. Isolate stays one scenario. Do not treat resolve-runvars as Discover's environment. Trust the post-Discover run record. Sealed input is `pkb_overriderunvars` only.

## Build and validation

Use Java 21.

Framework validation:

```shell
./gradlew test
```

Strict controller/artifact validation:

```shell
./gradlew verifyStrictControllerIsolation :pickleball-workbench:test
```

Windows:

```powershell
.\gradlew.bat test
```

Workbench/control-bridge scenario validation must stay focused:

```shell
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_parallel=80 -Dpkb_runvars.pkb_tags=@control-bridge
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_parallel=80 -Dpkb_runvars.pkb_tags=@step-override-bridge
```

Run the two commands sequentially because both scenarios intentionally exercise the process-global bridge bootstrap.

Or use the focused turnkey validator:

```shell
scripts/agent_validate.sh --workbench
```

For broad consumer-visible changes outside the Workbench/controller-isolation surface, publish the current framework artifact locally and run the Maven consumer. The focused Workbench rule above takes precedence for that surface:

```shell
./gradlew test publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
```

Windows:

```powershell
.\gradlew.bat test publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
```

Repository contract and generated-index checks:

```shell
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

Or run the turnkey validator:

```shell
scripts/agent_validate.sh
```

Windows:

```powershell
.\scripts\agent_validate.ps1
```

If a required validation cannot run, state exactly what was not run and why. Never claim that a test passed without executing it.

## Change boundaries

- Keep changes focused on the requested behavior.
- Do not perform unrelated refactors.
- Do not change versions, publish remote artifacts, create releases, or push branches unless explicitly requested.
- Never reuse a published version number. Once a version is on Maven Central or JitPack, any further change ships under a new version, even a one-line fix. `export-guidance` prints `Pickleball <version> built from commit <sha>` so two builds can be told apart.

### Release checklist

1. Bump the version; never republish an existing one.
2. Confirm `META-INF/pickleball-build.properties` in the built jar has a non-blank `commit`.
3. Publish.
4. Before announcing, verify the new version resolves through Maven Central and its mirrors (for example `mvn -U dependency:get -Dartifact=<group>:pickleball:<version>` from a clean local repository, plus the search.maven.org listing). Do not announce or tell consumers to upgrade until it resolves.
- Do not edit generated build output.
- Preserve backward compatibility unless the user explicitly approves a breaking change.
- Dynamic-control additions must remain opt-in: no handler/API call means normal scenario traversal, ParsingMap construction/order, NodeMap references, resolution, and writes retain their pre-control behavior.
- Workbench changes must preserve strict physical, dependency, process, and classpath isolation; separate JVMs alone are not sufficient.
- Follow existing code style and patterns before introducing new abstractions.
- Do not replace executable examples with prose.
- Never store secrets, credentials, machine-specific paths, or private data in agent instruction files.

### Core technical debt: call and component path grammar

**Status: intentionally deferred for `pkb_callpath` and `pkb_componentpath`.**

Changed in 2.1.15. `pkb_features`, `pkb_datapath`, `pkb_configpath`, and the Gherkin `<file:...>` reference share one prefix rule:

| Prefix | Meaning |
|---|---|
| (none) | resources folder (classpath) |
| `file:` | resources folder (classpath), same as no prefix |
| `classpath:` | resources folder (classpath) |
| `wd-file:X` | working-directory/X |
| `wd-file:/X` | absolute path /X |

A path with no prefix is a classpath resource, including a path that contains a slash. `qa/configs` is the resources folder. `wd-file:src/test/resources/qa/configs` is that folder under the working directory. `wd-file:/opt/...` is an absolute directory. `file:/opt/...` is the classpath resource `opt/...`. There is no project-root prefix. A `file:/...` or unprefixed `/...` path that is not on the classpath fails with a hint: use `wd-file:/...` for a filesystem path. A relative miss and a missing `wd-file:` path stay empty. The default `pkb_datapath` is `data`. `wd-file:features` is passed to Cucumber as `file:features`. A normal run and an agent or Workbench private-run copy resolve the same value to the same folder. A root `configs/` directory is chosen when `wd-file:` points at it.

`pkb_callpath` and `pkb_componentpath` keep their historical project-relative defaults (`src/test/resources/calls`, `src/test/resources/component`). `pkb_glue` is Java/Cucumber glue syntax, not a resource path.

**Maintainer rule:** do not change `pkb_callpath` or `pkb_componentpath` path semantics opportunistically while changing profiles, `pkb_runvars`, `pkb_run_profile`, or execution-context inheritance. Address that remaining debt only in a task explicitly scoped to those two keys, with compatibility tests and migration documentation.

### Temporary agent workspace

Use `.agent-work/` for disposable scripts, patches, generated bundles, intermediate files, migration utilities, investigation output, and other agent-created artifacts that are not maintained project code.

Do not place temporary automation in the repository root, `scripts/`, source directories, test directories, or documentation directories. The committed `scripts/` directory is only for reusable project-maintenance tooling intended to remain part of the repository.

Delete temporary files after they have served their purpose. Before completing a task, confirm that `.agent-work/` contains no files that need to be retained. Never force-add `.agent-work/` content to Git.

## Agent-maintained context

`docs/agent/feature-map.md` is a living navigation map. Update it when a capability is added/removed, responsibility moves, a canonical test/scenario/endpoint/guide changes, or public syntax/compatibility expectations change.

Run:

```shell
python scripts/refresh_agent_index.py
```

after adding, moving, or removing indexed source, test, documentation, or consumer files.

Do not use these files as substitutes for inspecting current source.

## Verification lessons

Unit tests and a single consumer_sim run are not enough to call a Workbench bug fixed. Report-6 was green that way and the Windows consumer still hit every report-7 bug.

- Single-run fixtures hide workspace resolver bugs (latest == requested).
- Expression-only glue hides regex matching bugs.
- One-shot isolate hides restart regressions and progress-file state.
- Idealized timing hides slow paths between stages.
- Single-run idealized fixtures hide log-rotation and multi-start evidence loss.
- Stage-overlap without intermediate progress marks hides materialize cost.
- "Exited before becoming healthy" without the underlying cause hides parent-pre-check opportunities.
- Global flags accepted but ignored create silent mismatches with docs.

Future rounds must use multi-run ids, mixed glue (regex and Cucumber expressions, including a blank `pkb_glue`), repeated start/stop, and timing budgets in `scripts/consumer_sim.py` before claiming fixed. An item is done only when that consumer scenario passes. Unit tests alone never close it.

## Definition of done

A functionality change is complete only when:

- The requested behavior is implemented.
- Applicable compatibility has been preserved or a breaking change is clearly identified.
- Relevant consumer-hosted internal Java checks exist and pass.
- Relevant consumer scenarios exist and pass when applicable.
- Workbench/core changes retain the neutral protocol boundary, controller-only artifact scan, opaque nested payload, and consumer-owned worker runtime.
- Documentation matches the resulting behavior.
- The feature map remains accurate.
- The generated repository index is current.
- The final response summarizes the implementation, affected contracts, documentation, tests, and any validation not performed.

## Execution engine

The default execution engine is native. `pkb_engine`, then `PKB_ENGINE`, then `engine.properties` `mode=` choose `native` or `cucumber`. `pkb_engine=cucumber` keeps the Cucumber adapter. An unrecognised value fails at first use. Gherkin syntax and step annotations do not change. Every Cucumber-core aspect is still required by the adapter, so none were retired.

Pickleball-owned model types live in `tools.dscode.engine.model`: `EngineStep`, `EngineScenario`, `EngineFeature`, `Table`, `DocText`, `Status`, and `RunResult`. `CucumberModelConverter` is the adapter edge. A Cucumber run does not change when the adapter is selected.

`StepRegistry` reads the same `@Given`, `@When`, `@Then`, `@And`, `@But`, and other `io.cucumber.java` step annotations. Those values stay single string literals. Matching uses Cucumber's `ExpressionFactory` (regex and Cucumber expressions, parameter types, DataTable and DocString parameters, step overrides, and ambiguity). Dynamic, block-conditional, RUN, and service-call steps execute on the native engine. `pkb_engine_log=match` or `registry` writes `.pickleball/engine/engine.log`.

`ExecutionContext` holds the scenario, step stack, map scopes, phase, failures, timing, and browser and service resources. `GlobalState.getCurrentScenarioState` still returns the Cucumber thread local when one is set, and the context's legacy scenario only when that thread local is empty. `ParsingMap.getRunningParsingMap` uses the context map only in that same case.

Mappings, data elements, data operations, and assertions use `Table` and `DocText` (`NeutralRows`, `TabularDataAdapter`, `NeutralAssertions`). Conversion to a Cucumber `DataTable` or `DocString` stays in `CucumberModelConverter`.

Gherkin is parsed with the gherkin library, then converted. `EncodingRewrite` repeats the encoding line swap. `GherkinEngineParser.expand` keeps placeholders, marks background steps, and applies scenario tags. Sources are files, Dalton rows, in-memory text, and a step list with no file. `FeaturePathLoadCache` is the one loader for file bytes and Dalton bytes.

`ScenarioSource` resolution uses tags, name, feature name, example rows, step text, order, and limit. The rules match `CucumberScanUtil` and `ExampleRowFilter`. Memory text and Dalton rows use that same lookup.

`PickleballEngine.run` and `runStep` run a scenario or one step. Sources are in-memory Gherkin, a runtime step list with nothing on disk, a file, or a Dalton row. `engine help`, `engine run`, and `engine step` are the CLI. The launcher, `dalton run --engine`, Gradle `engineRun`, Workbench `EngineCliForwarder`, and MCP `engine` only forward to that CLI and do not run steps themselves. `pkb_engine_log=run` logs the run.

Steps written during a step (`ExecutionContext.writeSteps`) are inserted into the running scenario and run next, with nothing on disk. The result records `source=runtime`. `pkb_engine_log=run` logs those lines under the `runtime` entity.

`DynamicControl.executeStep` runs when no Cucumber scenario is on the thread. The live and paused path is unchanged. The control bridge uses that native call only when it has no scenario lane. `pkb_engine_glue` names glue classes for that call.

`ParityHarness` and `DualRun` run the same feature on the Cucumber adapter and the native engine. Status and step text are compared. A returned value is compared when both sides recorded one. Plain glue matches. `ASSERT: "a" equals "a"` matches. Repo features and every `maven-consumer-project` feature, including dynamic phrases, match. Browser features that cannot launch a browser fail the same way on both sides.

`EngineLog` stays off unless `pkb_engine_log` or `PKB_ENGINE_LOG` names a capability. `pkb_engine_log_entity` filters the entity. Each line starts with an `Instant` date stamp. `pkb_engine_log_max_bytes` caps `.pickleball/engine/engine.log` (default 1048576 bytes, minimum 64). The native engine fires `BEFORE_CUCUMBER_RUN` before the first scenario and, after the last, the same suite finish as the Cucumber adapter (`AFTER_CUCUMBER_RUN`, log close, ReportPortal, pickleball log stop, `AgentCoordination.finishCurrent`).

## Control engine

The Control Engine is the Java engine in `dev.domengine`. It ships in the pickleball jar. Docs, `capabilities.display_name`, and the CLI help first line call it the Control Engine. It is one engine for browsers and for Windows, macOS, and Linux desktops. `pkb_composite=on` selects it. Unset, `auto`, and `off` use `SeleniumAdapter` and do not look for an engine. There is no second engine. `pkb_composite_engine` is not a selector. Unset does nothing. `java` warns once that the key is no longer used. Any other value fails at engine start. An explicit `pkb_composite_bin` is the Java launcher override. Do not merge open pull requests #2 and #3 on ds-code-t/dom-composite, and do not push to that repository.

`CompositeBinaryLocator` and `DirectControlProvider.resolveBinary("composite", ...)` share one explicit order (`explicitEngineCandidates`): property `pkb_composite_bin`, then `pkb_runvars.pkb_composite_bin`, then env `COMPOSITE_BIN`, then env `pkb_composite_bin`. Env `COMPOSITE_BIN` is working-directory relative. The other explicit slots resolve against the project. Both resolvers return the same absolute path. Then the Control Engine launcher. An explicit binary that is not the Control Engine launcher logs one INFO line naming the source key. An `on` fallback (missing engine, client failed, join failed) prints `error:` and `fix:` once per run and reason and records a `composite.fallback` diagnostic event. It does not consult `pkb_composite_fallback_log`. Do not rename `compositeSessionDir`, `compositeSessionId`, or `session/composite`. The stdio client stays in `dev.domcomposite` and its javadoc says it talks to the Java control engine.

`domengine log list|tail|show` reads the capped engine logs. Pickleball `log` also lists `.pickleball/history.log`. The existing 10 MB front trim on that file is unchanged. The Workbench Logs tab only forwards to that CLI.

`domengine session join` (`--id` or `--cdp`) and `domengine session log --id` (`--follow` streams until the session leaves the registry; refused inside `serve --stdio`) match the control-engine wire. MCP only forwards those argv lists.

`domengine session create --url` returns only after that document's `readyState` is `complete` (bounded wait). A browser-level CDP URL is attached to the current tab's page target; `DOM.*` is not sent to a browser target. A test that starts a browser on a page URL waits with `BrowserSession.awaitInitialDocument` before it queries. That helper attaches the first page target, calls `Page.navigate` for the requested URL, and waits until `readyState` is `complete`. The debugger socket can open while that document is still loading. Chrome or Edge that the engine or a live test starts uses `ChromeProcess`: `--remote-debugging-port=0`, `--remote-allow-origins=*`, the port from `DevToolsActivePort` (45 seconds, including after the starter process exits, then one relaunch into that same wiped profile), and a 30 second debugger wait. A `DBUS_SESSION_BUS_ADDRESS` that is not `unix:` or `tcp:` is `disabled:` for that browser process only. A port timeout names `launcher-exit` and a collapsed `launch.log` (a repeated line is kept once, with a count). A starter that exits does not abandon a child still using that profile. Live Chrome action smoke (`LiveChromeActionSmokeTest`) runs when `DOMENGINE_CHROME` or a Chrome binary is on `PATH`. It is skipped only when that binary is missing. `ActionSmokeTest` still uses the fake DevTools server. `LiveEdgeSnapshotTest` runs when `microsoft-edge` is on `PATH` or `/opt/microsoft/msedge/msedge` is executable. It starts the `msedge` binary beside a wrapper script. A host with neither skips with `microsoft-edge is not on PATH`.

`query --wait-for attached|visible|hidden|detached` re-reads the live tree. `hover` is an action and `--force-hover` sets the hover pseudo-state. `scroll` with both deltas 0 calls `DOM.scrollIntoViewIfNeeded`. A non-zero delta stays a mouse wheel. A click, double-click, context-click, or type scrolls the node into view and sends the mouse at the viewport center. A click on an `option` selects that option and fires `input` and `change` on its `select`. `drag --source` and `--target` take a CSS string or a JSON object whose `css`, `xpath`, `pipeline`, or `selector` field is the query. Both ends are scrolled into view before the mouse sequence. A named key focuses that node, then uses the DOM name and code (`ENTER` is `event.key` `Enter`, `code` `Enter`). A chord sends modifier bits (Alt 1, Control 2, Meta 4, Shift 8). A printable keyDown sets CDP `text` and does not set Control, Alt, or Meta. `type` without `--append` clears the control (DOM value or contenteditable text, else select-all and delete) and inserts. `--append` inserts. A press of only letters and digits uses `--append`. Element text skips a shadow root whose text repeats the light-DOM text, so a native option label is not doubled. A combined clause is the target alone. A search xpath that also names a context does not run that unscoped pipeline first. `--filter displayed` runs before `nth`, `every`, and `count-only`. `--frame` and `--shadow` set the root (`not_found` when missing). `--all-frames` is the main document, then frames depth-first. `--node-id` is a backend node id; a miss is `stale` unless `--refind`, and the adapter throws `StaleElementReferenceException`. Row states are `hidden`, `disabled`, `checked`, `selected`, `readonly`, `required`, `expanded`, `collapsed`, and `focused`. `query --limit` and `eval --node-ids` are engine flags. A failed capability read is not cached. A `domengine` version uses the Java verb lists. When capabilities cannot be read, the fallback action list for that call only is `click`, `type`, and `scroll`, and two consecutive failures block that field for 5 seconds. Errors are `{"ok":false,"error":"<message>","code":"<code>"}`. An engine flag a verb does not read is exit 2, `unrecognized flag --<name> for <verb>`. `capabilities.verb_flags` lists only flags that verb reads. The adapter sends a relied-on flag only when that list contains it. Live XPath is XPath 1.0 evaluated by the page (`document.evaluate`). An invalid expression is an error, not zero hits.

`domengine conform --root DIR --live` evaluates the vendored control-engine fixtures in headless Chrome, including the assertions offline `conform` skips. `LiveConformanceTest` skips only when that binary is missing. Offline `ConformanceSuiteTest` is unchanged. Those HTML and JSON files are the control-engine wire contract.

An unjoined WebDriver stays on Selenium for nesting and for a composite find. Reading engine query flags does not launch Chrome. `ContextAnchorReproTest` and `NestAnchorReproTest` pass with or without a Chrome binary. `assertEquals(1, deepest.size())` is unchanged. A joined browser still sends `--within` and `--nesting`.

The syntax layer is `dev.domengine.query`. The dictionary layer is `SelectorTranslator` and the execution dictionary. `>>` stays a sequential pipeline. `AND` is intersection, `OR` is union, and `NOT` is the complement of the current scope. Parentheses outrank `NOT`, then `AND`, then `OR`. Mixed `AND` and `OR` at the same level require parentheses. Capitals are canonical. A lowercase `and`, `or`, or `not` is accepted and logs one warning per process and operator (`lowercase operator 'and' read as AND; write capitals`). `domengine query --selector` and `--pipeline` accept the expression. `DOMENGINE_LOG=expr` or `COMPOSITE_LOG=expr` logs evaluation. That log stays off unless the entity is named. It is date-stamped and size-capped. Selenium fallback returns null for `AND`, `OR`, `NOT`, and for a relation form.

Eight relations: `near`, `below`, `above`, `left-of`, `right-of` (`left` and `right` are aliases), `inside`, `after`, and `before`. Stage form is `css=#anchor >> inside=button`. Filter form is `<relation>(<anchor query>)`, including `not-near(text="Cancel")` and `inside@0.5(role=dialog)`. A candidate is above when its bottom is at or above the anchor top, left-of when its right edge is at or left of the anchor left, and right-of when its left edge is at or right of the anchor right. `inside` keeps a displayed candidate whose box lies fully inside an anchor. Hits that use geometry need a box and must be displayed, then sort by center distance. `after` is document order (`following::`) and `before` is `preceding::`. Neither needs a box. `exposure=human` uses the visual box when one was stored. A negated relation drops candidates that satisfy the positive relation. A weight is stored and ignored by the filter. `in`, `from`, and `for` stay `<context> >> <target>`. The other context words become the filter form. `DOMENGINE_LOG=relation` logs anchors, candidates, kept, and the space, and stays off by default. `DOMENGINE_LOG=visual` still logs the geometry space and rank count.

The dictionary layer emits a combined clause first. Target clauses are one AND group, for example `(role=button AND text="Save")`. The XPath runs only when that pipeline matches nothing. A pure XPath definition still resolves and logs one deprecation warning. Pure CSS passes through.

`look` matches computed style. `look=color=red` and `look=opacity>0.5,background-color=#fff` are stages. A comma is AND and a pipe is OR inside the stage. `contrast` is the contrast of `color` against the painted `background-color`. It is not a screenshot match, and there is no engine named `attribute`. `DOMENGINE_LOG=look` logs the matched properties and stays off by default. A step can add `with look <prop> <predicate>` after the existing attribute clause, for example `with look color equaling red` or `with look opacity greater than 0.5`. That clause is not an HTML attribute. `SelectorTranslator` maps it to a `look=` stage on the same engine query string. `with id attribute` and `with id noattribute` are unchanged. Cucumber step annotations stay single string literals. `pkb_composite_log=query` logs `forward selector=` and stays off by default. A parsed step with that clause applies the look stage on the production find (`ElementWrapper.getWrappedElements` and `ContextWrapper`). Attribute and noattribute grammar still apply. Unset, `auto`, and `off` fail the find instead of ignoring look. `pkb_composite=on` evaluates it. Selenium is not asked to evaluate a look stage.

`QueryHooks` is the only query file that imports pre-map or affinity. `Query` calls pre-map, rank, and one-match once each. `Expressions` calls `candidates` once per stage. Pass-through leaves the hit order unchanged. `--rank affinity` scores, and a negated satisfied term lowers that score. `--strict` throws `not found` or a message that starts `ambiguous: N candidates`, and the CLI JSON carries `candidates`. Browsers and desktops run the same query string. Desktop rejects `pierce`, `frame`, and `shadow`.

Live native desktop access runs on the machine where the engine runs (Windows UI Automation, macOS Accessibility, Linux AT-SPI). The fixture backend is kept for tests and CI. `desktop attach --fixture` replays a recorded tree. `desktop attach --live [--name]` reads the platform. Live click and type call that platform or fail. They do not report success for a recorded tree. Linux live desktop reaches the accessibility bus with `dbus-send --session` and `DBUS_SESSION_BUS_ADDRESS` set to the address from `org.a11y.Bus.GetAddress`. It does not pass `--address=`. `AtSpi.busAddress()` still uses `--session` on the real session bus. Role is `GetRoleName`, then `GetRole`. `Properties.Get` stays for Name only. The numeric fallback matches at-spi2-core 2.56 `AtspiRole`: `FOCUS_TRAVERSABLE` is 21, so frame is 23 and button is 43, and `EXTENDED` follows window, so application is 75. A frame named for `desktop attach --name` maps to `window`. `role=button` matches GetRoleName `button`. A missing bus still fails with `AT-SPI unavailable`. The live-success test skips when the bus is absent.

The jar command is `domengine`, which is not the step CLI `engine`. `PickleballWorkbenchLauncher` dispatches it. MCP `domengine`, `DomEngineCliForwarder`, and Gradle `domengine` only forward. Workbench `installDist`, `:run`, and tests carry a non-transitive `dom-engine` jar. That jar is not a compile dependency and it is not published. Do not publish a separate `dom-engine` artifact as a substitute. The classes and sources are inside the pickleball jar. A verification test fails if they are missing.

## Browser protocols and the agent boundary

Scripted element operations stay on the library adapter unless `pkb_composite=on`. `DriverConstruction.ready` selects `ProtocolSelector` `LIBRARY` for the driver it just built and does not open a published `se:cdp` or BiDi websocket. Direct CDP and BiDi are `AgentProtocolController`. Page surfaces prefer a direct CDP or BiDi socket. On Chromium the selected tab's target id comes from Selenium `Target.getTargetInfo`. Overlay, page, isolated worlds, preload, and bindings run on that tab after `Target.attachToTarget` with flatten. Selenium `executeScript` is the logged last resort. Cleanup enables the DOM domain before `Overlay.enable` and `Overlay.hideHighlight`. A highlight hide or later cleanup on a tab, frame, or driver that is already closed is skipped. A live tab whose hide or switch throws still fails the command.

`pkb_composite_fallback_log` (`warn` by default, `info`, or `off`) logs one line per run and reason when `on` cannot start the engine (missing engine, client failed, join failed). The line names the engine path or `none` and the fix. It is also a `composite.fallback` diagnostic event. Unset, `auto`, and `off` do not look for an engine and do not log a fallback. `pkb_composite=on` still prints `error:` and `fix:`. An explicit executable that is not the Java launcher logs one `INFO composite.engine override` line. The consumer `@dom-composite` scenarios set `pkb_composite=on` so they still exercise the engine.

MCP is an agent entry, not a second implementation. `domengine mcp` and Workbench MCP tools forward argv or agent ops. Popup commands `agent_popup_open`, `agent_popup_status`, and `agent_popup_close` are registered that way. A driver built outside a scenario, including agent `launch --start`, reads the debug-browser flag from the open scenario when there is one, otherwise from `pkb_debug_browser`, otherwise it leaves the port alone. It does not throw.

Chrome, Chromium, and Edge use CDP. Firefox uses BiDi, or a logged Selenium fallback for the snapshot producer.

## Snapshots and pre-mapping

A composite snapshot is the page-level artifact: screenshot, source, structure, paths, a viewport map, and a pre-map. The pre-map is core. `CompositeSnapshotBuilder.build` always runs `PreMapBuilder`. There is no flag to skip it. The per-element capture is an element snapshot.

`PreMapLookup.installed()` is the live session lookup, not a pass-through. A session query reads that pre-map. A miss rebuilds from the tree the query already holds and does not call `DOM.getDocument` again. `--explain` shows `premap: hit` or `premap: rebuilt <ms>`. `Query` asks `candidates` once per searchable stage. `--rank affinity` ranks relatives. `--strict` resolves one match and lists candidates when the match is ambiguous.

Chrome, Chromium, and Edge use the CDP snapshot producer. Firefox uses BiDi, or a logged Selenium fallback. Windows UI Automation, macOS Accessibility, and Linux AT-SPI use the desktop producer. Live hit-testing, focus order, top layer, screenshots, Playwright-only queries, and scripted shadow roots that the producer cannot fill are skipped, not failed. The canonical field list is `docs/snapshot.md`.

## Agent surfaces

In-page surfaces live in `tools.dscode.common.agentcontrol`. The popup lives in `tools.dscode.common.agentsurface` and does not depend on the Workbench. One host process per project and run id serves a Chromium app window on loopback HTTP and server-sent events. The token is 128-bit hex and compared in constant time. The Host header must be `127.0.0.1:<port>`. `popup-open`, `popup-status`, and `popup-close` are agent ops, CLI verbs, and MCP tools. `AgentSurfaceRouter.route` is the one guarded call at the top of `AgentControl.execute`. When it returns empty, that call is unchanged.

`pkb_agent_popup=auto` opens the popup for a desktop session and for an explicit `--target=popup`. `always` opens it for chat and for HTML or overlay writes that have no `--handle`. `off` returns an empty route for existing ops. A popup verb while off returns `opened=false` and `fallback=off`. No Chromium logs one `no-chromium` warning, writes `host.json` with `state=closed`, and still stores the ledger row. A surface op then returns `ok=true`, `target=none`, and `fallback=no-chromium`. Scenario cleanup clears the Instructions, Diagram, and overlay panes and keeps chat. A JVM that opened hosts writes `close` on shutdown.

`highlight --target=native`, and a desktop highlight that already has bounds, draws four 3 px `#ff2d55` strips from `--x --y --width --height` or from the bounds on `--query` JSON. HiDPI uses `GraphicsConfiguration.getDefaultTransform()`. A headless runtime returns `drawn=false` `reason=headless`. The caption goes to the popup Instructions pane. The Workbench caption window is used only when the result says `popup=false`. Channel, discovery, and the routing table are in `docs/direct-control-handoff.md`.

## Workbench

The Workbench GUI can add, delete, and modify element categories, general configuration and data files, scenarios and scenario steps, and Java step definitions, plus JS and TS source, through one CLI-first edit model. `dalton category`, `dalton file`, `dalton glue`, and `dalton feature scenario-add`, `scenario-modify`, `scenario-delete`, `step-add`, `step-modify`, and `step-delete` are the write path. The Editor, MCP `dalton`, and Gradle `daltonEdit` only forward. A scenario or step edit splices that region, keeps comments outside it, normalizes the indent of the edited region, and writes valid Gherkin through `feature put` when Dalton is on, or the file when Dalton is off. `glue/js/*.js` and `glue/js/*.ts` are source only. JS and TS step execution is not implemented and does not replace Java glue. Java step annotations stay a single string literal. A successful Java compile updates the glue fingerprint and stops the warm worker. A compile failure leaves that worker. Secret fields are references (`<name>` or `~MASK`). Deletes need `--confirm`. A conflict is refused unless `--force`. `pkb_dalton_log=edit` logs each edit.

`EditPanel.getText()` is Swing and is classified with `LogPanel`. Gradle `daltonEdit` forwards to `DaltonCli` with the other dalton tasks. Workbench launch console capture keeps a 256KB head and a 512KB tail so a long workbook dump cannot exhaust the test heap. `CaptureBuffer.toByteArray`, `size`, and `writeTo` return the capped head, the truncation marker, and the tail. `LiveChromeCdpTest` uses the dom-engine Chrome lookup (`DOMENGINE_CHROME`, then `google-chrome`, `google-chrome-stable`, `chromium`, `chromium-browser`, `chrome` on `PATH`).

The control-bridge pause is held until BEGIN has set `pauseRequested`, and VERIFY requires that pause to be AFTER_STEP of BEGIN, so the adapter and the native engine record the same step order under suite load. Step order is not sorted. Event sequence numbers are assigned under the same lock as the append. Build provenance streams the git diff into a hash instead of buffering it in the Gradle daemon. Root `reports/` workbook HTML and `report.xlsx` are generated and gitignored. The breaking-change list stays empty.
