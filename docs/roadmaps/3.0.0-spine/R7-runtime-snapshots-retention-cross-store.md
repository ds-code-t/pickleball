# R7 — Runtime snapshots, retention, and the cross-cutting store

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R1 and R2. R5 and R6 may already be on the branch. This roadmap does not need the Node window to land. Design: `docs/plans/2026-11-25-spine-design.md`. Scoreboard: `docs/maintainer/3.0.0-spine/R7-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R7.md`.

Every phase is mandatory and must be completed in full. Nothing in R7 is optional or deferred. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Targeted tests only until P4 is green. Do not run the full regression here. That is the last phase of R8. Windows only. Append new Windows-only class names to `docs/tech-debt.md`.

Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`. `store_schema` stays 1. `cross_schema` starts at 1.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. No surface-asset change is expected. If one happens, update `tools.dscode.browser.SurfaceAssetsTest`.

## CLI-first and logging checklist

- New verbs: `store run --script`, `store report export`, `store diff`, `store retain` extensions, `cross note`, `cross journal`, `cross link`. Workbench and MCP forward.
- `pkb_store_log` capability `pin` for snapshot writes. Entity is the run id. `pkb_journal=off` disables the journal. Default on.

## P1 — Run snapshot rows

### Functionality

Do not copy the source database per run. Extend `runstate.db` from `SqlDialect.RUN_DDL`.

New tables:

- `run_snapshots(run_id TEXT PRIMARY KEY, commit_sha TEXT NOT NULL, head_id TEXT NOT NULL, sandbox TEXT NOT NULL, parent_commit TEXT NOT NULL DEFAULT '', parent_head_id TEXT NOT NULL DEFAULT '', enabled_hash TEXT NOT NULL, started_at TEXT NOT NULL, FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE)`
- `run_snapshot_entities(run_id TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, content_hash TEXT NOT NULL, PRIMARY KEY (run_id, entity_type, entity_id), FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE)`
- `run_attributes(run_id TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (run_id, name), FOREIGN KEY (run_id) REFERENCES runs(run_id) ON DELETE CASCADE)`

Columns on `runs` if missing: `commit_sha`, `head_id`, `parent_commit`, `parent_head_id`. Added with `ALTER TABLE`. Existing rows get empty strings.

`tools.dscode.store.StoreExecution` writes the snapshot row at run start from `UniversalEntity`'s current head. `enabled_hash` is the SHA-256 of the sorted `enabled=1` entity keys. Entity rows in the snapshot are identities and hashes, not payloads. A sandbox run fills `parent_commit` and `parent_head_id` from the cow parent. Promote does not update old snapshot rows.

`schema_meta` on the run-state database: `run_snapshots=1`. Source `store_schema` stays 1.

CLI: `store history show <run-id> --json` includes `snapshot` with those fields. The existing keys move under `items` because R4 wrapped store JSON in `AgentEnvelope`. Do not add a second shape.

### Docs

`AGENTS.md` Store section: a run freezes head id and entity hashes in `runstate.db`. It does not copy `source.db`. `docs/diagnostic-reporting.md` if it says reports are the only run record: the snapshot row is the record, and the diagnostic files remain. Release notes.

### Tests

Create `src/test/java/tools/dscode/store/RunSnapshotTest.java`. A native run writes one `run_snapshots` row whose `head_id` matches `store head show`. Deleting the feature file after the run does not remove the snapshot. A cow-sandbox run stores `parent_head_id`. Promoting the sandbox does not change that parent id on the old row. The source database file size is unchanged by the snapshot insert (the insert is in `runstate.db`).

Extend `RunHistoryAccuracyTest` so `history show` JSON contains `snapshot.head_id`.

```
.\gradlew.bat :test --tests tools.dscode.store.RunSnapshotTest --tests tools.dscode.store.RunHistoryAccuracyTest --console=plain
```

## P2 — Report/script, visual targets, entities, exceptions, find index

### Functionality

Closes the report/script bullet, visual match targets, business entities, linking, `PickleballException`, the dashboard timeline, A/B, and leftover 6.3.1.

**Report/script.** `store report write --run=<id> --out=<file.pkb.json>` writes JSON: `format` = `pickleball.report-script`, `formatVersion` = 1, `commit`, `headId`, `sandbox`, `steps` as references (`definitionId`, `textHash`, `ord`), `artifacts` as relative paths plus sha256. No embedded screenshot bytes. No secrets: values that fail `StoreSecrets` are `~MASK;`.

`store run --script=<file.pkb.json>` loads that head through `UniversalEntity` and runs. `store run --script=<file> --head=<otherHead>` runs against another head and writes a new run row. The old run stays.

`store report export --run=<id> --html=<file>` writes one read-only HTML file. It is not a script. `DashboardHtml` is the builder. Add a timeline section that lists runs ordered by `started_at` with status. Do not add a ReportPortal server. Do not add a dependency.

`store diff --run=<a> --run=<b> --json` compares step-definition hashes for the scenario. A definition present in one run and missing in the other is `break`. A hash change is `changed` and includes the two hashes, not the source bodies if either side is a secret-bearing file. Config changes are `run_attributes` keys whose values differ.

**Visual targets.** A step may store `steps.visual_ref` (additive column) pointing at an `artifacts.artifact_id` whose `kind` is `dom` or `screenshot`. Playback in `NativeScenarioRun` treats a non-null `visual_ref` as a match target in addition to the regex: the artifact's stored identity chain is replayed with the existing `query --identity` path. A parameter marker `{{name}}` inside the step text is replaced from the parsing map before the match. The regex path still runs. Both must match. A missing artifact is `error:` and `fix:`, not a skip.

**Business entities.** In `tools.dscode.coredefinitions.ObjectRegistrationSteps` add one literal:

`@Given("^RECORD ENTITY: \"([^\"]*)\" as ([A-Za-z][A-Za-z0-9]*)#([A-Za-z0-9._-]+)$")`

The method writes `entities` and `entity_usages` operation `mention`, and a `run_snapshot_entities` row. Secret fields store `~MASK;`. The step is also accepted on the native engine through the R2 catalog. Add a scenario to the existing consumer feature that already records entities, or to `area-b-control-flow.feature` if no entity feature exists. Search first. Do not add a new feature file if one already covers entities.

**Exceptions.** New `tools.dscode.common.exceptions.PickleballException`. Fields `severity` and `scope` as the design states. `StatusException` and `StepCreationException` extend it and still implement `PickleBallExceptionInterface`. Do not rename the interface. `MetaSteps` gains one literal `@Given("^MARK ENVIRONMENT: \"([^\"]*)\"$")` which sets run attribute `environment`. Snapshot writer copies `severity`, `scope`, class name, and signature into `run_attributes` keys `exception.severity`, `exception.scope`, `exception.signature` when the run fails. View `exception_clusters` is created with `CREATE VIEW IF NOT EXISTS` over those attributes grouped by signature, scope, and the hour of `started_at`. No ML dependency.

**Find index (6.3.1).** Table `snap_find_index(run_id TEXT, head_id TEXT, doc_version TEXT, identity_chain TEXT, handle TEXT, role TEXT, name TEXT, backend_node_id TEXT, PRIMARY KEY (run_id, handle))`. `find` in `ElementOperations` consults it only when the live document version equals `doc_version`. Otherwise it uses the live query and does not return stale handles. A mismatch logs `pkb_engine_log=match` entity `find` line `index miss`.

### Docs

`docs/diagnostic-reporting.md`, `docs/snapshot.md` (visual target is a step column, not a new snapshot schema version), `docs/store-entities.md` (`RECORD ENTITY`), `AGENTS.md` exception paragraph, tech-debt row 6.3.1 removed. Release notes. Consumer guide that lists steps, updated in place.

### Tests

Create `src/test/java/tools/dscode/store/ReportScriptTest.java`. Write a script, run it, assert a new run row points at the same head. `--head` of another head writes a second run and leaves the first. Export HTML contains the run id and does not contain a fixture secret. `diff` reports `break` when a definition id is removed.

Create `src/test/java/tools/dscode/engine/runtime/VisualTargetTest.java`. A step with `visual_ref` and a matching identity passes. A wrong chain fails. `{{city}}` substitutes from the map.

Create `src/test/java/tools/dscode/common/exceptions/PickleballExceptionTest.java`. A thrown `PickleballException` of scope `universal` lands in the snapshot attributes. `MARK ENVIRONMENT: "off-hours"` sets scope `environmental` on a following failure. `exception_clusters` returns that signature.

Create `src/test/java/tools/dscode/common/domoperations/SnapFindIndexTest.java` (package matches `ElementOperations`'s tests; if those tests live elsewhere, put this next to them). Same doc version uses the index. A different doc version does not.

Consumer `RECORD ENTITY` scenario: publish to maven local and run that feature's tag only.

```
.\gradlew.bat :test --tests tools.dscode.store.ReportScriptTest --tests tools.dscode.engine.runtime.VisualTargetTest --tests tools.dscode.common.exceptions.PickleballExceptionTest --tests tools.dscode.common.domoperations.SnapFindIndexTest --console=plain
```

Adjust the find-index test's package to the real one before running, and record the name on the scoreboard.

## P3 — Retention

### Functionality

`store retain` and `store history retain` already take `--days` and `--max-bytes`. Wire the defaults to `pkb_store_retain_days` (14) and `pkb_store_retain_bytes` (2147483648). These are distinct from `pkb_store_changes_days`.

Retention deletes old runs and, through the foreign keys, their snapshot rows, find-index rows, and attributes. It does not delete a `heads` row in the source database that a retained run's `head_id` still names. After the delete, deltas past the window of 100 that were only folded in memory are copied to `delta_archive(seq INTEGER PRIMARY KEY, at TEXT, kind TEXT, payload_json TEXT, head_id TEXT)` in `runstate.db` and removed from the source `deltas` table. A head that a retained run points at is not rebuilt from the archive during a normal run. `store head rebuild --from-archive=<seq>` is the recovery verb and it refuses if the seq is still inside the live window.

No background thread deletes anything.

### Docs

`AGENTS.md` retain paragraph. `docs/configuration.md` properties. Release notes. Tech-debt is unchanged by this phase except the Linux/macOS appendix if you add a class.

### Tests

Create `src/test/java/tools/dscode/store/SnapshotRetainTest.java`. Two runs, one old. Retain with `--days=0` deletes the old run and its snapshot and keeps the new run's head. A head id that the new run names is still in the source database. `delta_archive` receives a folded delta and the source `deltas` table no longer has that seq. `rebuild --from-archive` of a live seq fails.

Extend `StoreHistoryTest` so the default days value read from the property is 14 when unset.

```
.\gradlew.bat :test --tests tools.dscode.store.SnapshotRetainTest --tests tools.dscode.store.StoreHistoryTest --console=plain
```

## P4 — Cross-cutting store and the session journal

### Functionality

Closes layer 3 and leftover 6.8.8.

`tools.dscode.launcher.CrossStore` opens `%USERPROFILE%\.pickleball\spine\cross.db` on Windows, override `pkb_cross_store`. The file is created on first `cross` verb. Opening pickleball does not create it until a `cross` verb or a journal write runs. `pkb_journal=off` skips journal writes and does not create the file for the journal alone.

Tables: `schema_meta`, `notes(id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, agent_id TEXT, project_hash TEXT, body TEXT)`, `journal(id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, agent_id TEXT, session_id TEXT, project_hash TEXT, verb TEXT, args_hash TEXT, ok INTEGER, code TEXT)`, `links(id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, project_hash TEXT, commit_sha TEXT, head_id TEXT, run_id TEXT)`.

`project_hash` is the SHA-256 of the project path. The raw path is not stored. `args_hash` is the SHA-256 of the canonical flag JSON. Raw args are not stored. `notes.body` is the text the agent passed. The command rejects a body that `StoreSecrets` flags as a secret.

`VerbDispatch` writes one journal row after each verb when the journal is on. A failure to write the journal logs `error:` on stderr once and does not fail the verb. Same rule as store tracking: tracking failures do not fail the scenario.

CLI, dispatched by `PickleballWorkbenchLauncher` before `store`:

- `cross note add --agent=<id> --text=<text>`
- `cross note list [--agent=<id>] [--json]`
- `cross journal list [--verb=<name>] [--limit=N] [--json]`
- `cross journal retain --days=N`
- `cross link add --commit=<sha> --head=<id> --run=<id>`
- `cross link list [--json]`

Register them in `VerbCatalog`. They are `cli`, not `stream`.

The file survives deleting a project directory. A test creates the db, deletes the temp project, and still lists the note.

### Docs

`AGENTS.md` new short subsection under Store for the cross-cutting file, including the Windows path. `docs/configuration.md` gains `pkb_cross_store` and `pkb_journal`. Tech-debt row 6.8.8 removed. `docs/verb-inventory.md` regenerated. Release notes. Do not link this path as a project store.

### Tests

Create `src/test/java/tools/dscode/launcher/CrossStoreTest.java`. Note add and list. A secret-shaped body is refused. Journal row after `store status` has the verb `store` and an args hash, and the raw flag text is absent from the file (read the sqlite bytes as text and assert the flag value is not there). `pkb_journal=off` writes no row. Deleting the project directory leaves the note. `journal retain --days=0` drops the row.

```
.\gradlew.bat :test --tests tools.dscode.launcher.CrossStoreTest --tests tools.dscode.launcher.VerbCatalogTest --console=plain
```

## Out of R7

Do not build the warm classloader, the heap gate, or the browser version ladder.
