# 2026-11-25 Spine Design

This is the implementable design for Dan's locked plan in `docs/plans/2026-11-25-sqlite-spine-plan.md` and the earlier brainstorm `docs/brainstorm/2026-11-25-sqlite-spine-and-cucumber-decoupling.md`. The plan header still says "BRAINSTORM ONLY". Dan's later instruction overrides that header: implement every locked item, and settle every open question and every "Open Items Remaining" entry here. Build sessions follow the roadmaps under `docs/roadmaps/3.0.0-spine/`. They do not reopen a decision recorded in this file.

Written from the `3.0.0-spine` tree. The code names below are the classes, tables, and verbs that exist in this checkout. A fresh Build session that follows a roadmap should not have to guess a package.

## What exists today

The store is already embedded SQLite, `store_schema=1`, driver `org.xerial:sqlite-jdbc:3.47.2.0`. Schema DDL is `tools.dscode.store.sql.SqlDialect` (`SOURCE_DDL`, `RUN_DDL`, `BRANCH_DDL`). The source database is `.pickleball/store/sandboxes/<name>/source.db` (branch label `main` for the working copy; the file people still call `main.db` in older sentences is that sandbox). Run history is `.pickleball/store/runstate.db`. Git files stay the source of truth. `pkb_store=false` stays file-only.

Source tables today: `schema_meta`, `files`, `provenance`, `feature_docs`, `scenarios`, `example_blocks`, `steps`, `entities`, `step_definitions`. Branch bookkeeping on each source database: `_pkb_branch`, `_pkb_base`, `_pkb_actor`, `_pkb_changes`. Run-state tables: `runs`, `run_logs`, `scenario_executions`, `step_executions`, `entity_usages`, `log_entity_links`, `artifacts`, `sandboxes`.

The CLI is `tools.dscode.store.StoreCli`, dispatched from `tools.dscode.launcher.PickleballWorkbenchLauncher.dispatchStoreIfAny`. Verbs that already exist and stay: `migrate`, `sandbox` (`create|list|use|diff|extract|drop|prune|merge`), `changes`, `status`, `hydrate`, `extract` (alias `hydrate-out`), `history` (`list|show|record|retain`), `entity` (`list|get|put|remove|export|use`), `feature` (including `put` and `scenario-add|scenario-modify|scenario-delete|step-add|step-modify|step-delete`), `category`, `file`, `glue` (including `compile`), `files`, `features`, `scenarios`, `steps`, `conflicts`, `query`, `timeline`, `retain`, `run`, `run-classpath`. `install` and `server` stay unknown. Workbench and Gradle only forward (`StoreCliForwarder`, Gradle `store*` tasks). The Workbench process does not open SQLite.

Hydration, hash skip, and jar-independent full rewrite already live in `StoreHydrator`, `StoreHashes`, `StoreDiscovery`, `StoreExtractor`, `StoreContent`. Unchanged `content_hash` values are skipped. `--force` rewrites. Conflicts are listed by `store conflicts` and refused by extract unless `--force`. There is no side-by-side choice and no discarded-copy hold. Sandboxes are independent full copies (`VACUUM INTO` from `main`), with no parent pointer. That was a locked decision of the embedded-store work. This plan overrides it for the default create path. See "Conflicts" below.

The native engine is `tools.dscode.engine.PickleballEngine`, `NativeScenarioRun`, `StoreScenarioSource`, `FileScenarioSource`, `MemoryScenarioSource`. `pkb_engine=cucumber` selects `CucumberAdapterRun`. The default is native. `StepRegistry` still imports `io.cucumber.cucumberexpressions.ExpressionFactory`, Cucumber hook annotations, and `TagExpressionParser`, and it still walks jars and directories with `java.nio.file` and `JarFile`. That is the opposite of "the runtime sees only rows". `CucumberModelConverter` is the adapter edge and stays an adapter.

Step definitions are Java methods with a single string-literal `io.cucumber.java` annotation. `StoreStepCatalog` already upserts `step_definitions` from those literals and from `defineStep` calls under `glue/js` (`*.js` and `*.ts` are source only; they do not run). `FlagSteps` methods for `ALWAYS RUN`, `RUN IF SCENARIO PASSING`, `RUN IF SCENARIO FAILED`, `RUN IF SCENARIO HARD FAILED`, `RUN IF SCENARIO SOFT FAILED`, `IGNORE FAILURES`, `LOG FAILURES BUT CONTINUE SCENARIO`, and `RUN IF SCENARIO FINISHED` are empty. `GeneralSteps.retryStep` (`^RETRY:.*$`) is empty. `io.cucumber.core.runner.StepBase.stepMaxIterations` and `stepTimeoutSeconds` are never assigned. `PhraseData.runUntilOperation` is not the block-until loop.

The control engine is `dev.domengine` (query in `dev.domengine.query`, relations in `Relations` and `Expressions`, desktop in `dev.domengine.desktop`). Eight relations already exist: `near`, `below`, `above`, `left-of`, `right-of` (`left` and `right` are aliases), `inside`, `after`, `before`. There is no `between`. `DesktopScreenshot` can capture a rectangle. There is no `desktop launch` verb. `DomEngineMain` screenshot is a browser session shot.

Playwright for Java `1.65.1` and `driver-bundle` are normal POM dependencies, not shaded. `verifyPlaywrightPackaging` fails the build if Playwright classes or the driver bundle are inside pickleball's jar. `tools.dscode.launcher.PlaywrightNode` resolves the bundled Node through `com.microsoft.playwright.impl.driver.Driver.ensureDriverInstalled(env, installBrowsers=false)` and never runs npm. Dan verified that binary is Node 24.22.1. Browser download is off (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`). Playwright joins with `connectOverCDP`. `RouteCommands` implements `net-route` actions `fulfill`, `abort`, `continue`, and `modify`. The `modify` arm on the Playwright, BiDi, and CDP paths changes headers and does not send `rule.body`.

Agent replies already have `tools.dscode.common.agentfast.AgentEnvelope` (`v`, `ok`, `op`, `ms`, `state`, `sum`, `items`, `next`, `trunc`, `err`). Store `--json` and domengine JSON do not all go through that envelope. `WorkbenchCliCoverageGateTest` fails when `docs/roadmaps/workbench-cli-inventory.json` drifts from the parser sets. MCP tools in `pickleball-workbench` (`WorkbenchMcpServer`, `WorkbenchMcpTools`) forward. The Workbench window is Swing (`WorkbenchFrame`) plus an OpenJFX WebView. The popup host (`PopupHost`, `AgentSurfaceRouter`) is loopback HTTP and server-sent events on `127.0.0.1`, 128-bit token, constant-time compare.

There is no class named `PickleballException`. The public seam is `PickleBallExceptionInterface`. `StatusException` and `StepCreationException` implement it. `SoftException` and `SoftRuntimeException` are separate.

Dashboard reads are `tools.dscode.store.dashboard` (`DashboardCli`, `SqliteDashboardSource`). They query run-state. They are not a frozen copy of the hydrated project.

`pkb_callpath` and `pkb_componentpath` still use historical project-relative defaults. That debt stays. This program does not change their path grammar.

## Conflicts and the resolution

1. **Independent sandboxes versus copy-on-write.** Embedded-store sandboxes have no parent. The spine plan requires a copy-on-write file of differing rows, a parent reference, tombstones, and flatten-on-promote. The newer plan wins for the default. `store sandbox create` defaults to `--mode=cow`. `--mode=copy` keeps today's full copy, and `SandboxIndependenceTest` must keep passing for that mode. A cow sandbox is not attached with SQLite `ATTACH` during a run. `UniversalEntity` resolves the chain in Java and writes a flattened head. A run reads head tables only.

2. **Where the hydrated database lives.** The plan says the hydrated SQLite lives in `target/` or `build/` and is gitignored. The working copy already lives in `.pickleball/store/`, which is gitignored, and every CLI, test, and guide uses that path. Moving it to Maven `target/` or Gradle `build/` would delete the store on every clean and would split Gradle consumers from Maven consumers. The path stays `.pickleball/store/sandboxes/<name>/source.db` and `.pickleball/store/runstate.db`. "Build artifact" in the plan means "not a Git artifact". JAR-hash invalidation is the clean trigger, not the directory name.

3. **JAR-hash wipe versus run history.** A changed pickleball code-source jar hash wipes the hydrated source projection and rebuilds the head from the pinned commit and current files. It does not delete `runstate.db`. Run history is removed only by `store retain` / `store history retain`. Reason: the dashboard is the audit log. A rebuild is not a retention event.

4. **`store_schema` stays 1.** New tables and new columns are additive. `SqlDialect.ensureSchema` creates missing tables and adds missing columns. Each backfill sets a `schema_meta` key (the `example_blocks` pattern). The version number stays `store_schema=1`. A bump would trip the schema-5 rehydrate path and strand existing databases. Reason: the public store contract keys off version 1.

5. **Playwright is not shaded, and frontend-maven-plugin is not used.** `verifyPlaywrightPackaging` stays. The Node binary stays inside the `driver-bundle` dependency. It is not copied into pickleball's jar. `frontend-maven-plugin` would download a second Node distribution. That violates "no new Node distribution". The build locates the driver-bundle Node the same way `PlaywrightNode.locate()` does. Details are in "Distribution and licensing".

6. **Browser downloads.** Today pickleball never downloads a browser. The plan's source enum can download when the operator asks. Default `source: local` fails hard when the requested version is not a local install and does not contact `playwright.azureedge.net`. `source: cdn` and `source: any` may download. Unset YAML keeps today's no-download path. `CHROME_HEADLESS.yaml` does not gain a `cdn` source.

7. **Playwright becomes the primary adapter without a silent fallback.** Today `ProtocolSelector` can fall back quietly and the default protocol is unchanged unless `pkb_protocol=playwright`. The plan locks Playwright as the default for Chromium, Firefox, and WebKit, and forbids a silent switch to Selenium. On this branch, a session whose resolved channel is `chrome`, `msedge`, `chromium`, `firefox`, or `webkit` uses Playwright. `safari` uses Selenium `safaridriver`. `pkb_protocol=selenium` forces Selenium. A failed Playwright connect fails the run with `error:` and `fix:`. It does not open Selenium. Existing configs that do not set `browser.channel` keep the current `pkb_protocol` resolution until R8 writes the channel into the shared block. R8 updates the consumer headless config explicitly so `@all` does not change adapter by surprise. Release notes state the new default.

8. **"The spine never imports Cucumber or Playwright" versus annotations that are Cucumber types.** Consumer glue keeps `@io.cucumber.java.en.Given` (and `When`, `Then`, `And`, `But`) as a single string literal. Those annotations stay. The native runtime stops calling `ExpressionFactory`. `StoreStepCatalog` continues to read the literal from source text. Ingest translates a Cucumber expression into a stored regex plus a `parameter_types` JSON column. `PatternMatcher` matches at runtime. The cucumber adapter (`tools.dscode.engine.adapter.cucumber`) may still use `ExpressionFactory` so legacy `pkb_engine=cucumber` behavior does not move. `StepRegistry` splits: the native method has no `io.cucumber` import; the adapter file keeps the import. A test scans the native engine class files and fails on `io.cucumber` or `com.microsoft.playwright` outside `tools.dscode.engine.adapter`.

9. **MCP hot path versus Workbench isolation.** "MCP calls Java directly" must not put pickleball classes into `pickleball-workbench`. The Workbench process stays a client. Its MCP server stops shelling out. It sends the verb over the existing authenticated control protocol to the consumer worker / launcher JVM. That JVM calls the same Java method the CLI calls. `pkb_mcp_transport=cli` is the debug path that shells out. The default is `direct` (protocol to the shared method, no shell). There are no MCP-only verbs. A verb that is not a CLI verb is not registered.

10. **Swing versus a Node workbench.** The plan replaces the Swing window. It does not put a browser engine inside the controller jar, and it does not download Playwright's Chromium for the shell. The window is the existing popup pattern: driver-bundle Node serves the UI on loopback, and `PopupBrowserLocator` opens the machine's Chrome or Edge as an app window on `127.0.0.1`. No Chromium still yields `no-chromium`, and the CLI still works. Swing `WorkbenchFrame` is removed in the same phase that the Node window covers the same verbs. The nested workbench jar stays free of pickleball classes and free of `node_modules`. UI assets are exploded by the launcher from `META-INF/pickleball/workbench-ui/` in the pickleball jar, not from the nested workbench jar.

11. **One-shot dashboard versus streams.** The plan's open question once said a persistent socket could be a later optimization, and it also said that optimization was optional. This program does not leave an optional mode. Dashboard and ordinary verbs are one process invocation against the warm JVM when `spine start` is up, and a cold JVM when it is not. The locked stream exceptions stay long-lived: `browser-events --follow`, `observe --follow`, `log tail --follow`, `domengine session log --follow`, `chat --follow`, and `record --follow`. They are CLI verbs that do not exit until the stream ends or `--once` is set. There is no third transport.

12. **Code tab "later" and the ML pass "later".** Both phrases in the plan are settled as scope, not as deferrals. The code tab ships in R5 with Monaco. The "later ML pass" is satisfied by the SQL view `exception_clusters`. No model is trained and no ML dependency is added. Reason: the plan says the principle is that rows are joinable, and an ML runtime would be a new download and a new license. The view is the flagging surface.

13. **`pkb_callpath` and `pkb_componentpath`.** Ingest stores them as attributes. Their meaning stays project-relative (`src/test/resources/calls`, `src/test/resources/component`). Do not apply the `wd-file:` prefix rule to those two keys in this program.

14. **Gherkin stays.** Empty flag steps become real behavior. They are not deleted. Context words that the control engine can already evaluate are translated at ingest onto those relations. They are not dropped. New public step text is a single string literal on the existing annotation.

15. **Secrets.** Deltas, journals, conflict discards, node replies, and run snapshots store references (`<password:...>`, `<entity:Type#id.field>`, `~MASK;`). They never store resolved cleartext. The node seam's `secret` function returns a reference or `~MASK;`.

## Settled questions

### Runtime snapshot SQLite (MAJOR, and Open Item)

Two databases, with different jobs.

- The project source database stays the hydrated projection (pins, deltas, heads, files, scenarios, steps, definitions, entities).
- `runstate.db` gains snapshot tables. A run does not copy the source database. It records `commit_sha`, `head_id`, `sandbox`, the enabled-set hash, and foreign keys to entity identities and content hashes. Canonical HTML and screenshots stay files referenced by `artifacts` (path plus sha256). Re-execution lazy-loads that head through `UniversalEntity`. Reason: a full copy per run duplicates every hydrated file, and one file per run makes dashboard joins into file scraping. The plan requires ordinary SQL joins.
- A sandbox run stores `parent_commit`, `parent_head_id`, and `sandbox` name. Promote writes a new head on the parent. Old run rows are not rewritten.
- Retention is only `store retain` and `store history retain`. Defaults: `--days` 14 (new property `pkb_store_retain_days`, distinct from `pkb_store_changes_days`) and `--max-bytes` 2 GiB (`pkb_store_retain_bytes`) applied to snapshot payload bytes. Heads that a retained run still points at are kept. Deltas older than the window of 100 are folded, then the individual rows past the window move to `delta_archive` in `runstate.db` and drop from the source database. No background deleter.

### Playwright fixture and page context (was ON HOLD)

Fixture *definitions* (name, option versus auto, dependency names, source text) hydrate at ingest into `fixtures`. Fixture *instances* and the live `page`, `context`, and `browser` are created at execution by the adapter. The row stores the fixture name and a handle minted at run start. The spine never holds a Playwright object. Reason: a page is a live browser. Freezing it at ingest is meaningless. Static source is a row. Dynamic state is the adapter.

### Persistent socket versus per-command spawn

Per-command invocation is the contract for dashboard reads and ordinary verbs. The warm JVM (`spine start`) removes the startup tax without a standing socket for those verbs. The stream verbs listed under conflict 11 stay open. No optional socket mode is added later. Reason: the plan already chose spawn, and an unscheduled "later optimization" would be a deferred item.

### Verb inventory (Open Item)

One registry, `tools.dscode.launcher.VerbCatalog`, built from the parser sets that `WorkbenchCliCoverageGateTest` already walks, plus `StoreCli` and `DomEngineMain`. `engine inventory` prints it. Categories are `cli`, `stream`. Every stream verb is also a CLI verb. The `mcp-only` set is empty. A unit test fails if any MCP registration is missing from the CLI sets, or if any CLI verb is missing from the catalog. The committed copy is `docs/verb-inventory.md`, generated by the test's checked-in expectation the same way `workbench-cli-inventory.json` is checked. A hand-edited inventory that drifts fails `VerbCatalogTest`.

Direct MCP and the CLI call one Java method. The method is named on the catalog row. New Node pages register a verb by adding it to that catalog. A page that calls a private HTTP route which is not a verb fails `VerbCatalogTest`.

### Warm classloader (Open Item)

Class: `tools.dscode.engine.runtime.WarmClassLoader`, a child of the spine loader. Parent-last only for packages named by the ingested glue attribute (`pkb_warm_packages` overrides the list). `tools.dscode`, `dev.domengine`, and `io.cucumber` are always parent-first and are never reloaded. One loader per snapshot id, held by `ExecutionContext`, removed and `close()`d on replace. Not stored in a `ThreadLocal`.

`tools.dscode.store.GlueStaticScan` uses the JDK 21 `java.lang.classfile` API. A static field that is not a compile-time constant produces one warning at ingest: `warn: static mutable field <Class>.<field> will survive a warm reload; move it to scenario state`. The warning does not fail the hydrate. Existing injected state keeps working.

Correctness does not depend on the warm JVM. `pkb_warm` defaults to `off`. `mvn test` and CI stay cold. `spine start` is the dev process. Tests cover the same scenario cold and warm. A dependency change (new Java library, or a new bare npm import) is not a reload. It forces a full compile and a new JVM. `StoreGlueCompiler` already compiles Java glue. R8 drives incremental compiles through `javax.tools.JavaCompiler` on that class. Maven and Gradle stay the CI build and the first full compile.

### Node workbench, tabs, and dynamic mode (Open Item)

Node is the renderer. Java is the control plane. The workbench is side by side with the real browser session. It is not a custom browser and it does not own the page DOM. Tab order, most prominent first: Scenarios / Tests (business language, step expands to the Java, TypeScript, or JavaScript definition), Globals / Run Vars / Configs, Data, Raw SQL. The branch selector is on every tab and changes the title bar and the accent color. Selecting a finished run adds Map, logs, and screenshots for that run only.

Dynamic mode forks a delta snapshot off that run's universal entity the moment the operator enters it (`branch fork --from-run=`). The completed run is immutable. An empty fork is discarded on exit. Map edits write a pre-step hook on that fork: `NativeScenarioRun` applies the hook to the step's `ParsingMap` immediately before the step, children inherit, parents and later steps do not. Step-definition edits on the fork are rows on the fork. Logs for the new activity attach to the fork. Promote (`branch promote --to=parent|top`) is the only upward move. The name is promote. At promote time `--logs=clear|archive` is required; there is no silent default. Parent links between branches are optional metadata columns, not the resolution chain. Each branch has its own delta chain.

Chat exists from controller start, before any scenario, in `ChatLog`. Browser chat is a mode of that panel (`--session=`), not a second store.

### Selenium BiDi surface (Open Item)

`SeleniumBidiEvents` already subscribes, when `pkb_scenario_events=on`, to console entries, JavaScript exceptions, `beforeRequestSent`, `responseCompleted`, and `fetchError`. That set stays the event surface. It does not grow in this program.

Opt-in command verbs, off unless `pkb_selenium_bidi=on`:

- `bidi script` calls Selenium's browsing-context script on the current session. Entry: new methods on `tools.dscode.browser.SeleniumBidiCommands` (new class next to `SeleniumBidiEvents`).
- `bidi intercept` is the Safari leaf for replacing a response body. Playwright sessions do not use it. Playwright body changes go through `RouteCommands`.
- `bidi grid-attach --url=` builds a `RemoteWebDriver` through the existing `DriverConstruction` remote path.

Raw `bidi --method` is not an agent verb. `AgentProtocolController` remains the spine escape hatch. One owner per debugging port. A second attach to the same port fails with `error:` and `fix:`. `Recording` stays Playwright codegen (`pw codegen`). No Selenium recorder is added.

### Other locked choices that the plan left half-specified

- **Enabled flags.** `files`, `scenarios`, `steps`, `step_definitions`, and a new `folders` table each have `enabled INTEGER NOT NULL DEFAULT 1`. Ingest sets `enabled=0` when the normalized path is outside the included feature and glue roots. The row still exists. Universal hydration stores every discovered file. Toggled-off rows remain.
- **`@pkb-ignore`.** Flipping a scenario or feature off in a `.feature` file rewrites the file through `GherkinRewrite` to add `@pkb-ignore`, and removes that tag when the row is enabled. Folder-off writes the tag on the `Feature` line. The native engine skips `enabled=0` and does not parse the tag at runtime. The tag exists so a legacy Cucumber run, which still understands tags, skips the same rows.
- **Tags.** New table `scenario_tags(file_path, scenario_ord, tag)`. Runtime filters read that table. The existing `scenarios.tags` text column stays as the display cache, written in the same transaction.
- **Paths.** `files.norm_path` is the per-OS normalized form (Windows: backslash, lowercase drive letter) plus `folder_key`. The engine does not open it. Extract and the node host are the boundaries that turn a stored path into a filesystem path, and only when materializing.
- **Run variables.** Table `run_variables(name, value, enabled, source_file, content_hash)`. Secret values are references. Edits on `main` sync back to the source properties or YAML file. Sandbox edits do not.
- **Conflict choice.** `store conflicts show <path>` prints both texts. `store conflicts choose <path> --keep=db|file` keeps one side. The discarded text goes to `.pickleball/store/discarded/<utc>-<path>`. On the next JVM start, `StoreConflictChoice` deletes a discarded file that is older than 24 hours or that was written by a previous process. No merge library.
- **Undo and redo.** Undo appends an inverse delta. It does not delete history. `schema_meta.delta_cursor` is the current position. Redo reapplies. The window of 100 counts stored deltas after folding, not the cursor.
- **Report/script file.** Extension `.pkb.json`. JSON, so it diffs and needs no new parser. Fields: `format` = `pickleball.report-script`, `formatVersion` = 1, `commit`, `headId`, `sandbox`, step references, artifact references. `store run --script=<file>` loads that head and runs. `store report export --html` writes a read-only HTML file that is not runnable. The HTML file does not contain secrets.
- **Business entities.** One new step in `ObjectRegistrationSteps`, a single string literal: `@Given("^RECORD ENTITY: \"([^\"]*)\" as ([A-Za-z][A-Za-z0-9]*)#([A-Za-z0-9._-]+)$")`. It writes `entities` and `entity_usages` with operation `mention`. The value is stored only when it is not a secret field. Secret fields store `~MASK;`.
- **Exceptions.** New `tools.dscode.common.exceptions.PickleballException extends RuntimeException implements PickleBallExceptionInterface`. Fields `severity` (`info`, `warn`, `error`, `fatal`) and `scope` (`universal`, `environmental`, `entity`). `StatusException` and `StepCreationException` extend it. The interface name is not renamed. Default scope is `entity` when the running step has an entity usage, otherwise `universal`. Scope `environmental` is set only when the run attribute `environment` is non-blank (`pkb_environment`, or the new step `MARK ENVIRONMENT: "<text>"` on `MetaSteps`). The snapshot row copies severity, scope, exception class name, and a signature that is the class name plus the first non-pickleball stack frame. View `exception_clusters` groups by signature, scope, and hour. No ML library.
- **Cross-cutting store.** New file `%USERPROFILE%\.pickleball\spine\cross.db` on Windows, `~/.pickleball/spine/cross.db` elsewhere. Override `pkb_cross_store`. Schema key `cross_schema=1`. Opened on demand by `tools.dscode.launcher.CrossStore`. It survives deletion of a project. It holds agent notes, the session journal, and cross-project links (project path hash, commit, head id, run id). It does not hold scenario bodies. CLI verb family `cross` (`note add|list`, `journal list|retain`, `link add|list`), dispatched by `PickleballWorkbenchLauncher` next to `store`. `pkb_journal=off` disables journal writes. Default is on. Args are stored as a sha256, never as raw text.
- **A/B.** `store run --script=<file> --head=<headId>` runs the same script against another head. The old run row stays. The new run row points at the new head. `store diff --run=<a> --run=<b>` reports step-definition content-hash changes and a missing definition as `break`, not as a skip.
- **Dashboard and ReportPortal.** The spine dashboard is `DashboardCli` / `DashboardHtml` over the run-state SQL joins, plus the static HTML export. It is not a ReportPortal server and this program does not add a ReportPortal backend. The existing `com.epam.reportportal:client-java` client stays as it is today. GitHub Pages hosting means the user publishes the exported HTML. Reason: the plan's requirement is SQL joins and a rewindable timeline, which the run-state tables already sit next to. A second backend would be a new service, a new login model, and a new license review.
- **Frame policy (6.1.5).** `browser.framePolicy` is `top` (default), `iframe`, or `all`. Default `top` matches current top-document finds. A consumer that wants the iframe as the default sets `iframe` in YAML. Ingest stores it as run variable `frame_policy`. This does not change phrase context words. Those are R2.
- **Chat channel (6.5.4).** Default channel is the surface session id. `pkb_chat_channel=run` restores the single run channel. Release notes call the default change out.
- **Snap-backed find index (6.3.1).** Table `snap_find_index` in `runstate.db`: `run_id`, `head_id`, `identity_chain`, `handle`, `role`, `name`, `backend_node_id`. `find` consults it when the live document version equals the snapshot's document version, and otherwise uses the live query. The index is not a second tree.
- **UIA cache (6.7.3).** `DesktopTree` keeps one cached tree per attached window id inside the warm spine, size-capped, dropped on a structure-changed event and on `pkb_warm=off` (every call reads through). The resource gate can refuse a cache that would cross the heap line.
- **Desktop screenshot (6.7.4).** New CLI `desktop screenshot --name=<window>` calls `DesktopScreenshot.capture` with the window rectangle from `WindowsUia`. The Linux fix text in that class must stop recommending `grim`. Wayland remains unsupported, matching the control-engine rule. Windows is the tested path.
- **Desktop launch (6.7.1).** `desktop launch --exe=<path> --name=<window>` starts the process and attaches. Windows only is tested. Other operating systems return `error:` and `fix:` and do not pretend to launch.
- **Body modify (6.6.5).** `RouteCommands` action `modify` applies `rule.body` as the continued request body: Playwright `Route.ResumeOptions.setPostData`, CDP `Fetch.continueRequest` `postData`, BiDi `continueRequest` body. `fulfill` stays the response-body path.
- **`pw` inside execute-steps (6.6.6).** `WorkbenchSessionCommands.executeStep` and the `execute-steps` file runner send a line whose first token is `pw` through `PwCommand` instead of the Gherkin runner.
- **Shared element record and one envelope (6.8.1, 6.8.5).** Every JSON reply, including `store --json` and domengine JSON, is an `AgentEnvelope`. The previous store object moves under `items`. That is a breaking change and it is in this program. One element shape is built only by `tools.dscode.common.agentfast.ElementRecord`: `handle`, `identity.chain`, `role`, `name`, `tag`, `bounds`, `backendNodeId`, `frame`. Extra keys go under `extra` and are omitted unless `--fields` asks.
- **`@args.json` (6.8.6).** Every verb accepts `@<file>` or `--args-file=<file>` whose JSON object is the flag map. MCP passes that object into the same parser. Unknown keys fail.

## Schema this program adds

Source database, still `store_schema=1`:

- Columns on `files`: `enabled`, `norm_path`, `folder_key`.
- Columns on `scenarios`, `steps`, `step_definitions`: `enabled`.
- Columns on `steps`: `max_iterations`, `timeout_seconds`, `retry`, `ignore_failures`, `log_failures_continue`, `run_if_finished`, `always_run`, `run_if_passing`, `run_if_failed`, `run_if_hard_failed`, `run_if_soft_failed`, `context_mode`, `context_scope`, `until_mode`.
- Columns on `step_definitions`: `parameter_types`, `host` (`java`, `js`, `ts`, `playwright`).
- Tables: `pins`, `deltas`, `heads`, `head_rows`, `folders`, `scenario_tags`, `run_variables`, `fixtures`, `delta_cursor` is a `schema_meta` key rather than a table.
- `schema_meta` keys: `universal_entity=1`, `delta_window=100`, `jar_hash`, `delta_cursor`.

`runstate.db`: `run_snapshots`, `run_snapshot_entities`, `run_attributes`, `snap_find_index`, `delta_archive`, plus columns on `runs` for `commit_sha`, `head_id`, `parent_commit`, `parent_head_id`. Existing columns stay.

`cross.db`: `notes`, `journal`, `links`, `schema_meta`.

Cow sandbox file: same path as today. `schema_meta.sandbox_mode` is `cow` or `copy`. Cow files store delta rows and a `parent_name` in `_pkb_branch`. Head tables are materialized.

## Distribution and licensing

Pickleball is Apache-2.0. The published POM already depends on, and does not shade:

- `com.microsoft.playwright:playwright:1.65.1`, `driver:1.65.1`, `driver-bundle:1.65.1` (Apache-2.0). `driver-bundle` is about 229 MB and contains a Node binary per platform. Dan verified the binary this project resolves as Node 24.22.1. The Node.js license (MIT-style, plus Node's own third-party notices) already applies to that artifact. This program does not copy that binary into pickleball's jar, does not strip Node's license files out of the bundle, and does not add a second Node distribution. `PLAYWRIGHT_NODEJS_PATH` still overrides the binary. First use may unpack the already-downloaded driver jar into Playwright's driver cache. That unpack is not a network call. `NoRuntimeDownloadTest` is extended to prove the new node spawn does not contact `registry.npmjs.org` or `playwright.azureedge.net`.

- `org.xerial:sqlite-jdbc:3.47.2.0` (Apache-2.0), already a runtime POM dependency. SQLite itself is public domain. No bump, no shading change, no encryption.

npm is build-time only, and only for the workbench UI (R5). R3 ships a hand-written `step-host.mjs` as a jar resource and does not run npm. R5 adds `workbench-ui/package.json` and a committed `package-lock.json`. The Gradle task runs the driver-bundle `node` against a checksum-pinned npm CLI tarball downloaded at build time into the Gradle cache (not committed, not packaged). `frontend-maven-plugin` is not used. `npm ci` then installs exactly the lockfile. Allowed licenses for those packages: MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, and the public domain. The pinned set is `monaco-editor` (MIT) and `typescript` (Apache-2.0). No React, no Electron, no `tsx`, no `axe-core` (MPL-2.0 stays out of this bundle). The UI server is `node:http` in our own `server.mjs`. The resulting production `node_modules` is packaged at `META-INF/pickleball/workbench-ui/` inside the pickleball jar and exploded beside the run by the launcher. It is not committed. End users do not run npm, do not have Node on `PATH`, and do not see a download. The launcher sets `NODE_PATH` and the script path. Registry override for an air-gapped build: `pkb_npm_registry` (passed as `npm --registry`). A failed checksum or a disallowed license fails the build with `error:` and `fix:`.

Runtime browser downloads stay off unless YAML `browser.source` is `cdn` or `any`. The host override is Playwright's `PLAYWRIGHT_DOWNLOAD_HOST`, set from run variable `pkb_browser_cdn_host` when that variable is non-blank. Default `local` never downloads. This is the only network download the spine is allowed to start, and only under that setting.

`THIRD-PARTY-NOTICES.md` gains the Monaco and TypeScript entries in the R5 phase that packages them, including the license and the fact they sit in the pickleball jar as resources. The Node binary entry stays the existing driver-bundle paragraph.

Dan approves this section before a Build session starts R3 (spawning the bundled Node for step definitions) or R5 (npm at build time and packaging `node_modules`). R1 and R2 do not need that approval. The decisions above are the proposal. They are not optional alternatives inside the roadmap.

## Ordering

Each roadmap lands on its own. Later roadmaps call earlier entry points. They do not reimplement them.

1. **R1** builds the universal entity, incremental and JAR-hash hydration, enabled flags, tags, run variables, `@pkb-ignore`, conflict choice, and cow sandboxes. Nothing else can store a head id until this exists.
2. **R2** makes the native engine read those rows and moves Cucumber to ingest plus the legacy adapter. Step ceilings, the watchdog, flag steps, inline `until`, and phrase context words land here because they are the runner.
3. **R3** adds Java, JavaScript, TypeScript, and Playwright-test hosts on top of the row model. The node process is an adapter behind `ForeignStepHost`.
4. **R4** makes every capability a CLI verb, points MCP at the same methods, and publishes the inventory. Reply shape, `@args.json`, route body modify, `pw` in `execute-steps`, desktop launch, and desktop screenshot land here because they are surface gaps, not a new engine.
5. **R5** replaces the Swing window with the Node renderer, overlay, chat, and multi-agent addressing. It consumes the R4 verbs. It does not add a second control path.
6. **R6** is dynamic mode, fork, the Map tab, and promote. It needs R1 deltas and the R5 window.
7. **R7** freezes runs into `runstate.db`, adds the report/script file, retention, the cross-cutting store, the find index, and the session journal.
8. **R8** adds the warm classloader, the resource gate, the browser version ladder, the Selenium Safari leaf, the warm CDP pool, and the UIA cache. Full regression runs once, at the end of R8, on Windows only.

Linux and macOS test gaps stay in `docs/tech-debt.md`. That section is the one item this program does not execute. New Windows-only classes are appended there when a phase adds them. The eight step-engine entries and the nineteen consumer-fixes 6.x entries stay described in `docs/tech-debt.md` until the phase that changes the behavior rewrites that paragraph in place. The index maps every one of them to a phase. None stay unscheduled.

## CLI-first and logging

Every new capability is a CLI verb first. Workbench buttons, MCP tools, and Node pages call that verb's Java method. Logging uses the loggers that already exist:

- `StoreLog` via `pkb_store_log`: add capabilities `delta`, `enable`, `conflict`, `pin`. File `.pickleball/store/store.log`. Date stamp, entity filter `pkb_store_log_entity`, 1 MiB cap, sandbox name on each line.
- `EngineLog` via `pkb_engine_log`: add capabilities `ingest`, `match`, `watchdog`, `host`, `gate`. File `.pickleball/engine/engine.log`. Same stamp, entity filter, and cap.
- `BrowserLog` via `pkb_browser_log`: add entity `resolve`.

No new logger class and no new log file. Off unless the capability is named.
