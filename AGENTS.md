# Pickleball Agent Contract
## Purpose

Pickleball is a Java 21 testing framework distributed for use by consumer projects as a Maven dependency.

The repository also contains `maven-consumer-project`, which is both:

1. An example of how a consumer configures and uses Pickleball.
2. An executable compatibility and integration test for the framework.

Consumer scenarios start a local test-site server and exercise browser behavior through Selenium as well as REST/SOAP-style service calls, mappings, templates, dynamic Cucumber steps, nested flows, and component scenarios.

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

- `src/main/java` — framework implementation and Cucumber integrations
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

## Dalton (3.0.0)

Dalton is opt-in. With no `pkb_dalton` setting and no Dalton config file, consumer projects stay on the file-only path. The locked design is `docs/maintainer/3.0.0-dalton-design.md`. The Round 1 implementation plan (complete) is `docs/roadmaps/3.0.0-dalton-roadmap.md`. The Round 2 roadmap is `docs/roadmaps/3.0.0-dalton-round2-roadmap.md`. Round 3 (engine decoupling from Cucumber, in progress) is designed in `docs/maintainer/3.0.0-engine-design.md` with the roadmap `docs/roadmaps/3.0.0-decouple-round3-roadmap.md`.

- Git files are the source of truth. The working copy is `.pickleball/dalton/dalton.db` (gitignored, including the explicit `.pickleball/dalton/` entry). A fresh clone has no database and must still run.
- CLI: `dalton status`, `dalton hydrate` (`--path`, `--force`, `--dry-run`), `dalton extract` (`--path`, `--force`; `hydrate-out` is the same command), `dalton history` (`list`, `show`, `record`, `retain`), `dalton entity` (`list`, `get`, `put`, `remove`, `export`), and `dalton feature put <relative-path>` (`--text` or `--file`). Extract writes stored bytes and does not reformat. It leaves an unedited file, and it refuses when the file and the database both changed unless `--force` is set. `dalton entity export` is that write limited to `dalton-entities/`. `dalton feature put` sets `db_edited`, leaves `last_synced_hash`, re-projects scenarios, steps, tags, docstrings, and data tables, and rolls back when the Gherkin is not valid. `dalton scenario put` and `dalton step put` are rejected. Hydrate reports `untracked DB edit on <path>` when `content_hash` moved and `db_edited` is still 0, and it does not overwrite that row unless `--force` is set. A flagged edit whose file is still the last sync is kept. History sits beside diagnostic reports and does not apply `pkb_reportretention`. Gradle `daltonStatus`, `daltonHydrate`, `daltonExtract`, `daltonFeature`, and `daltonHistory`, the Workbench action `DaltonCliForwarder`, and MCP `dalton` only forward to that CLI. Auto-hydrate on a run happens only when `pkb_dalton=true` or `dalton.properties` sets `enabled=true`. With that flag on, the JUnit feature parser and the CLI feature supplier read hydrated `.feature` bytes, and `<entity:Type#id.field>` resolves inside a running step. A secret field stays a reference. With the flag off, loading does not open the database and entity references are not special.
- CLI reads: `dalton files list|show`, `dalton features list|show`, `dalton scenarios list` (`--feature`), `dalton steps list` (`--feature`, `--scenario`), and `dalton conflicts`. `--json` is one stable object. These commands do not create a database. Gradle `daltonQuery` only forwards to that CLI. The Workbench Dalton tab uses `DaltonCliForwarder` for those reads and for feature and entity edits. It does not open SQLite.
- Discovery: `include` (default is the fixed directories), `exclude` (defaults always include `.git`, `target`, `build`, `.pickleball`, and common binary extensions), `max-file-size` (default 1 MiB), and `binary-policy=skip`. Set them in `dalton.properties` or with `pkb_dalton_include`, `pkb_dalton_exclude`, `pkb_dalton_max_file_size`, and `pkb_dalton_binary_policy`. `dalton status` prints the skipped count and sample reasons and does not create a database. `--path` obeys those limits. `--path --force` is the only bypass. `pkb_dalton_log=discover` logs each reason.
- `sqlite-jdbc` is not a published dependency. Tests and the Gradle forwarder put it on their own classpath. The consumer shadow JAR must not ship it. When Dalton is enabled and the driver is missing, auto-hydrate and the CLI commands that open the database fail with `Dalton is enabled but org.xerial:sqlite-jdbc is not on the classpath. Add it as testRuntimeOnly / test scope or use the Gradle dalton* tasks.` They do not fall back to the files.
- `dalton run` is the warm path. It sets `pkb_dalton=true` and launches `pickleball-test-engine` through `WorkbenchDirectRun`, so the result matches a normal engine run. It prints one line per scenario (`scenario PASSED|FAILED <name>`) and one line per pickle step (`step PASSED|FAILED <text>`). Glue is fingerprinted by path, size, and modification time. A change runs one wrapper compile and restarts the worker. `--dry-run` prints the plan and writes nothing. The classpath is `pkb_dalton_run_classpath` or the Workbench `classpath.cache`. Gradle `daltonRun`, the Workbench Run button, and MCP `dalton` only forward to the CLI. `pkb_dalton_log=run` logs that worker. Full Gradle or Maven stays the CI build.
- Do not store cleartext secrets in Dalton. Entities hold secret references only, using the existing secrets and config approach. `secret-fields=` in `dalton.properties` (or `pkb_dalton_secret_fields`) adds names to that list. A high-entropy value warns and is still stored. The database is not encrypted.

## Public contracts

Treat these as consumer-visible contracts unless source evidence clearly shows otherwise:

- Public Java APIs, including the optional dynamic-control API and semantic hook contract. `tools.dscode.Values` is the static value API (`getValue`, `saveValue`, the map-specific pairs, Jackson `Class` overloads, `softFail`, `throttle`, `registerPrefix`, `cacheGet` / `cachePut`, and `secret`). `PrefixValue` is the only prefix return type. `~MASK;` and `~UNMASK;` keep known secrets out of logs.
- Element text `matching` is a full match and honors quote types the way phrase `matches` does. This changed in 2.1.16.
- Element find, click, submit, clear, type, hover, drag, scroll, keys, wait, page source, and inspect go through `ElementOperations` and `DomCompositeAdapter` when a dom-composite 2.4.0 binary is present (`pkb_composite=auto`). `ElementWrapper` click, submit, sendKeys, and clear are one `ElementOperations` call each. Submit, double-click, context-click, element drag, and named keys use the real verb when `capabilities.actions` lists `submit`, `dblclick`, `contextclick`, `drag`, or `key`. A binary that lists only click, type, and scroll keeps the old mapping: submit is click, double-click is two clicks, context-click is one click, and drag is click, scroll, click. An offset drag stays on that mapping because `drag` takes two locators and no dx/dy. Gherkin phrases stay the same. XPath and CSS from `ExecutionDictionary` pass through. Live `inspect-page` and `find-element` use `PageElementQuery`, which calls `ElementOperations.describe` once and counts from that batch. `workbench_element_inspect` uses `ElementControl`, which calls the same `describe`. `pkb_composite=off`, or a missing binary, uses the Selenium calls inside `ElementOperations` for that run (one script when the driver can run it). WebDriver stays for browser lifecycle (navigation, window handles, CDP). There is no `BrowserSteps.getJavascriptExecutor()`; scripts go through `ElementOperations.script`. Desktop `click` and `type` fail with `error:` / `fix:` until `capabilities.desktop` lists those verbs. A press of letters and digits uses composite type (a lone A-Z is lowercased). Chords the `key` verb can represent use `key` when it is listed. `A+B` and `CONTROL[A+B]` focus with a composite click, then WebDriver Actions, because a chord has one non-modifier. A state check reads the snapshot and does not find again. A composite snapshot is one `eval` when `capabilities.actions` lists `eval`, and the WebDriver script when it does not. An all-contexts scan is one query when the binary accepts `--pierce`, and the Java walk when it does not. `the 3rd` and `every 2nd` use one `PositionSelection` on a normal find and on that scan. The scan wraps only the hits position keeps. A composite snapshot reads tag, text, attributes, and bounds from the hit and evaluates `selectedValue`, `siblingIndex`, and `stableSelector` on demand. `pkb_composite_log=position` writes `TRACE position apply`. A visible or present wait on a composite hit is one query (`poll_ms` 150, `stability_ms` 0) when the binary accepts `--poll-ms`, and the 150 ms loop when it does not. A context find uses query --within when capabilities.query_flags lists within, including a context with no id and a parenthesized XPath. A binary that does not list it keeps the id/name anchor, and a missing anchor is still an empty find. Nesting is query --nesting on that same find when query_flags lists nesting, after the displayed filter. A binary that does not list it keeps one extra anchor query. A primary binary that has not joined a browser finds on that WebDriver with Selenium, the same hits as `pkb_composite=off`. A process-only session is not a join. `pkb_composite_log=element` writes `INFO element find selenium unattached`. `--within` and `--nesting` are still sent only when `query_flags` lists them. Several composite snapshots are one eval when capabilities.eval_nodes is set, and one eval each when it is not. pkb_composite_log=adapter writes INFO adapter within <id>, within fallback, nesting deepest|outermost, nesting fallback, and eval nodes=<n>. `the 3rd`, `last`, and `every 2` are `--nth` and `--every` on that same query when `query_flags` lists `nth` and `every` and the phrase has no `matching` regex. A regex, a missing flag, or an all-contexts scan keeps `PositionSelection` in Java. `INFO adapter nth <n>`, `nth <n> every <step>`, `count <n>`, or `position java`. A count is one `query --count-only` when `query_flags` lists `count-only`. A query adds `geometry: true` when `query_flags` lists `geometry`. The hit then carries `ElementSnapshot.Geometry`: `document_css_px`, `visual_viewport_relative`, `layout_viewport_relative`, `nearest_scrollport_relative`, and `frame_document_relative`. A scrollport hit also carries `scrollport.client` and, only when a content size was stored, `scrollport.content`. Content is not invented. A control also carries `expanded`, and `revealed` only when it is open and a region was stored. A collapsed control does not grow a predicted box. `corners` reports hit-grid samples; a null hit is not a hit. `scrollChain` is the ancestor scrollports from that same payload. `DomCompositeAdapter.hitAt` is one `query --point` when `query_flags` lists `point`, and null otherwise. `Bounds` stays the integer AABB. Selenium, and a binary that does not list `geometry`, leave `Geometry` null and do not invent quads. `pkb_composite_log=geometry` writes `TRACE geometry request` and `INFO geometry real` or `INFO geometry fallback`, and `INFO geometry scrollport client` or `scrollport client+content` when that box is on the hit. An expandable hit writes `INFO geometry expanded revealed` or `collapsed`, and `INFO geometry corners <n>hit/<n>miss` when a sample is known. `INFO geometry scroll-chain <n>` or `scroll-chain fallback`. `INFO geometry point` or `point fallback`. `pkb_composite_log=adapter` writes `INFO adapter geometry real` or `geometry fallback`. A refresh, when `eval` is listed and the node is still connected, does not re-find by `stableSelector`. Do not add a second locator. An engine query string on Workbench, MCP, or the control bridge is that same `ElementOperations.describe(driver, EngineQuery)` call. A controller query or action with no exposure is `human`. `lower` is per query and labels every node. `left-of`, `right-of`, `above`, `near`, and `below` on that query are visual distance. A binary without geometry invents no box. `--explain` returns rank and score. An image match promotes to a node or stays a labeled visual-only hit. A limit returns handles for one later act, not a batch click. Category+text stays the dictionary XPath and does not send exposure. Gherkin stays `SelectorTranslator`, and `after` / `before` stay document order.
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

## Native engine (3.0.0 Round 3)

Phase 1 adds Pickleball-owned model types in `tools.dscode.engine.model` (`EngineStep`, `EngineScenario`, `EngineFeature`, `Table`, `DocText`, `Status`, `RunResult`) and `CucumberModelConverter` at the adapter edge. Existing Cucumber execution is unchanged. The locked design remains `docs/maintainer/3.0.0-engine-design.md`.

Phase 2 adds `StepRegistry`. It reads the same `@Given` / `@When` / `@Then` / `@And` / `@But` and other `io.cucumber.java` step annotations, leaves those values as single string literals, and matches with Cucumber's `ExpressionFactory` (regex and Cucumber expressions, parameter types, DataTable / DocString parameters, step overrides, and ambiguity). Dispatch kinds for dynamic, block-conditional, RUN, and service-call steps are stubs and do not execute the step. `pkb_engine_log=match` or `registry` writes `.pickleball/engine/engine.log`.

Phase 3 adds `ExecutionContext` (scenario, step stack, map scopes, phase, failures, timing, and browser/service resources). `GlobalState.getCurrentScenarioState` still returns the Cucumber thread local when one is set. It uses the context's legacy scenario only when that thread local is empty. `ParsingMap.getRunningParsingMap` uses the context map only in that same case. A Cucumber run does not change.

Phase 4 uses `Table` and `DocText` in mappings, data elements, data operations, and assertions (`NeutralRows`, `TabularDataAdapter`, `NeutralAssertions`). Conversion to Cucumber `DataTable` and `DocString` stays in `CucumberModelConverter`.

Phase 5 parses Gherkin with the gherkin library, then converts. `EncodingRewrite` repeats the encoding line swap. `GherkinEngineParser.expand` keeps placeholders, marks background steps, and applies Scenario Tags. Sources are files, Dalton rows, in-memory text, and a step list with no file.

Phase 6 resolves scenarios from any `ScenarioSource` by tags, name, feature name, example rows, step text, order, and limit. The rules match `CucumberScanUtil` and `ExampleRowFilter`. Memory text and Dalton rows use that same lookup.

Phase 7 runs scenarios and single steps through `PickleballEngine.run` and `runStep`. Sources are in-memory Gherkin, a runtime step list with nothing on disk, a file, or a Dalton row. `engine help`, `engine run`, and `engine step` are the CLI. The launcher, `dalton run --engine`, Gradle `engineRun`, Workbench `EngineCliForwarder`, and MCP `engine` only forward to that CLI and do not run steps themselves. `pkb_engine_log=run` logs the run. A Cucumber suite is unchanged.

Phase 8 runs `DynamicControl.executeStep` when no Cucumber scenario is on the thread. The live and paused path is unchanged. The control bridge uses that native call only when it has no scenario lane. `pkb_engine_glue` names glue classes for that call.

Phase 9 adds `EngineConfig`. `pkb_engine`, then `PKB_ENGINE`, then `engine.properties` `mode=` choose `native` or `cucumber`. The default is `cucumber`. Existing JUnit suites stay on the Cucumber adapter unless that toggle is `native`. `CucumberAdapterRun` and `DualRun` compare one feature on both paths.

Phase 10 inserts steps written during a step (`ExecutionContext.writeSteps`) into the running scenario. They run next, with nothing on disk. The result records `source=runtime`, the same way a file step records its text. `pkb_engine_log=run` logs those lines under the `runtime` entity.

Phase 11 adds `ParityHarness` and `DualRun`. The same feature is run on the Cucumber adapter and the native engine. Status and step text are compared. A returned value is compared when both sides recorded one. Plain glue matches. `ASSERT: "a" equals "a"` matches. Browser features and dynamic phrases in the repo suites and `maven-consumer-project` are not the same yet: those steps still run inside Cucumber `StepExtension`. The default stays `cucumber`.

Phase 12 does not switch the default to native. The dual-run harness is green for plain glue and for `ASSERT: "a" equals "a"`. It is not green for browser features and dynamic phrases, which still run inside Cucumber `StepExtension`. `EngineConfig` stays `cucumber` unless `pkb_engine=native`. The Cucumber adapter stays available. Cucumber-core aspects are not retired. Logging for the native CLI is complete: `engine help`, `engine run`, and `engine step` are the commands; the launcher, `dalton run --engine`, Gradle `engineRun`, Workbench `EngineCliForwarder`, and MCP `engine` only forward; `EngineLog` stays off unless `pkb_engine_log` or `PKB_ENGINE_LOG` names a capability, `pkb_engine_log_entity` filters the entity, each line starts with an `Instant` date stamp, and `pkb_engine_log_max_bytes` caps `.pickleball/engine/engine.log` (default 1048576 bytes, minimum 64).

G9 closed the dual-run gap. Repo features and every `maven-consumer-project` feature (including dynamic phrases) match on the adapter and the native engine. Browser features that cannot launch a browser fail the same way on both sides. The default is now `native`. `pkb_engine=cucumber` (else `PKB_ENGINE`, else `engine.properties` `mode=cucumber`) keeps the Cucumber adapter, which stays supported. Every Cucumber-core aspect is still required by that adapter, so none were retired.

## Java DOM Engine (3.0.0)

The locked design for replacing the Rust DOM composite engine with a general-purpose Java engine is `docs/maintainer/3.0.0-java-dom-design.md`. The mandatory multi-phase roadmap is `docs/roadmaps/3.0.0-java-dom-roadmap.md`. Implementation stays on branch 3.0.0. The engine interface remains general-purpose. Open PRs #2 and #3 on ds-code-t/dom-composite must not be merged.

`domengine log list|tail|show` reads the capped engine logs. Pickleball `log` also lists `.pickleball/history.log`. The existing 10 MB front trim on that file is unchanged. The Workbench Logs tab only forwards to that CLI.

