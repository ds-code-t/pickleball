# Pickleball Agent Contract
## Purpose

Pickleball is a Java 21 testing framework distributed for use by consumer projects as a Maven dependency.

The repository also contains `maven-consumer-project`, which is both:

1. An example of how a consumer configures and uses Pickleball.
2. An executable compatibility and integration test for the framework.

Consumer scenarios start a local test-site server and exercise browser behavior through `SeleniumAdapter` unless `pkb_composite=on` starts the in-repo Java DOM engine, with WebDriver for browser lifecycle, as well as REST/SOAP-style service calls, mappings, templates, dynamic steps, nested flows, and component scenarios. The default execution engine is native. Cucumber is a compatibility adapter (`pkb_engine=cucumber`).

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

## Store

The working copy is an embedded SQLite database and is on by default. The CLI first argument is `store` (`pickleball store`). Opt-out precedence is `pkb_store=false`, then `PKB_STORE`, then `store.properties` (`enabled=false` or `pkb_store=false`). `StoreConfig.source` reports `property`, `env`, `file`, or `default`. Git files stay the source of truth. In history files, "Dalton" is the former name of the store core. The store design is `docs/maintainer/3.0.0-embedded-store-design.md`. A sandbox file is `.pickleball/store/sandboxes/<name>/source.db`. `pickleball store sandbox create` defaults to `--mode=cow`: the child records `schema_meta.sandbox_mode=cow` and `_pkb_branch` parent, and edits are deltas in the child (a delete is a tombstone). `--mode=copy` is an independent database with no parent. `store sandbox promote` requires `--logs=clear|archive`. Commands: `create|list|use|diff|extract|drop|prune|merge|promote`. `sandbox merge` compares `_pkb_base` with `main` and the sandbox by hash (`--policy=fail` writes nothing on conflict; `branch-wins` and `main-wins` resolve). `store changes` lists `_pkb_changes`. `pkb_store_changes_days` defaults to 14. Binding is `--sandbox`, then `pkb_store_sandbox`, then `PKB_STORE_SANDBOX`, else `main`. One sandbox per JVM. Edits land on that copy. Run-state stays in `runstate.db` and the row records the sandbox name. The warm worker for `main` is `.pickleball/store/sandboxes/main/worker/`. A named sandbox uses `.pickleball/store/sandboxes/<name>/worker/`. `store glue compile` compiles DB-edited `.java` into `sandboxes/<name>/classes/` and that directory is first on the worker classpath. The Store tab sandbox picker forwards `store sandbox` (including merge) and `store changes`. The store now opens embedded SQLite and starts no server and downloads nothing. `store status` prints `store=embedded` and `hydrate generation <n> <git sha>` with ` dirty` when the working tree was dirty. A hydrate increments that generation only when it changes rows. `install` and `server` are unknown commands. The sandbox CLI and the `store` name are still present. Design `docs/maintainer/3.0.0-embedded-store-design.md`, roadmap `docs/roadmaps/3.0.0-embedded-store-roadmap.md`, Build prompt `prompts/build-3.0.0-embedded-store.md`.

- Git files are the source of truth. The working copy is `.pickleball/store/main.db` (embedded SQLite, branch label `main`, gitignored) plus project run history in `.pickleball/store/runstate.db`. A fresh clone has no store. The first use that needs it creates `store_schema=1` and hydrates. Opening the store starts no process and downloads nothing. `pkb_store=false` stays file-only: status and feature loading do not create a store. An explicit `store hydrate` still opens the store.
- CLI: `store status`, `store hydrate` (`--path`, `--force`, `--dry-run`), `store extract` (`--path`, `--force`; `hydrate-out` is the same command), `store history` (`list`, `show`, `record`, `retain`), `store entity` (`list`, `get`, `put`, `remove`, `export`), and `store feature put <relative-path>` (`--text` or `--file`). Extract writes stored bytes and does not reformat. It leaves an unedited file, and it refuses when the file and the database both changed unless `--force` is set. `store entity export` is that write limited to `store-entities/`. `store feature put` sets `db_edited`, leaves `last_synced_hash`, re-projects scenarios, steps, tags, docstrings, and data tables, and rolls back when the Gherkin is not valid. `store scenario put` and `store step put` are rejected. Hydrate reports `untracked DB edit on <path>` when `content_hash` moved and `db_edited` is still 0, and it does not overwrite that row unless `--force` is set. A flagged edit whose file is still the last sync is kept. History sits beside diagnostic reports and does not apply `pkb_reportretention`. Gradle `storeStatus`, `storeHydrate`, `storeExtract`, `storeFeature`, and `storeHistory`, the Workbench action `StoreCliForwarder`, and MCP `store` only forward to that CLI. The forwarder is `java -cp <resolved consumer classpath> tools.dscode.launcher.PickleballWorkbenchLauncher store` (`classpath.txt`, else `classpath.cache`). The published jar stays thin. `DomEngineCliForwarder` uses that same classpath for `domengine`. A forwarder error is shown in the Tracking and Store tables. Auto-hydrate on a run happens unless Store is explicitly off. `store status` prints `enabled=<bool> source=<source>`. With Store on, the JUnit feature parser and the CLI feature supplier read stored feature rows. A missing row is `error:` and `fix: store hydrate`, and they do not read the file. `<entity:Type#id.field>` resolves inside a running step. A secret field stays a reference. With the flag off, loading reads the file, does not open the database, and entity references are not special.
- CLI reads: `store files list|show`, `store features list|show`, `store scenarios list` (`--feature`), `store steps list` (`--feature`, `--scenario`), and `store conflicts` (`show <path>` and `choose <path> --keep=db|file`). `--json` is one stable object. These commands do not create a database. Gradle `storeQuery` only forwards to that CLI. The Workbench Store tab uses `StoreCliForwarder` for those reads and for feature and entity edits. It does not open SQLite.
- The source database also stores one commit pin, an append-only delta log, and a flattened head (`pins`, `deltas`, `heads`, `head_rows`). `store_schema` stays 1. `schema_meta` records `universal_entity=1` and `delta_window=100`. `store pin show` prints the git commit, the time, and the dirty flag. An empty git directory stores an empty sha and dirty 0, and `store status` still warns once that git provenance is empty. A dirty tree sets `pins.dirty=1`. `store delta list` prints the log. `store delta undo` appends an inverse delta and leaves the undone row in place. `store delta redo` appends a copy of the delta that undo inverted. `schema_meta` key `delta_cursor` is the seq the head reflects. `store head show` prints the head and rebuilds it when `heads.stale` is 1. `store head rebuild` replays the remaining deltas. A cheap append flattens immediately. `expensive=1` sets `heads.stale=1` until `HeadMaterializer.quiet`, which `store status` and the end of hydrate call. After a flatten, more than 100 deltas are folded into the head and those delta rows are deleted. Unchanged source rows stay in their own tables. A run does not read the head yet. `pkb_store_log=pin,delta` logs pin, head, append, undo, redo, and fold lines. Workbench, Gradle, and MCP only forward these commands.
- A full hydrate whose stored `jar_hash` differs from `StoreHashes.jarHash` deletes the source rows (`files`, `feature_docs`, `scenarios`, `example_blocks`, `steps`, `entities`, `step_definitions`, `fixtures`, `head_rows`, `deltas`, `heads`, `folders`, `scenario_tags`, `run_variables`) and hydrates them from files. It does not open `runstate.db`. A matching hash skips a file whose `content_hash` is unchanged. `store status` prints `reused=<n> rehydrated=<n> jar=<12 hex chars>`. `schema_meta` records `hydration_spine=1` and `jar_hash`. `files`, `scenarios`, `steps`, and `step_definitions` have `enabled` (default 1). A file outside the feature and glue roots is stored with `enabled=0` and is not deleted. `files.norm_path` uses backslashes and a lowercase drive letter on Windows. `folder_key` is the parent. `scenario_tags` stores each scenario tag and each Examples tag on that scenario. `scenarios.tags` stays. `store query --tag` matches both. `store enable <feature|scenario|step|definition|folder> <id> --on|--off` flips that row. Off on a feature, scenario, or folder writes `@pkb-ignore` through the feature edit. On removes only that tag. A both-sides edit is refused. Bad Gherkin rolls back. `store tag add|remove` edits a scenario tag. Hydrate of main copies `pkb_*` run variables from the property and profile files into `run_variables`. `store var list`, `store var set`, and `store var enable` read and write those rows. `store var set` on main writes the properties file. A sandbox `store var set` updates that database only. A cleartext secret is rejected with `error:` and `fix:`. `pkb_store_log=enable` logs the toggle and does not log the value. Java source the editor can change stays a stored row. Workbench, Gradle, and MCP only forward these commands.
- `store conflicts` lists an untracked database edit and a row edited on both sides. `store conflicts show <path>` prints the file text and the database text. A secret field is printed as its stored reference. `store conflicts choose <path> --keep=db` writes the database bytes to the file and sets `last_synced_hash`. `--keep=file` replaces the database row from the file and clears `db_edited`. The discarded text is written to `.pickleball/store/discarded/<utc-timestamp>-<path>`. On the next store command, a discarded file older than 24 hours is deleted, and so is one whose sibling marker names a different JVM start id. The start id is `.pickleball/store/discarded/session`. There is no merge. `store sandbox merge` is unchanged. `pkb_store_log=conflict` logs the path, the choice, and the discarded file name. Workbench, Gradle, and MCP only forward these commands.
- Discovery: `include` (default is the fixed directories), `exclude` (defaults always include `.git`, `target`, `build`, `.pickleball`, and common binary extensions), `max-file-size` (default 1 MiB), `binary-policy` (`include` by default, or `skip`), and `large-file-bytes` (default 262144; `0` means every binary). Set them in `store.properties` or with `pkb_store_include`, `pkb_store_exclude`, `pkb_store_max_file_size`, `pkb_store_binary_policy`, and `pkb_store_large_file_bytes`. `hash-only` is not a policy. `include` stores a large binary whose bytes equal its Git blob as a reference (empty raw content, path location, sha256, git blob, git commit, byte size). Any other tracked binary is stored in the database. `max-file-size` does not apply to a reference. Reading a reference checks the file hash, then `git cat-file blob`, and fails with `error:` and `fix:` when both are missing. A write that changes one copies the bytes into that branch. Extract leaves an unchanged reference and reports `binary_ref_changed`. `store status` prints `refs=<n> owned-large=<n>`, the skipped count, sample reasons, and Git provenance (`git.commit`, `git.branch`, `git.remote` with userinfo stripped, `git.githubRepo`, `git.dirty`, `git.changed`) and does not create a database. `--path` obeys those limits. `--path --force` is the only bypass. `pkb_store_log=discover,git,refs` logs each reason. Hydrate records `git_commit`, `git_branch`, `git_remote`, `github_repo`, and `git_dirty` on `provenance`, and `git_blob` on each file row. No git binary, or not a repository, leaves those fields empty and warns once.
- The store driver is `org.xerial:sqlite-jdbc:3.47.2.0`, a published runtime dependency (POM scope `runtime`), not shaded. When the store is on and that driver is missing, auto-hydrate and the CLI commands that open the store fail and do not fall back to the files. The message says the store is on by default and names `pkb_store=false` for the file-only path. A missing native library can be pointed at an executable directory with `-Dorg.sqlite.tmpdir`. Nothing is downloaded. `sqlite-jdbc` is also the driver for `pickleball store migrate` (the previous SQLite working copy) and for the diagnostic-report index.
- `store run` is the warm path. It sets `pkb_store=true` and launches `pickleball-test-engine` through `WorkbenchDirectRun`, so the result matches a normal engine run. It prints one line per scenario (`scenario PASSED|FAILED <name>`) and one line per pickle step (`step PASSED|FAILED <text>`). Glue is fingerprinted by path, size, and modification time. A change runs one wrapper compile and restarts the worker. `--dry-run` prints the plan and writes nothing. The classpath is `pkb_store_run_classpath` or `.pickleball/store/sandboxes/<name>/worker/classpath.cache`. The CLI rewrites that cache when the classes directory changes and restarts the worker. Gradle `storeRun`, the Workbench Run button, and MCP `store` only forward to the CLI. Gradle does not write `classpath.cache`. `pkb_store_log=run` logs that worker. Full Gradle or Maven stays the CI build.
- Do not store cleartext secrets in Store. Entities hold secret references only, using the existing secrets and config approach. `secret-fields=` in `store.properties` (or `pkb_store_secret_fields`) adds names to that list. A high-entropy value warns and is still stored. The database is not encrypted.
- Linked tracking uses `store_schema=1`. `store hydrate` upserts `step_definitions` from Java `@Given`, `@When`, `@Then`, `@And`, and `@But` string literals under `src/main/java` and `src/test/java`, and from `Given`/`When`/`Then`/`And`/`But`/`defineStep` calls in `glue/js`, `src/test/resources/glue/js`, and `src/main/resources/glue/js`. A `/pattern/` literal is stored with the slashes and matches as a regular expression. `store glue put` of a `.js` or `.ts` file refreshes that file's catalog rows. `engine step --definition=<id> --text=<step>` runs that one definition. One Node process starts for each top-level `PickleballEngine.run` and for each native suite, and stops in `finally`. The native suite reads the store catalog, so a `js` or `ts` row matches there. An existing store gains `step_definitions.host` and the `fixtures` table on open. `*.spec.ts` and `*.spec.js` become scenarios whose keyword is `Playwright`. The file bytes stay. Built-in fixture names `page`, `context`, and `browser` are rows, not instances. Execution stores the handle string `playwright.page`. The spine holds no Playwright object. `pkb_engine_log=ingest` logs `playwright spec <path> tests=<n>`. Java definitions execute through `JavaStepHost` on the application classloader. A stored `js` or `ts` row runs through `NodeStepHost` in `tools.dscode.launcher` on the Node binary inside the Playwright driver-bundle. The engine package does not reference that host, `PlaywrightNode`, or `step-host.mjs`. One JSON object per line. The calls are `resolveText`, `saveValue`, `getValue`, `softFail`, `secret`, `log`, and `entityGet`. `secret` and a secret `entityGet` return `~MASK;` or a reference. A bare import fails with `error: bare import is a rebuild` and `fix: add the package to workbench-ui and rebuild`. Erasable TypeScript runs. `enum`, `namespace`, and parameter properties fail with `fix: keep the TypeScript erasable`. `pkb_engine_log=host` logs spawn, load, invoke, and the call name. `GlueStaticScan` reads the class-file field table and `ConstantValue` attribute. JDK 21 does not ship `java.lang.classfile`. A static field that is not a compile-time constant (`static final` primitive or `String`) warns once per class and field, and hydrate still succeeds: `warn: static mutable field <Class>.<field> will survive a warm reload; move it to scenario state`. The same line is written when `pkb_store_log=hydrate` under entity `glue`. A missing host for the row's `host` column fails that step with `error:` and `fix:`. `definition_id` is the SHA-256 of language, pattern, path, and line. A line move remaps executions to the new id for that language, pattern, and path. A still-referenced leftover is marked removed instead of deleted. `scenario_executions`, `step_executions`, `entity_usages`, `log_entity_links`, `artifacts`, and `example_blocks` exist after open. A tracked run (`pkb_store_track`, or `pkb_run_id` when track is unset) records `scenario_executions` and `step_executions` in `pkb_store_project` when that property is set, and sets `definition_id` when the step text matches a catalog pattern. Each run id matches a diagnostic run id unless the caller already set `pkb_run_id` or a diagnostic run is already active. A finished diagnostic run is not active: `finishRun` drops that reporter, and `runId` is empty when diagnostic mode is off or the reporter is finished. Each top-level in-process `PickleballEngine.run` mints its own id when neither is set, and clears that self-minted id when the call returns. A nested run stays on the outer id. The native API closes the tracked scenario when that scenario finishes, so a later call does not reuse it. Outline rows store `examples_row`. A Cucumber run records when Store is on. An auto-hydrate failure prints `error:` and `fix: store hydrate` and stops the load. Tracking failures do not fail the scenario. A lookup during that step records `entity_usages` operation `read`; `value_ref` is a reference or `~MASK;`, never resolved cleartext. `store entity use` records write or mention. `store history record --link=Type#id` writes `log_entity_links` from that argument, not from the message. `--from` links screenshots and does not delete the diagnostic report. `store query` and `store timeline` read those rows in SQL and do not create a database. Filters include `--tag`, `--feature`, `--run`, and `--text`. `--feature` keeps step and usage rows whose scenario key contains that path. `--tag` keeps step and usage rows for a scenario whose tags match, by file path and scenario line, and a Scenario Outline example row still belongs to that outline. An Examples tag matches only that Examples block, from its Examples line to the next Examples block or the next scenario. Those tags live in `example_blocks` (`file_path`, `scenario_ord`, `ord`, `line`, `tags`). Opening a database backfills that table once from Examples steps that already store tags, then sets `schema_meta` key `example_blocks` to `1` so later opens do not scan again. Schema version stays `store_schema=1`. When hydrate stores a new or changed feature, and when `store feature put` or a shared feature edit writes one, that file's `steps`, `example_blocks`, `scenarios`, and `feature_docs` rows are deleted and rewritten in the same transaction, so replacing a file drops its old `example_blocks` rows. A feature tag matches every scenario in that file. A log row stores the run's scenario name, not a scenario path, so a tag or feature filter does not keep it by key. A blank scenario name is stored blank and is not replaced with the scenario key, so a tag or feature filter still does not keep that log row. A timeline run row stays when that run touched a match. A run row has no scenario path. Timeline also takes `--cursor`, `--before`, and `--latest`. `total` is the full filtered count on every page, including a `--cursor` page. `store retain` deletes old runs and their executions, usages, log links, and artifacts. `step_definitions` and diagnostic report files stay. `pkb_store_log=track` records catalog, execution, usage, link, artifact, and timeline lines. The Workbench Tracking tab forwards `store timeline`, `store query`, and `store retain` through `StoreCliForwarder`. Refresh and Latest open the newest page. Older and Newer use `--before` and `--cursor`. From, To, Tag, Feature, Run, and Text are forwarded. Scrub, play, and speed walk that result. Open in Explorer reuses the existing Explorer and shows a gap when no retained run matches. A blank run id shows `Select a run with a run id first`. An Explorer that is not ready shows `Explorer is not ready`. Rewind is view-only over recorded history. It does not undo browser or service side effects. The Workbench does not open the store or load core store classes. A schema-5 SQLite file is not upgraded in place. The first open rehydrates from files, imports rows with `db_edited=1`, drops run history, and leaves the SQLite file. `pickleball store migrate` does that explicitly and prints `migrated edits=<n>` and `history not imported: <runs> runs`. `--remove-legacy` deletes the previous SQLite directory after success and leaves `.pickleball/store/data`. `--dry-run` does not create the store. An explicit migrate fails while the previous properties file or entity directory is still present and prints the `git mv` lines. A hydrate that changes rows increments `schema_meta` `hydrate_generation` and writes `hydrate_label` as `<git sha>` or `<git sha> dirty`. A hydrate that changes nothing leaves that generation. `store status` prints `store=embedded` and `hydrate generation <n> <git sha>` with ` dirty` when the tree was dirty. Run history stays in `runstate.db`. Design `docs/maintainer/3.0.0-linked-tracking-design.md`, roadmap `docs/roadmaps/3.0.0-linked-tracking-roadmap.md`.

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

### One spine, multiple adapters

Pickleball has one execution spine: the Store-backed execution core. CLI, Workbench, MCP, the native suite, feature files, and GUI/DOM triggers are adapters in front of it. After an adapter selects its input, every path uses the same engine, the same query definition dictionaries, and the same execution path. Adapters select input and forward; they do not own execution logic. The core treats current and future adapters alike and stays decoupled from all of them. New work extends the spine or a thin adapter, never a parallel path. Other file kinds may later be unwrapped into the spine. Known gaps are tracked in `docs/maintainer/execution-path-audit-2026-11-15.md`.

The spine's store is the embedded SQLite database described under `## Store` (`store_schema=1`). The CLI name is `store`. A sandbox defaults to copy-on-write. `--mode=copy` is an independent copy of `main`. `sandbox merge` and `store changes` are CLI commands. Workbench, MCP, and Gradle only forward migrate, sandbox, merge, and changes to that CLI. `install` and `server` are unknown commands. Design: `docs/maintainer/3.0.0-embedded-store-design.md`.

The Cucumber adapter (`pkb_engine=cucumber`) is the one side branch. It exists only for compatibility with legacy Cucumber and older Pickleball projects. It stays intentionally isolated and is not merged into the native engine. Cucumber language, syntax, and functionality never enter the Store core.

The default execution engine is native. `pkb_engine`, then `PKB_ENGINE`, then `engine.properties` `mode=` choose `native` or `cucumber`. `pkb_engine=cucumber` keeps the Cucumber adapter. An unrecognised value fails at first use. Gherkin syntax and step annotations do not change. Every Cucumber-core aspect is still required by the adapter, so none were retired.

Pickleball-owned model types live in `tools.dscode.engine.model`: `EngineStep`, `EngineScenario`, `EngineFeature`, `Table`, `DocText`, `Status`, and `RunResult`. `CucumberModelConverter` is the adapter edge. A Cucumber run does not change when the adapter is selected.

`StepRegistry` reads the same `@Given`, `@When`, `@Then`, `@And`, `@But`, and other `io.cucumber.java` step annotations. Those values stay single string literals. Native matching uses `PatternMatcher` against the stored `step_definitions` rows. `store hydrate` translates a Cucumber expression into that regex and `parameter_types`, prints `translated=` and `failed=`, and keeps the previous row when a parameter type cannot be translated (`error:` and `fix: store glue show <path>`). The Cucumber adapter matches with `ExpressionFactory` inside `CucumberStepRegistry`. DataTable and DocString parameters, step overrides, and ambiguity stay. Dynamic, block-conditional, RUN, and service-call steps execute on the native engine. `pkb_engine_log=match`, `registry`, `ingest`, `watchdog`, or `host` writes `.pickleball/engine/engine.log`. `host` logs Node step-host spawn, load, invoke, and the call name. For a Playwright spec step it logs the verb name (`goto`, `textContent`, or `toHaveTitle`), not the return value and not the URL. `watchdog` logs each hung-step sample and each fire. The entity is the scenario name.

`ExecutionContext` holds the scenario, step stack, map scopes, phase, failures, timing, and browser and service resources. `GlobalState.getCurrentScenarioState` still returns the Cucumber thread local when one is set, and the context's legacy scenario only when that thread local is empty. `ParsingMap.getRunningParsingMap` uses the context map only in that same case.

Mappings, data elements, data operations, and assertions use `Table` and `DocText` (`NeutralRows`, `TabularDataAdapter`, `NeutralAssertions`). Conversion to a Cucumber `DataTable` or `DocString` stays in `CucumberModelConverter`.

Gherkin is parsed with the gherkin library, then converted. `EncodingRewrite` repeats the encoding line swap. `GherkinEngineParser.expand` keeps placeholders, marks background steps, and applies scenario tags. Sources are files, Store rows, in-memory text, and a step list with no file. When Store is on, `PickleballEngine` and the Cucumber adapter read `StoreScenarioSource` rows. A missing row is `error:` and `fix: store hydrate`. `FeaturePathLoadCache` remains the loader only when `pkb_store=false`. A stored `*.spec.ts` or `*.spec.js` loads as scenarios whose keyword is `Playwright`. Those scenarios skip Gherkin expand. The step text stays the source line. `PlaywrightTestIngest` writes the rows and does not import Playwright. `PlaywrightSpecHost` in `tools.dscode.launcher` creates the page at execution and stores the handle string `playwright.page`. The engine package does not reference that host, `PlaywrightNode`, `step-host.mjs`, or `com.microsoft.playwright`. `pkb_store=false` still reads Gherkin files only.

`ScenarioSource` resolution uses tags, name, feature name, example rows, step text, order, and limit. The rules match `CucumberScanUtil` and `ExampleRowFilter`. Memory text and Store rows use that same lookup.

`PickleballEngine.run` and `runStep` run a scenario or one step. Sources are in-memory Gherkin, a runtime step list with nothing on disk, a file, or a Store row. `engine help`, `engine run`, and `engine step` are the CLI. The launcher, `store run --engine`, Gradle `engineRun`, Workbench `EngineCliForwarder`, and MCP `engine` only forward to that CLI and do not run steps themselves. `pkb_engine_log=run` logs the run.

Steps written during a step (`ExecutionContext.writeSteps`) are inserted into the running scenario and run next, with nothing on disk. The result records `source=runtime`. `pkb_engine_log=run` logs those lines under the `runtime` entity.

`DynamicControl.executeStep` runs when no Cucumber scenario is on the thread. The live and paused path is unchanged. The control bridge uses that native call only when it has no scenario lane. `pkb_engine_glue` names glue classes for that call.

`ParityHarness` and `DualRun` run the same feature on the Cucumber adapter and the native engine. Status and step text are compared. A returned value is compared when both sides recorded one. Plain glue matches. `ASSERT: "a" equals "a"` matches. Repo features and every `maven-consumer-project` feature, including dynamic phrases, match. Browser features that cannot launch a browser fail the same way on both sides.

`EngineLog` stays off unless `pkb_engine_log` or `PKB_ENGINE_LOG` names a capability. `pkb_engine_log_entity` filters the entity. Each line starts with an `Instant` date stamp. `pkb_engine_log_max_bytes` caps `.pickleball/engine/engine.log` (default 1048576 bytes, minimum 64). The native engine fires `BEFORE_CUCUMBER_RUN` before the first scenario and, after the last, the same suite finish as the Cucumber adapter (`AFTER_CUCUMBER_RUN`, log close, ReportPortal, pickleball log stop, `AgentCoordination.finishCurrent`).

## Control engine

The Control Engine is the Java engine in `dev.domengine`. It ships in the pickleball jar. Docs, `capabilities.display_name`, and the CLI help first line call it the Control Engine. It is one engine for browsers and for Windows, macOS, and Linux desktops. `pkb_composite=on` selects it. Unset, `auto`, and `off` use `SeleniumAdapter` and do not look for an engine. There is no second engine. `pkb_composite_engine` is not a selector. Unset does nothing. `java` warns once that the key is no longer used. Any other value fails at engine start. An explicit `pkb_composite_bin` is the Java launcher override. Open PRs #2 and #3 on ds-code-t/dom-composite must not be merged, and nothing is pushed to that repo.

`CompositeBinaryLocator` and `DirectControlProvider.resolveBinary("composite", ...)` share one explicit order (`explicitEngineCandidates`): property `pkb_composite_bin`, then `pkb_runvars.pkb_composite_bin`, then env `COMPOSITE_BIN`, then env `pkb_composite_bin`. Env `COMPOSITE_BIN` is working-directory relative. The other explicit slots resolve against the project. Both resolvers return the same absolute path. Then the Control Engine launcher. An explicit binary that is not the Control Engine launcher logs one INFO line naming the source key. An `on` fallback (missing engine, client failed, join failed) prints `error:` and `fix:` once per run and reason and records a `composite.fallback` diagnostic event. It does not consult `pkb_composite_fallback_log`. Do not rename `compositeSessionDir`, `compositeSessionId`, or `session/composite`. The stdio client stays in `dev.domcomposite` and its javadoc says it talks to the Java control engine.

`domengine log list|tail|show` reads the capped engine logs. Pickleball `log` also lists `.pickleball/history.log`. The existing 10 MB front trim on that file is unchanged. The Workbench Logs tab only forwards to that CLI.

`domengine session join` (`--id` or `--cdp`) and `domengine session log --id` (`--follow` streams until the session leaves the registry; refused inside `serve --stdio`) match the control-engine wire. MCP only forwards those argv lists.

`domengine session create --url` returns only after that document's `readyState` is `complete` (bounded wait). A browser-level CDP URL is attached to the current tab's page target; `DOM.*` is not sent to a browser target. A test that starts a browser on a page URL waits with `BrowserSession.awaitInitialDocument` before it queries. That helper attaches the first page target, calls `Page.navigate` for the requested URL, and waits until `readyState` is `complete`. The debugger socket can open while that document is still loading. Chrome or Edge that the engine or a live test starts uses `ChromeProcess`: `--remote-debugging-port=0`, `--remote-allow-origins=*`, the port from `DevToolsActivePort` (45 seconds, including after the starter process exits, then one relaunch into that same wiped profile), and a 30 second debugger wait. A `DBUS_SESSION_BUS_ADDRESS` that is not `unix:` or `tcp:` is `disabled:` for that browser process only. A port timeout names `launcher-exit` and a collapsed `launch.log` (a repeated line is kept once, with a count). A starter that exits does not abandon a child still using that profile. Live Chrome action smoke (`LiveChromeActionSmokeTest`) runs when `DOMENGINE_CHROME` or a Chrome binary is on `PATH`. It is skipped only when that binary is missing. `ActionSmokeTest` still uses the fake DevTools server. `LiveEdgeSnapshotTest` runs when `microsoft-edge` is on `PATH` or `/opt/microsoft/msedge/msedge` is executable. It starts the `msedge` binary beside a wrapper script. A host with neither skips with `microsoft-edge is not on PATH`.

`query --wait-for attached|visible|hidden|detached` re-reads the live tree. `hover` is an action and `--force-hover` sets the hover pseudo-state. `scroll` with both deltas 0 calls `DOM.scrollIntoViewIfNeeded`. A non-zero delta stays a mouse wheel. A click, double-click, context-click, or type scrolls the node into view and sends the mouse at the viewport center. A click on an `option` selects that option and fires `input` and `change` on its `select`. `drag --source` and `--target` take a CSS string or a JSON object whose `css`, `xpath`, `pipeline`, or `selector` field is the query. Both ends are scrolled into view before the mouse sequence. A named key focuses that node, then uses the DOM name and code (`ENTER` is `event.key` `Enter`, `code` `Enter`). A chord sends modifier bits (Alt 1, Control 2, Meta 4, Shift 8). A printable keyDown sets CDP `text` and does not set Control, Alt, or Meta. `type` without `--append` clears the control (DOM value or contenteditable text, else select-all and delete) and inserts. `--append` inserts. A press of only letters and digits uses `--append`. Element text skips a shadow root whose text repeats the light-DOM text, so a native option label is not doubled. A combined clause is the target alone. A search xpath that also names a context does not run that unscoped pipeline first. `--filter displayed` runs before `nth`, `every`, and `count-only`. `--frame` and `--shadow` set the root (`not_found` when missing). `--all-frames` is the main document, then frames depth-first. `--node-id` is a backend node id; a miss is `stale` unless `--refind`, and the adapter throws `StaleElementReferenceException`. Row states are `hidden`, `disabled`, `checked`, `selected`, `readonly`, `required`, `expanded`, `collapsed`, and `focused`. `query --limit` and `eval --node-ids` are engine flags. A failed capability read is not cached. A `domengine` version uses the Java verb lists. When capabilities cannot be read, the fallback action list for that call only is `click`, `type`, and `scroll`, and two consecutive failures block that field for 5 seconds. Errors are `{"ok":false,"error":"<message>","code":"<code>"}`. An engine flag a verb does not read is exit 2, `unrecognized flag --<name> for <verb>`. `capabilities.verb_flags` lists only flags that verb reads. The adapter sends a relied-on flag only when that list contains it. Live XPath is XPath 1.0 evaluated by the page (`document.evaluate`). An invalid expression is an error, not zero hits.

`domengine conform --root DIR --live` evaluates the vendored control-engine fixtures in headless Chrome, including the assertions offline `conform` skips. `LiveConformanceTest` skips only when that binary is missing. Offline `ConformanceSuiteTest` is unchanged. Those HTML and JSON files are the control-engine wire contract.

An unjoined WebDriver stays on Selenium for nesting and for a composite find. Reading engine query flags does not launch Chrome. `ContextAnchorReproTest` and `NestAnchorReproTest` pass with or without a Chrome binary. `assertEquals(1, deepest.size())` is unchanged. A joined browser still sends `--within` and `--nesting`.

The syntax layer is `dev.domengine.query`. The dictionary layer is `SelectorTranslator` and the execution dictionary. `>>` stays a sequential pipeline. `AND` is intersection, `OR` is union, and `NOT` is the complement of the current scope. Parentheses outrank `NOT`, then `AND`, then `OR`. Mixed `AND` and `OR` at the same level require parentheses. Capitals are canonical. A lowercase `and`, `or`, or `not` is accepted and logs one warning per process and operator (`lowercase operator 'and' read as AND; write capitals`). `domengine query --selector` and `--pipeline` accept the expression. `DOMENGINE_LOG=expr` or `COMPOSITE_LOG=expr` logs evaluation. That log stays off unless the entity is named. It is date-stamped and size-capped. Selenium fallback returns null for `AND`, `OR`, `NOT`, and for a relation form.

Nine relations: `near`, `below`, `above`, `left-of`, `right-of` (`left` and `right` are aliases), `inside`, `after`, `before`, and `between`. Stage form is `css=#anchor >> inside=button`. Filter form is `<relation>(<anchor query>)`, including `not-near(text="Cancel")` and `inside@0.5(role=dialog)`. A candidate is above when its bottom is at or above the anchor top, left-of when its right edge is at or left of the anchor left, and right-of when its left edge is at or right of the anchor right. `inside` keeps a displayed candidate whose box lies fully inside an anchor. `between` keeps a displayed candidate whose center lies in the axis-aligned box of the two anchor centers. A missing second anchor is an error. Hits that use geometry need a box and must be displayed, then sort by center distance. `after` is document order (`following::`) and `before` is `preceding::`. Neither needs a box. `exposure=human` uses the visual box when one was stored. A negated relation drops candidates that satisfy the positive relation. A weight is stored and ignored by the filter. `in`, `from`, and `for` stay `<context> >> <target>`. The other context words become the filter form. `DOMENGINE_LOG=relation` logs anchors, candidates, kept, and the space, and stays off by default. `DOMENGINE_LOG=visual` still logs the geometry space and rank count.

The dictionary layer emits a combined clause first. Target clauses are one AND group, for example `(role=button AND text="Save")`. The XPath runs only when that pipeline matches nothing. A pure XPath definition still resolves and logs one deprecation warning. Pure CSS passes through.

`look` matches computed style. `look=color=red` and `look=opacity>0.5,background-color=#fff` are stages. A comma is AND and a pipe is OR inside the stage. `contrast` is the contrast of `color` against the painted `background-color`. It is not a screenshot match, and there is no engine named `attribute`. `DOMENGINE_LOG=look` logs the matched properties and stays off by default. A step can add `with look <prop> <predicate>` after the existing attribute clause, for example `with look color equaling red` or `with look opacity greater than 0.5`. That clause is not an HTML attribute. `SelectorTranslator` maps it to a `look=` stage on the same engine query string. `with id attribute` and `with id noattribute` are unchanged. Cucumber step annotations stay single string literals. `pkb_composite_log=query` logs `forward selector=` and stays off by default. A parsed step with that clause applies the look stage on the production find (`ElementWrapper.getWrappedElements` and `ContextWrapper`). Attribute and noattribute grammar still apply. Unset, `auto`, and `off` fail the find instead of ignoring look. `pkb_composite=on` evaluates it. Selenium is not asked to evaluate a look stage.

`QueryHooks` is the only query file that imports pre-map or affinity. `Query` calls pre-map, rank, and one-match once each. `Expressions` calls `candidates` once per stage. Pass-through leaves the hit order unchanged. `--rank affinity` scores, and a negated satisfied term lowers that score. `--strict` throws `not found` or a message that starts `ambiguous: N candidates`, and the CLI JSON carries `candidates`. Browsers and desktops run the same query string. Browser DOM and desktop UI share one query language, one verb set, and one walk/attach/resolve model. Browser and desktop queries both go through `Query.run`. `frame`, `shadow`, and `pierce` are rejected on desktop. Named exceptions are in the design.

Verb tiers live in `tools.dscode.control.protocol.VerbTiers` (human: act, capture, chat-read, doctor, explain, find, help, screenshot, snap, wait, wait-for; every other verb is control). `HumanLock` enforces `start --human` and `session-mode --human=on|off` in both `WorkbenchAgentCommands.run` and `AgentControl.execute`; a control-tier verb in a locked session is an error/fix refusal, and `HumanTierTest` fails when a verb has no tier.

Live native desktop access runs on the machine where the engine runs (Windows UI Automation, macOS Accessibility, Linux AT-SPI). The fixture backend is kept for tests and CI. `desktop attach --fixture` replays a recorded tree. `desktop attach --live [--name]` reads the platform. Live click and type call that platform or fail. They do not report success for a recorded tree. Linux live desktop reaches the accessibility bus with `dbus-send --session` and `DBUS_SESSION_BUS_ADDRESS` set to the address from `org.a11y.Bus.GetAddress`. It does not pass `--address=`. `AtSpi.busAddress()` still uses `--session` on the real session bus. Role is `GetRoleName`, then `GetRole`. `Properties.Get` stays for Name only. The numeric fallback matches at-spi2-core 2.56 `AtspiRole`: `FOCUS_TRAVERSABLE` is 21, so frame is 23 and button is 43, and `EXTENDED` follows window, so application is 75. A frame named for `desktop attach --name` maps to `window`. `role=button` matches GetRoleName `button`. A missing bus still fails with `AT-SPI unavailable`. The live-success test skips when the bus is absent. Linux desktop control is AT-SPI plus `xdotool` on X11 or Xvfb only. Wayland is unsupported: a Wayland-only session (`WAYLAND_DISPLAY` set and `DISPLAY` unset, or `XDG_SESSION_TYPE=wayland` without `DISPLAY`) fails `desktop attach --live` with `error: Wayland session detected; desktop control needs X11 or Xvfb (set DISPLAY)` and a `fix:` line. Xwayland with `DISPLAY` set is allowed. `grim` screenshots are not a supported desktop-control path.

The jar command is `domengine`, which is not the step CLI `engine`. `PickleballWorkbenchLauncher` dispatches it. MCP `domengine`, `DomEngineCliForwarder`, and Gradle `domengine` only forward. Workbench `installDist`, `:run`, and tests carry a non-transitive `dom-engine` jar. That jar is not a compile dependency and it is not published. Do not publish a separate `dom-engine` artifact as a substitute. The classes and sources are inside the pickleball jar. A verification test fails if they are missing.

## Browser protocols and the agent boundary

Scripted element operations stay on the library adapter unless `pkb_composite=on`. `DriverConstruction.ready` selects `ProtocolSelector` `LIBRARY` for the driver it just built and does not open a published `se:cdp` or BiDi websocket. Direct CDP and BiDi are `AgentProtocolController`. Page surfaces prefer a direct CDP or BiDi socket. On Chromium the selected tab's target id comes from Selenium `Target.getTargetInfo`. Overlay, page, isolated worlds, preload, and bindings run on that tab after `Target.attachToTarget` with flatten. Selenium `executeScript` is the logged last resort. Cleanup enables the DOM domain before `Overlay.enable` and `Overlay.hideHighlight`. A highlight hide or later cleanup on a tab, frame, or driver that is already closed is skipped. A live tab whose hide or switch throws still fails the command.

`pkb_composite_fallback_log` (`warn` by default, `info`, or `off`) logs one line per run and reason when `on` cannot start the engine (missing engine, client failed, join failed). The line names the engine path or `none` and the fix. It is also a `composite.fallback` diagnostic event. Unset, `auto`, and `off` do not look for an engine and do not log a fallback. `pkb_composite=on` still prints `error:` and `fix:`. An explicit executable that is not the Java launcher logs one `INFO composite.engine override` line. The consumer `@dom-composite` scenarios set `pkb_composite=on` so they still exercise the engine.

MCP is an agent entry, not a second implementation. `domengine mcp` and Workbench MCP tools forward argv or agent ops. Popup commands `agent_popup_open`, `agent_popup_status`, and `agent_popup_close` are registered that way. A driver built outside a scenario, including agent `launch --start`, reads the debug-browser flag from the open scenario when there is one, otherwise from `pkb_debug_browser`, otherwise it leaves the port alone. It does not throw.

Chrome, Chromium, and Edge use CDP. Firefox uses BiDi, or a logged Selenium fallback for the snapshot producer.

## Snapshots and pre-mapping

A composite snapshot is the page-level artifact: screenshot, source, structure, paths, a viewport map, and a pre-map. The pre-map is core. `CompositeSnapshotBuilder.build` always runs `PreMapBuilder`. There is no flag to skip it. The per-element capture is an element snapshot. Workbench MCP `snapshot` does not compile against dom-engine. It calls `SnapshotDispatch.render(JsonNode)` by reflection. When that class is absent it calls `SnapshotProducers.page` only if every parameter is String, Map, or JsonNode, and otherwise returns the schema-shaped placeholder.

`PreMapLookup.installed()` is the live session lookup, not a pass-through. A session query reads that pre-map. A miss rebuilds from the tree the query already holds and does not call `DOM.getDocument` again. `--explain` shows `premap: hit` or `premap: rebuilt <ms>`. `Query` asks `candidates` once per searchable stage. `--rank affinity` ranks relatives. `--strict` resolves one match and lists candidates when the match is ambiguous.

Chrome, Chromium, and Edge use the CDP snapshot producer. Firefox uses BiDi, or a logged Selenium fallback. Windows UI Automation, macOS Accessibility, and Linux AT-SPI use the desktop producer. Live hit-testing, focus order, top layer, screenshots, Playwright-only queries, and scripted shadow roots that the producer cannot fill are skipped, not failed. The canonical field list is `docs/snapshot.md`.

To join a browser that is already running, use `attach --cdp-endpoint=chrome|msedge|<url>`, `attach --extension`, `attach --selenium=<debuggerAddress>`, or `attach --discover [--candidate=<n>]`; CDP routes end in the `attach --target=browser:cdp=<ws>` join, so the other verbs work on it. `relaunch-with-debug --confirm` is the last resort. `pw <area> <action>` and `cdp --method=` run Playwright features and raw CDP over that browser (Playwright attaches over CDP and never downloads a browser). `doctor --attach` lists the routes and fixes. See docs/playwright.md.

## Composite snapshot v2

Early 3.x accepts snapshot versions 1 and 2. Producers emit version 2. `capabilities.snapshot_schema` stays 1. Version 1 is `$id` `urn:pickleball:composite-snapshot:1`. Version 2 is `$id` `urn:pickleball:composite-snapshot:2`. The family name stays `pickleball.composite-snapshot`. The guide is `docs/snapshot.md`. Design `docs/maintainer/3.0.0-composite-v2-design.md`.

The canonical form is raw HTML for each browser frame, serialized from the captured DOM tree (CDP DOM, or `page-walk.js` on the facade), not from the browser's `outerHTML`. That is one pierced tree, including closed shadow roots and cross-origin frames, with the captured attribute order, and the serializer does not change the live page. `outerHTML` stays on `source.text`. An open or closed shadow root is `<template data-pkb-shadow="open|closed">`. An iframe stays in the parent with `data-pkb-frame`, and the iframe document is its own frame. Desktop UIA, AX, and AT-SPI are a synthesized HTML-ish DOM (`canonical.kind` `html-ish`) plus `canonical.side`. `html-ish` requires `side`. `html` with `side` is an error. The role-to-tag map is `NativeDesktop.mapRole`. UIA `AutomationId` becomes `id` only when it is a token, and it is always `data-uia-automation-id` for backend `uia`. An `input` or `img` that has children is written with an end tag, and the parser keeps those children. A void tag with no matching end tag stays empty. A stray end tag does not stop the parse. Whether each void start has a matching end tag is decided in one stack pass per parse. Flat `nodes[]` and `premap` are a projection. Live boxes, roles, names, and states are copied onto the parsed elements. Two builds of the same canonical HTML produce the same projection. A version 1 document with no canonical tree still validates.

`xpath=` and `css=` run on that tree after the view mask. An absolute path whose every step is `tag[n]` uses `XPathIndex`, and a miss is zero hits. The older tag, attribute, and `contains()` subset stays. Anything else is XPath 1.0 through `javax.xml.xpath`. Shadow roots are separate documents when pierce is `open` or `all`. Iframes are not crossed. Live `document.evaluate` is unchanged. CSS adds the `+` and `~` combinators, the `~=` and `|=` attribute operators, and Level 3 pseudo-classes: `:nth-child`, `:nth-last-child`, `:nth-of-type`, `:nth-last-of-type` (`an+b`, `odd`, `even`), `:first-child`, `:last-child`, `:only-child`, `:first-of-type`, `:last-of-type`, `:only-of-type`, `:not` of one compound, `:checked`, `:disabled`, `:enabled`, `:empty`, and `:root`. `:disabled` and `:enabled` are HTML form controls, including a disabled `fieldset` except its first `legend`. `:not` rejects a combinator, a comma, and a nested `:not`. An unsupported pseudo-class or a pseudo-element is an error, not zero hits. `css-simple` still refuses a selector that contains `:`. No new XML or CSS library. An invalid expression is an error.

Human and full-access are lazy masks, not a second tree. `exposure=human` keeps a node that is seen or reachable from stored state and geometry. The engine does not scroll or expand. Seen means the box intersects a known viewport, the node is displayed, and no later opaque box covers it when `data-paint-order` was stored. `exposure=full` and `exposure=full-access` keep every node. A query with no exposure stays unfiltered. Real-use callers pass `exposure=human`. Page actions set `mask=off`, so a click or type still finds a target outside the human view. Human mode governs mouse and keyboard input only. The mask is cached once per root object and document version, at most 32 entries, keyed by the root object. A blank document version stamps element count and node id and geometry, so an in-place edit is recomputed.

Each v2 node records `identity.chain`: one unique stable attribute (`id`, `data-testid`, `data-uia-automation-id`, then role plus name plus section), then a desktop `uia-runtime-id`, `ax-path`, or `atspi-path` that is not all digits, then a fingerprint of dHash, text, and relative bounds, then the index-qualified XPath with its shadow and frame paths. Replay stops at the first hit and reports `matched`, the locator, the chain index, `handle`, and `backend_node_id` when the node has one. `query --identity` and `desktop query --identity` replay a chain array, `{chain}`, or a v2 node `{identity:{chain}}`, inline or from a file. A malformed value is exit 1, code `invalid`. `act --identity=<chain|file>` replays a recorded chain and acts on the hit. Refs from `snap` and `find` keep their chain: a replaced node stays `stale_ref` unless the agent passes `act --refind=chain` (or sets `pkb_ref_refresh=chain`), which re-finds it and reports `refound {matched, locator, index, why}`. Every replay is logged; positional XPath is the last step tried. A version 1 document does not carry the chain. dHash uses `TemplateMatch.prefilter` at 10 bits. Text matches after trim. Each relative component must be within 0.05.

OCR runs for tag `img` or `svg`, role `img`, `image`, `graphics-symbol`, `graphics-document`, or `icon`, an `i` or `span` with an icon-font class, and a desktop icon role. A `use` inside an `svg` is not a second target. The cache key is the SHA-256 of the crop width, height, and pixels. One process cache is shared by both views. An element outside the human mask is not recognized and does not read the runtime. `pkb_ocr_runtime`, then `PKB_OCR_RUNTIME`, selects the runtime. `onnx` is the opt-in slot: nothing is bundled; `setup ocr --runtime=onnx --confirm` installs pinned, checksum-verified ONNX Runtime 1.32.0 (MIT) and the PP-OCRv3 English model (Apache-2.0) into `pkb_ocr_dir` (default `~/.pickleball/opt-in/ocr/pp-ocrv3-en/1`). Until then it loads nothing and returns `OCR runtime not installed` (`ocr_runtime_missing`) with that fix; `doctor` prints `ocrRuntime=`. A `pkb-ocr 1` file maps a crop hash to a line of text and is not executed. Any other existing file returns `OCR runtime cannot be invoked` (`ocr_runtime_unsupported`). `ocr-views` lists `human`, `full`, or both (the default). Tokens are case-insensitive and whitespace-tolerant. A blank value means both. An unknown token is exit 1, code `invalid`, message `invalid ocr-views: <token>`. A view that is not listed returns zero hits and does not read the runtime. FlaUI.WebDriver is flagged and not added. JDK 21. No FFM.

## Agent surfaces

In-page surfaces live in `tools.dscode.common.agentcontrol`. The popup lives in `tools.dscode.common.agentsurface` and does not depend on the Workbench. One host process per project and run id serves a Chromium app window on loopback HTTP and server-sent events. The token is 128-bit hex and compared in constant time. The Host header must be `127.0.0.1:<port>`. `popup-open`, `popup-status`, and `popup-close` are agent ops, CLI verbs, and MCP tools. `AgentSurfaceRouter.route` is the one guarded call at the top of `AgentControl.execute`. When it returns empty, that call is unchanged.

`pkb_agent_popup=auto` opens the popup for a desktop session and for an explicit `--target=popup`. `always` opens it for chat and for HTML or overlay writes that have no `--handle`. `off` returns an empty route for existing ops. A popup verb while off returns `opened=false` and `fallback=off`. No Chromium logs one `no-chromium` warning, writes `host.json` with `state=closed`, and still stores the ledger row. A surface op then returns `ok=true`, `target=none`, and `fallback=no-chromium`. `popup-close` writes one close row and waits up to 10 seconds for the host pid, the browser pid, and `profile/` to leave. In the last two seconds, and again at the deadline, it force-kills a host or browser that is still alive, the child processes of that host and browser, and any process whose command line contains that profile path, then deletes the profile. The result field `exited` is true when the profile is gone and the host and browser pids are gone, and it can also be true once the host logged its exit and the profile is gone. `PopupCli` `popup-close` exits 1 when the host does not exit. Scenario cleanup clears the Instructions, Diagram, and overlay panes and keeps chat. A JVM that opened hosts writes `close` on shutdown.

`highlight --target=native`, and a desktop highlight that already has bounds, draws four 3 px `#ff2d55` strips from `--x --y --width --height` or from the bounds on `--query` JSON. HiDPI uses `GraphicsConfiguration.getDefaultTransform()`. A headless runtime returns `drawn=false` `reason=headless`. The caption goes to the popup Instructions pane. The Workbench caption window is used only when the result says `popup=false`. Channel, discovery, and the routing table are in `docs/direct-control-handoff.md`.

Visual in-page components stay on this spine. The contract is `docs/roadmaps/3.0.0-visual-components-roadmap.md` (label, border, arrow, numbered step badge, magnifier, spotlight, overlay width and height). `PageSurfaces.highlight` ignores x, y, width, and height. `rememberGeometry` stores only a point. Overlay `--width` and `--height` are stored and used only when no resolved element box is available. A resolved box (visual-viewport width and height, or the same width and height on the native query path) is the box. The overlay callout colors, border, and padding stay fixed.

Label, border, arrow, step badge, magnifier, and spotlight are first-class `SurfaceAssets` kinds and CLI verbs (`label`/`unlabel`, `border`/`unborder`, `arrow`/`unarrow`, `step-badge`/`unstep-badge`, `magnifier`/`unmagnifier`, `spotlight`/`unspotlight`). They use the same spine, the same resolved box, and the same cleanup. A step badge is a page badge, not a Gherkin edit. Native draws the rectangle and stores the caption; the HTML callout, arrow shaft, magnified clone, and page dim are in-page. Logging entities: `label`, `border`, `arrow`, `step`, `magnifier`, `spotlight`. The close-out pointer is `docs/agent/README.md` and `docs/roadmaps/workbench-cli-inventory.json`.

The overlay polish (3.0.0) is implemented: popup and Workbench chat send on Enter, echo at once, and keep a reconnecting stream; one page runtime (`pkb-runtime.js`, `window.__pkbUi`, `pkb-surface` isolated world, main-world fallback) keeps placements on their targets through nested scroll containers, resize, zoom and DOM changes without blocking them unless `block:true`; `panel-open`/`panel-close` show a draggable, resizable chat panel in the page; `guide`/`guide-next`/`guide-back`/`guide-done` and `ask` show numbered steps and questions whose events arrive in `overlay-poll` and never carry sensitive values; `UiState` re-injects what is shown after a reload or SPA route change through a `pkb-surface` preload; `ui-catalog`/`ui-apply`/`ui-poll` (MCP `ui_catalog`/`ui`/`ui_poll`, `WorkbenchUiTools`) batch it. `ui` stays the Workbench UI verb. Contract: `docs/dynamic-control-api.md`; audit `docs/maintainer/3.0.0-overlay-polish-audit.md`, roadmap `docs/roadmaps/3.0.0-overlay-polish-roadmap.md`.

Agent data (3.0.0) stays on that same CLI. `controller-start` is one JVM per project (`PkbMain`, `AgentController`, `.pickleball/agent-controller.json`). A later verb is a client of that process. `controller-stop` ends it. MCP and the live worker bridge are unchanged. Every reply is the envelope in `AgentEnvelope` (`v`, `ok`, `op`, `ms`, `state`, `sum`, `items`, `next`, `trunc`, `err`). `SessionIds` adds a short id (`s1.h42`) beside the long `ref:`. `ref` expands it. `AgentCursor` is the one `next` token for chat and ui-poll, and the same token reads the same position again. `SnapView` and `LineView` apply `--format=lines|json`, `--fields`, `--where`, `--limit`, and `--budget`. The default snap stays at most 200 lines or 16 KB. Schema: `docs/dynamic-control-api.md`.

Visual-component verify (coordinator): step badge and magnifier text is escaped; a live magnifier copy is scaled around its center so the label stays in the oval, stays inert, and has no ids, names, inline handlers, or scripts; `un*` logs under its own entity; a native `un*` with no id hides `native-<kind>`; scenario end hides every native rectangle. Tests: `AgentExpansionTest`, `NativeHighlightRouteTest`, `NativeHighlightBoxTest`, `LiveChromeCdpTest`.

## Workbench

The Workbench GUI can add, delete, and modify element categories, general configuration and data files, scenarios and scenario steps, and Java step definitions, plus JS and TS source, through one CLI-first edit model. `store category`, `store file`, `store glue`, and `store feature scenario-add`, `scenario-modify`, `scenario-delete`, `step-add`, `step-modify`, and `step-delete` are the write path. The Editor, MCP `store`, and Gradle `storeEdit` only forward. A scenario or step edit splices that region, keeps comments outside it, normalizes the indent of the edited region, and writes valid Gherkin through `feature put` when Store is on, or the file when Store is off. `glue/js/*.js` and `glue/js/*.ts` `defineStep` and `Given`/`When`/`Then`/`And`/`But` calls execute through `NodeStepHost`. They do not replace Java glue. Java step annotations stay a single string literal. A successful Java compile updates the glue fingerprint and stops the warm worker. A compile failure leaves that worker. Secret fields are references (`<name>` or `~MASK`). Deletes need `--confirm`. A conflict is refused unless `--force`. `pkb_store_log=edit` logs each edit.

`EditPanel.getText()` and `TrackingPanel.getText()` are Swing and are classified with `LogPanel`. Gradle `storeEdit` forwards to `StoreCli` with the other store tasks. Workbench launch console capture keeps a 256KB head and a 512KB tail so a long workbook dump cannot exhaust the test heap. `CaptureBuffer.toByteArray`, `size`, and `writeTo` return the capped head, the truncation marker, and the tail. `LiveChromeCdpTest` uses the dom-engine Chrome lookup (`DOMENGINE_CHROME`, then `google-chrome`, `google-chrome-stable`, `chromium`, `chromium-browser`, `chrome` on `PATH`).

The control-bridge pause is held until BEGIN has set `pauseRequested`, and VERIFY requires that pause to be AFTER_STEP of BEGIN. The native engine records that step before AFTER_STEP returns, so a step executed while the pause is held is recorded after it, matching the adapter. The bridge publishes the running scenario when it starts inside that step, so the flag is visible before the step returns. Publishing that scenario still arms pause-first, so an isolate session pauses on the hook that opens the bridge instead of running to the end. A driver, DOM, or page-ready hook that still belongs to the step does not take the pause. A native run installs the Cucumber step runner and a thread test-case state so a detached service call can execute. A later Cucumber adapter run replaces that runner and the thread test case so its plugins see the pickle steps. Step order is not sorted. Event sequence numbers are assigned under the same lock as the append. Build provenance streams the git diff into a hash instead of buffering it in the Gradle daemon. Root `reports/` workbook HTML and `report.xlsx` are generated and gitignored. The breaking-change list stays empty.
