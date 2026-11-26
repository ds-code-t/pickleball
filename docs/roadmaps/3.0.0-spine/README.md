# 3.0.0-spine roadmaps

Index for implementing `docs/plans/2026-11-25-sqlite-spine-plan.md`. The settled design is `docs/plans/2026-11-25-spine-design.md`. Work and push only on `3.0.0-spine`. Never touch `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Every phase in every roadmap is mandatory and must be completed in full. Nothing is optional or deferred. The one exception is Linux and macOS testing, which stays in `docs/tech-debt.md` (Dan: test on Windows only).

Each phase runs targeted tests only for what that phase changed. The full regression runs once, after R6 and the merge of R8, as described in [Final regression](final-regression.md). Testing is on Windows only: the final regression runs only on Dan's Windows PC, with no Linux or macOS runs and no GitHub Actions runs.

## Order

Later roadmaps call earlier entry points. They do not reimplement them.

| Order | Roadmap | Depends on | Lands |
|---|---|---|---|
| 1 | [R1 Core hydration spine and universal entity](R1-core-hydration-spine-universal-entity.md) | nothing | Pin, deltas, head, incremental hydrate, JAR hash, enabled flags, tags, run variables, `@pkb-ignore`, conflict choice, cow sandboxes |
| 2 | [R2 Cucumber as adapter, decoupling and ingest](R2-cucumber-as-adapter-decoupling-ingest.md) | R1 | Ingest translation, row-only native runs, ceilings, watchdog, flag steps, inline until, phrase context |
| 3 | [R3 Step-definition language adapters](R3-step-definition-language-adapters.md) | R1, R2 | Java host seam, Node step host, JS and TS definitions, Playwright test ingest |
| 4 | [R4 CLI and MCP universal surface](R4-cli-mcp-universal-surface-verb-inventory.md) | R1, R2, R3 | Shared entry points, verb catalog, one reply envelope, route body, desktop launch and screenshot |
| 5 | [R5 Workbench one control plane](R5-workbench-one-control-plane.md) | R4 | Node renderer, overlay, chat, multi-agent addressing, Monaco |
| 6 | [R6 Dynamic mode, branching, and promote](R6-dynamic-mode-branching-promote.md) | R1, R5 | Fork, Map tab, pre-step hook, promote |
| 7 | [R7 Runtime snapshots, retention, and the cross-cutting store](R7-runtime-snapshots-retention-cross-store.md) | R1, R2 | Frozen run rows, report/script file, retain, `cross.db` |
| 8 | [R8 Warm engine, resource gate, and browser config](R8-warm-engine-resource-gate-browser-config.md) | R1, R3, R4, R7 | Classloader, heap gate, version ladder, Safari leaf, warm CDP, UIA cache, full regression |

Scoreboards: `docs/maintainer/3.0.0-spine/R<n>-scoreboard.md`. Build prompts: `prompts/build-3.0.0-spine-R<n>.md`.

## Rules on every roadmap

- Fetch and rebase before every push. Push after every phase. Run each git command on its own. Never chain with `&&`, `;`, or a pipe. Never push to `master`.
- Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. If a phase changes surface-asset bytes, update the hash assertion in `tools.dscode.browser.SurfaceAssetsTest`.
- Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals. Never run `./gradlew --stop` or `.\gradlew.bat --stop`.
- Docs describe only the current implemented design and are rewritten in place. History goes in `docs/release-notes.md`.
- Record the implementation SHA on that roadmap's scoreboard after each phase.
- CLI-first: every capability is a CLI verb. GUI, MCP, and the Node renderer call the same Java method. Logging is toggleable, per entity, date-stamped, and size-capped, through `StoreLog`, `EngineLog`, or `BrowserLog`.
- Do not change `pkb_callpath` or `pkb_componentpath` path grammar.
- `store_schema` stays 1. New tables and columns are additive.

## Plan bullets

One row per bullet in `docs/plans/2026-11-25-sqlite-spine-plan.md`, in file order. The phase is where that bullet becomes true. A note names an earlier phase when the work is split and this row is the completing phase.

| Section | Bullet | Phase | Note |
|---|---|---|---|
| Guiding philosophy | Spine is self-enclosed logic that runs itself | R2 P2 | Rows come from R1. The native engine stops walking paths and classpath here. |
| Guiding philosophy | Everything external is an adapter | R2 P1 | Cucumber, Playwright, Gherkin, and host languages sit outside `PickleballEngine`. |
| Guiding philosophy | The spine runs any text as business language and matches it with a regex | R2 P1 | `PatternMatcher` matches stored patterns. Origin format is not read at runtime. |
| Guiding philosophy | Step definitions may be Java, TypeScript, or JavaScript | R3 P3 | Java seam is R3 P1. The Node host is R3 P2. All three execute in R3 P3. |
| Guiding philosophy | Adapters are the only external surface. The spine does not import Cucumber, Playwright, or a host framework at runtime | R3 P2 | Cucumber ban lands in R2 P2. The combined class-file ban, including Playwright, lands here. |
| Guiding philosophy | CLI is the universal capability surface. A GUI handler is one call to that command | R4 P1 | |
| Guiding philosophy | MCP is the structured agent transport. Humans use CLI or the Workbench | R4 P1 | Hot path is the shared Java method. `pkb_mcp_transport=cli` remains the debug shell-out. |
| Guiding philosophy | Bidirectional streams are the named exception to one-shot commands | R4 P2 | The stream set is fixed in the design. No third transport. |
| Guiding philosophy | Hydration is the operating model. Git holds source. SQLite is gitignored and is not the Git artifact | R1 P2 | Path stays `.pickleball/store/`. It is not moved to `target/` or `build/`. |
| Guiding philosophy | At execution the engine sees only hydrated rows | R2 P2 | |
| Guiding philosophy | Bidirectional sync is the default on main | R1 P2 | Sandbox edits do not sync until an explicit promote onto main or `store sandbox extract`. |
| Guiding philosophy | Every entity is an editable SQL row, one at a time or by folder | R1 P2 | |
| Universal control | Headless core. A window is optional presentation | R4 P1 | |
| Universal control | An agent may compose a transient page for one question | R5 P5 | The page is served by the Node renderer and dies with the session. |
| Universal control | A generated page reads and writes only through verbs | R5 P5 | `VerbCatalogTest` fails a private route. |
| Universal control | Session ids and agent ids are built in so concurrent agents do not collide | R5 P2 | |
| Locked decisions | Cucumber is a legacy adapter, not core execution | R2 P1 | |
| Locked decisions | Glue paths, feature paths, and run variables are translated at ingest | R2 P1 | `pkb_callpath` and `pkb_componentpath` keep their historical grammar. |
| Locked decisions | Script origin does not matter. A step is a step | R2 P1 | |
| Locked decisions | Step definitions use regex annotations. Cucumber is not required | R2 P1 | Java annotations stay `io.cucumber.java` string literals. The native matcher does not call `ExpressionFactory`. |
| Locked decisions | The same annotation shape runs on TypeScript and JavaScript | R3 P3 | |
| Locked decisions | Playwright test definitions run on the same substrate | R3 P4 | |
| Locked decisions | Interchangeable means different ingest shapes, not a conversion into Gherkin | R3 P4 | |
| Locked decisions | SQLite is the source of truth for a run, with provenance and original positions | R1 P1 | `pins` stores the commit. Line and path stay on the existing columns. |
| Locked decisions | Runtime execution does not resolve an OS path or a classpath | R2 P2 | |
| Locked decisions | Paths are normalized per OS at ingest and stored as attributes | R1 P2 | `files.norm_path`, `folder_key`. |
| Locked decisions | Glue path is just an ingested attribute | R2 P1 | |
| Locked decisions | Java source and class files stay references. JSON, XML, and YAML are inlined | R1 P2 | Today's `binary_ref` rules stay for large binaries. |
| Locked decisions | A Cucumber-looking start hydrates, then executes from rows | R2 P2 | |
| Locked decisions | TypeScript and JavaScript call fixed Java functions and never see a Java object | R3 P2 | Seam: `resolveText`, `saveValue`, `getValue`, `softFail`, `secret`, `log`, `entityGet`. |
| Locked decisions | No MapDB. SQLite for durable rows, Java objects for live state | R3 P2 | |
| Locked decisions | The driver-bundle Node executes TypeScript and JavaScript. The spine does not know Node exists | R3 P2 | `ForeignStepHost` in the engine. `NodeStepHost` in the launcher, next to `PlaywrightNode`. |
| Locked decisions | Node is the human-facing UI. npm is build-time only. The end user installs nothing | R5 P1 | Packaging is the design's Distribution section. Dan approves that section before R5 Build. |
| Locked decisions | MCP tools hit Java directly on the hot path | R4 P1 | The Workbench process still does not load pickleball core. It uses the control protocol. |
| Locked decisions | MCP and CLI share one Java entry point | R4 P1 | |
| Locked decisions | A new Node surface registers against the verb map | R4 P2 | |
| Locked decisions | Every hydrated entity has an enabled flag. Folders toggle as a group | R1 P2 | |
| Locked decisions | `@pkb-ignore` round-trips a disabled legacy scenario | R1 P2 | `GherkinRewrite`. |
| Locked decisions | Run variables are rows, editable, and synced on main | R1 P2 | `run_variables`. Secrets stay references. |
| Locked decisions | Tags are queryable SQL attributes | R1 P2 | `scenario_tags`. Runtime does not parse the tag text. |
| Locked decisions | A sandbox is an isolated copy. It does not sync unless told | R1 P4 | Default `--mode=cow`. `--mode=copy` keeps today's independent database. |
| Locked decisions | A divergent row and file produce a side-by-side choice. No merge library | R1 P3 | Discarded text is held under `.pickleball/store/discarded/` until the next start or 24 hours. |
| Locked decisions | Only changed source hashes are re-hydrated | R1 P2 | |
| Locked decisions | A changed pickleball JAR hash wipes the hydrated store and does a full re-hydrate | R1 P2 | Source projection only. `runstate.db` is kept. |
| Locked decisions | A warm JVM reloads Java step definitions with a custom classloader. CI does not need it | R8 P1 | `pkb_warm` defaults off. |
| Locked decisions | Three swappable layers, and a GUI can browse the logical tree or the file tree | R5 P1 | Both trees are stored in R1 P1. This phase is the browser for them. |
| Locked decisions | Everything in the project folder is hydrated. Presence and execution are separate | R1 P2 | |
| Execution model | Three layers separate the warm spine, the per-run context, and cross-project memory | R8 P2 | Layer 3 is R7 P4. Layer 1 is R8 P1. The pool and the gate close the model here. |
| Execution model | Layer 1. One long-running JVM. Maven or Gradle only when Java source must compile | R8 P1 | |
| Execution model | Layer 2. Each snapshot has its own context. Heavy work can use a subprocess pool | R8 P2 | Pool size is `pkb_worker_pool`, default 1. Not a hard-coded constant. |
| Execution model | Layer 3. A SQLite file outside the project and outside Git | R7 P4 | `%USERPROFILE%\.pickleball\spine\cross.db`. |
| Resource-sampling gate | Heap, off-heap, GC, and thread counts are queryable and can refuse work | R8 P2 | `spine sample`. |
| Hot-reload | Java reload drops the old class. Static mutable state warns at ingest and does not fail the build | R8 P1 | `GlueStaticScan` is added in R3 P1 and consumed here. Incremental compile uses `javax.tools.JavaCompiler` inside `StoreGlueCompiler`. |
| Hot-reload | TypeScript and JavaScript reload by re-import in that context's module registry | R3 P3 | |
| Hot-reload | A new Java library or a new npm package is a full rebuild, not a reload | R8 P1 | |
| Hot-reload | Workbench code tabs use Monaco or CodeMirror through the bundled Node | R5 P4 | Monaco (`monaco-editor`, MIT). Not a later milestone. |
| Browser configuration | Playwright browser binaries come from the CDN cache. The host is overridable | R8 P3 | `PLAYWRIGHT_DOWNLOAD_HOST` from `pkb_browser_cdn_host`. Default path does not download. |
| Browser configuration | One YAML browser block is shared by Selenium and Playwright | R8 P3 | Added to the existing browser YAML. Not a second format. |
| Browser configuration | Version strings may be major, major.minor, or major.minor.patch | R8 P3 | `BrowserVersionResolver`. |
| Browser configuration | `source: local` uses registered installs only and fails hard when the version is missing | R8 P3 | Never reads the Playwright cache. |
| Browser configuration | `source: cdn` uses the cache, downloads on a miss, and falls back to cache when the CDN is down | R8 P3 | |
| Browser configuration | `source: any` tries local, then the CDN rules | R8 P3 | Default when the key is present and blank is `local`, not `any`. |
| Browser configuration | Latest means the highest version the selected source allows | R8 P3 | |
| Browser configuration | Local with no version means any local browser. Local with a version means that version or a failure | R8 P3 | |
| Selenium and Safari | Selenium stays for real Safari. Playwright WebKit is not a substitute | R8 P3 | Windows tests assert the selector. A live Safari run is a macOS gap in tech-debt. |
| Selenium and Safari | No silent fallback. Playwright is the default. Selenium is the Safari and explicit leaf | R8 P3 | |
| Selenium and Safari | Opt-in Selenium verbs for specialist BiDi and Grid | R8 P3 | `bidi script`, `bidi intercept`, `bidi grid-attach`. Gate `pkb_selenium_bidi=on`. |
| Selenium and Safari | One owner per debugging port. The workbench is the port manager | R8 P3 | |
| Selenium and Safari | Direct Java CDP and BiDi remain a spine escape hatch, not an agent verb | R8 P3 | `AgentProtocolController` stays. It is not added to `VerbCatalog` as a raw method verb. |
| Selenium and Safari | Recording is Playwright codegen only | R8 P3 | No Selenium recorder. |
| Universal entity | The entity is a commit pin plus ordered deltas. SQLite, a report, and a sandbox are projections | R1 P1 | `UniversalEntity`, `pins`, `deltas`. |
| Universal entity | Unchanged portions stay lazy. Deltas override the parent, then the commit | R1 P1 | |
| Universal entity | Keep about 100 deltas. Fold older ones. Eager flatten unless the delta is marked expensive | R1 P1 | `delta_window=100`. Bulk toggle and re-import set `expensive=1` and `heads.stale=1`. |
| Universal entity | A run executes the flattened head. JAR-hash invalidation rebuilds that head from the commit and the files | R1 P2 | The head tables exist in R1 P1. The JAR trigger lands here. |
| Universal entity | Deltas past the window are archived. The head already contains their effect | R7 P3 | `delta_archive` in `runstate.db`. Folding itself is R1 P1. |
| Universal entity | Undo, redo, and revert walk the delta history | R1 P1 | Undo appends an inverse delta. |
| Universal entity | A branch is a snapshot of the entity with its own delta chain | R1 P4 | |
| Universal entity | A sandbox is copy-on-write, with tombstones, flatten-on-promote, and no required extract | R1 P4 | |
| Universal entity | Deltas live in SQL. Git versions files. The delta log does not need Git to function | R1 P1 | A fresh clone rehydrates from files and starts an empty delta log. `store status` warns `unsynced=<n>`. |
| Universal entity | A report/script file is a report and a runnable script, with its own step versions | R7 P2 | `.pkb.json`. |
| Universal entity | A DOM fragment or a screenshot is a match target, parameterizable and versioned | R7 P2 | |
| Universal entity | Scenario reports from a script run and from a walked visual session share one substrate | R7 P2 | |
| Universal entity | The dashboard is a project page with a rewindable timeline | R7 P2 | `DashboardHtml` over SQL. Not a new ReportPortal server. Static HTML is what a user can publish. |
| Universal entity | Business values recorded by a step become linked queryable entities | R7 P2 | `RECORD ENTITY:` on `ObjectRegistrationSteps`. |
| Universal entity | Linking entities, scenarios, runs, and the git-plus-delta pin is the central operation | R7 P2 | |
| Universal entity | Exception types, time of day, and bottlenecks are joinable. Pattern flagging reads those joins | R7 P2 | View `exception_clusters`. No ML runtime. |
| Universal entity | `PickleballException` carries severity and scope into the snapshot | R7 P2 | Class name is `PickleballException`. The existing interface `PickleBallExceptionInterface` is not renamed. |
| Universal entity | A/B testing points a script at another head and highlights definition and config diffs | R7 P2 | A missing definition is a `break`. |
| Open questions | Runtime snapshot shape, one DB versus many, sandbox git linkage, retention | R7 P3 | Schema and linkage are R7 P1. Retention here is the completing piece. |
| Open questions | Playwright fixture context, previously on hold | R3 P4 | Definitions at ingest. Instances at execution. |
| Open questions | Persistent socket versus per-command spawn | R4 P1 | Spawn for ordinary verbs. Streams stay long-lived. No optional socket. |
| Open questions | Build the verb inventory | R4 P2 | |
| Open questions | Custom classloader mechanics | R8 P1 | |
| Open questions | Node workbench tab layout, dynamic-mode map, side-by-side browser | R5 P1 | Dynamic-mode behavior itself is R6. The window and the side-by-side choice land here. |
| Open questions | Precise BiDi event surface for opt-in Selenium verbs | R8 P3 | Events stay the five `SeleniumBidiEvents` subscriptions. |
| Node workbench | Node replaces Swing and is a projection over the same verbs, beside the real browser | R5 P1 | |
| Node workbench | Scenarios / Tests is the prominent tab. A step expands to its definition | R5 P1 | |
| Node workbench | Globals / Run Vars / Configs is a form, including the browser block | R5 P1 | |
| Node workbench | Data tab shares a search box with SQL | R5 P1 | Exact, substring, and regex. |
| Node workbench | Raw SQL is the least prominent tab | R5 P1 | |
| Node workbench | Branch selector is visible on every tab and changes color and title | R5 P1 | Color application on fork is R6 P1. The control is on the shell here. |
| Node workbench | Selecting a finished run shows Map, logs, and screenshots for that run | R6 P2 | |
| Dynamic mode | Entering dynamic mode on a finished run forks a delta snapshot. An empty fork is discarded on exit | R6 P1 | |
| Dynamic mode | The Map tab is the ParsingMap tree for that run and step | R6 P2 | |
| Dynamic mode | Edits apply through a pre-step hook. Children inherit. Parents do not change | R6 P2 | |
| Dynamic mode | Step definitions can be created or edited on the fork | R6 P2 | |
| Dynamic mode | The original run's logs are not modified | R6 P1 | |
| Dynamic mode | Branching is explicit. Micro-edits do not each create a branch | R6 P1 | |
| Dynamic mode | Each branch has its own color | R6 P1 | |
| Dynamic mode | Each report/script file has a collapsible history tree | R6 P3 | |
| Dynamic mode | Parent links are optional metadata, not the resolution chain | R6 P3 | |
| Dynamic mode | Promote moves changes to the parent or to the top. Logs are cleared or archived at that moment | R6 P3 | The verb is `branch promote`. The storage primitive is R1 P4. |
| One control plane | The workbench, browsers, desktop, and headless runs share one Java entry point. Node only renders | R5 P1 | |
| One control plane | Chat is available before any scenario. Browser chat is a mode of it | R5 P3 | |
| One control plane | Injection is opt-in. The workbench records which sessions are injected | R5 P2 | |
| One control plane | The agent can take the lease on the workbench, on a browser, or on both. Blocking input is a verb | R5 P5 | |
| One control plane | Every command carries a session id or label | R5 P2 | |
| One control plane | The default injection is a sibling overlay. Direct DOM mutation is the fallback | R5 P2 | |
| One control plane | CDP and BiDi do the observation. A local listener remains only where the protocol is silent | R5 P2 | |
| One control plane | Session state lives in Java or SQLite. A reload re-renders from that state | R5 P2 | |
| One control plane | Each agent has its own overlay layer, addressed by agent id | R5 P2 | |
| Open items remaining | Exact BiDi event surface | R8 P3 | Same decision as the open question above. |
| Open items remaining | Runtime snapshot schema and retention | R7 P3 | |
| Open items remaining | Custom classloader concrete design | R8 P1 | |
| Open items remaining | Documented verb inventory | R4 P2 | |

## Leftovers

These are the nineteen consumer-fixes 6.x rows in `docs/tech-debt.md` (descriptions in `docs/maintainer/3.0.0-consumer-fixes/triage.md`) and the eight older step-engine entries. Each one is still current behavior until its phase rewrites that `docs/tech-debt.md` paragraph in place. None are left unscheduled. None are superseded: the plan does not replace element context, flag steps, or these surface gaps. It gives them a place to land.

| Item | What it is | Phase | Why this phase |
|---|---|---|---|
| Unused step ceiling fields | `StepBase.stepMaxIterations` and `stepTimeoutSeconds` are never assigned | R2 P3 | The native runner and the cucumber adapter read `steps.max_iterations` and `steps.timeout_seconds`. |
| Hung step watchdog | A non-repeating step never samples the time cap | R2 P3 | `StepWatchdog` marks the step failed about once a minute. It does not claim to kill a hung Selenium call. |
| Accepted steps that do nothing | `RETRY:`, `IGNORE FAILURES`, `LOG FAILURES BUT CONTINUE SCENARIO`, `RUN IF SCENARIO FINISHED`, and the `AND IGNORE FAILURES` / `AND SCENARIO FINISHED` suffixes | R2 P3 | `FlagSteps` and `GeneralSteps.retryStep` write the step-row flags. `area-b-control-flow.feature` exercises them. |
| Unsupported context words | `between`, `inside`, `within`, `of`, `on`, `near`, `next to`, `following`, `preceding` | R2 P4 | Ingest maps them onto `dev.domengine.query.Relations`. `between` is a new relation. |
| Unsupported condition word | `exists` | R2 P4 | Element operand means `is present`. Value operand means non-null. |
| Any parent context | No syntax for any parent. `AllContextsScan` must not grow a special case of its own | R2 P4 | Phrase `in any parent` sets `steps.context_scope=any-parent`. `PhraseData` is the only reader. |
| Phrase-local context | A phrase cannot opt out of `ALL_CONTEXTS` or pin `Loading` | R2 P4 | `with local context` and `with all contexts` set `steps.context_mode`. `ElementMatch.searchesAllContexts` reads it. |
| Inline until | A mid-line, comma, or period-ending `until` does not loop | R2 P3 | `PhraseData.runUntilOperation` becomes that loop, bounded by the step ceiling. |
| 6.1.5 | Shared frame policy. L13 is consumer config: the Gherkin default context is the iframe | R8 P3 | `browser.framePolicy` is `top` (default), `iframe`, or `all`. Default does not flip existing projects. |
| 6.3.1 | Snap-backed find index | R7 P2 | `snap_find_index` in `runstate.db`. Used only when the document version still matches. |
| 6.3.6 | Warm Playwright and CDP at start | R8 P4 | The pool starts only when `pkb_warm=on` and the resource gate allows it. |
| 6.5.1 | Structured chat refs and actions | R5 P3 | Chat rows gain `ref` and `action`. CLI `chat send --ref --action`. |
| 6.5.2 | A Point button | R5 P3 | Verb `point`. The button calls that verb. |
| 6.5.3 | Typed card answers | R5 P3 | `ask --type=text\|choice\|confirm`. |
| 6.5.4 | Per-session chat channels by default | R5 P3 | Default channel is the surface session. `pkb_chat_channel=run` restores the old channel. |
| 6.5.6 | Progress card | R5 P3 | Verb `progress`. |
| 6.5.7 | Overlay read receipts | R5 P3 | `read_at` on the overlay row. `overlay-poll` returns it. |
| 6.6.4 | Runtime injection on an attached window | R5 P2 | The fail-clearly path already shipped. This phase injects the sibling overlay when the session is marked injected. |
| 6.6.5 | Body-modify mocks | R4 P4 | `RouteCommands` action `modify` sends `rule.body` as the request body. `fulfill` stays the response body. |
| 6.6.6 | `pw` steps inside execute-steps | R4 P4 | `execute-step` and `execute-steps` hand a `pw` line to `PwCommand`. |
| 6.7.1 | Desktop launch | R4 P4 | `desktop launch --exe --name` on Windows. Other operating systems get `error:` and `fix:`. |
| 6.7.3 | UIA tree cache | R8 P4 | `DesktopTree` cache, dropped on structure-changed and when `pkb_warm=off`. |
| 6.7.4 | Desktop screenshot | R4 P4 | `desktop screenshot` calls `DesktopScreenshot.capture`. The fix text stops recommending `grim`. |
| 6.8.1 | One reply schema | R4 P3 | Every JSON reply is an `AgentEnvelope`. Store and domengine bodies move under `items`. Breaking, and in scope. |
| 6.8.5 | Normalised element record | R4 P3 | `ElementRecord` is the only builder. |
| 6.8.6 | `@args.json` on every verb | R4 P3 | `@<file>` and `--args-file`. MCP passes the same object. |
| 6.8.8 | Session journal | R7 P4 | `cross journal` stores the verb and an args hash, never raw args. |
| Deferred Linux and macOS tests | The existing tech-debt section, including consumer-fixes live classes that ran on Windows only | stays in `docs/tech-debt.md` | Dan: test on Windows only. Not a roadmap phase. Phases append new Windows-only class names to that section. They do not delete it. |

## CLI-first and logging checklist

Check this on every phase.

- Every capability added in the phase has a CLI verb and a line in `StoreCli.help`, `EngineCli`, `DomEngineMain` help, or `WorkbenchCliHelp`, whichever owns it.
- Workbench, MCP, and the Node renderer do not contain a second implementation. They call the Java method named in `VerbCatalog` once R4 exists. Before R4, they call the CLI's Java method directly in-process, not through a copy of the logic.
- New log lines use `StoreLog`, `EngineLog`, or `BrowserLog`. Capabilities added by this program: store `delta`, `enable`, `conflict`, `pin`; engine `ingest`, `match`, `watchdog`, `host`, `gate`; browser entity `resolve`.
- Logs stay off unless that capability or entity is named. Lines are date-stamped. The existing 1 MiB cap stays.
