# R1 — Core hydration spine and universal entity

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Design: `docs/plans/2026-11-25-spine-design.md`. Index: `docs/roadmaps/3.0.0-spine/README.md`. Scoreboard: `docs/maintainer/3.0.0-spine/R1-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R1.md`.

Every phase is mandatory and must be completed in full. Nothing in R1 is optional or deferred. Do not start R2 from this prompt. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Each phase runs targeted tests only. The end-of-work regression is the short (Chrome + Windows) tier in [Regression tiers](final-regression.md). It runs only after every roadmap and fix is done and verified with targeted tests. The full tier runs only when Dan asks. Windows only. Linux and macOS gaps are appended to the existing section in `docs/tech-debt.md`. Do not delete that section.

Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Do not chain with `&&`, `;`, or a pipe. Never push to `master`.

Before every push:

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

If a phase changes surface-asset bytes, update the hash assertion in `tools.dscode.browser.SurfaceAssetsTest`. R1 does not change surface assets.

Docs describe the behavior that the phase just implemented. Rewrite them in place. Put history in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale. Record the phase SHA, the test command, and the pass count on the scoreboard before the push.

`store_schema` stays 1. Do not change `pkb_callpath` or `pkb_componentpath` grammar. Do not move the database from `.pickleball/store/` to `target/` or `build/`.

## CLI-first and logging checklist

- New verbs are `store` subcommands on `tools.dscode.store.StoreCli`, help text included, dispatched by the existing switch. Workbench `StoreCliForwarder`, Gradle store tasks, and MCP `store` only forward. They do not open SQLite.
- Log through `tools.dscode.store.StoreLog`. New capabilities: `pin`, `delta`, `enable`, `conflict`. Lines are date-stamped, carry the sandbox name, and honor `pkb_store_log_entity` and the 1 MiB cap. Off unless named.

## P1 — Pin, deltas, head, undo

### Functionality

Add the universal entity as a projection on the source database. Do not create a second database.

New class `tools.dscode.store.UniversalEntity`. It loads `pins`, applies `deltas` after `schema_meta` key `delta_cursor`, and reads `head_rows` when `heads.stale` is 0. A stale head is rebuilt by `tools.dscode.store.HeadMaterializer` before a run or `store head show`.

New class `tools.dscode.store.DeltaLog`. `append` writes one row and, unless `expensive=1`, calls `HeadMaterializer.flatten` immediately. `expensive=1` sets `heads.stale=1`. `store status` and the end of `StoreHydrator.hydrate` call `HeadMaterializer.quiet()` which flattens a stale head.

DDL in `tools.dscode.store.sql.SqlDialect.SOURCE_DDL`, created with `CREATE TABLE IF NOT EXISTS`. `SqlDialect.SOURCE_TABLES` includes the new names. `schema_meta` keys written by `ensureSchema`: `universal_entity` = `1`, `delta_window` = `100`.

Tables:

- `pins(id INTEGER PRIMARY KEY, commit_sha TEXT NOT NULL, committed_at TEXT NOT NULL, dirty INTEGER NOT NULL, CHECK (id = 1))`
- `deltas(seq INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, actor TEXT NOT NULL, kind TEXT NOT NULL, payload_json TEXT NOT NULL, expensive INTEGER NOT NULL DEFAULT 0)`
- `heads(head_id TEXT PRIMARY KEY, built_at TEXT, stale INTEGER NOT NULL, through_seq INTEGER NOT NULL, jar_hash TEXT NOT NULL DEFAULT '')`
- `head_rows(head_id TEXT NOT NULL, tbl TEXT NOT NULL, pk TEXT NOT NULL, payload_json TEXT NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (head_id, tbl, pk))`

Window: after a flatten, if `deltas` has more than 100 rows, fold the oldest into the head and delete those delta rows in the same transaction. Do not archive yet. Archiving is R7. Undo appends a new inverse delta. It does not delete the undone row. `schema_meta` key `delta_cursor` is the seq the head currently reflects. Redo reapplies the following delta by appending a copy. Kind values for this phase: `put`, `delete`, `undo`, `redo`.

`pins.commit_sha` comes from the existing git read in `tools.dscode.store.StoreGit`. An empty git dir stores an empty sha and `dirty=0`, and `store status` keeps today's warning that git provenance is empty. A dirty tree sets `pins.dirty=1`.

CLI, added to `StoreCli` and `StoreCli.help`:

- `store pin show [--json]`
- `store delta list [--limit=N] [--json]`
- `store delta undo [--json]`
- `store delta redo [--json]`
- `store head show [--json]`
- `store head rebuild [--json]`

`store_log` capability `pin` for pin and head lines, `delta` for append, undo, redo, and fold. Entity is the table name.

A run is not switched onto the head in this phase. `PickleballEngine` stays as it is. This phase only stores and resolves the entity. R2 reads it.

### Docs

Rewrite the Store section of `AGENTS.md` in place so it names pins, deltas, the head, undo, and the window of 100. Update the store row of `docs/agent/feature-map.md`. Add a section to `docs/store-entities.md`. Add a release-notes paragraph. Do not document unbuilt cow sandboxes or `@pkb-ignore` yet.

### Tests

Create `src/test/java/tools/dscode/store/UniversalEntityTest.java`. Cases: a put delta overrides a parent row; a tombstone hides it; an unchanged row is not copied into `head_rows` until flatten needs it; undo restores the previous payload; redo restores the edit; the 101st delta folds the oldest and the head payload still matches.

Create `src/test/java/tools/dscode/store/DeltaWindowTest.java`. Cases: an `expensive=1` delta leaves `heads.stale=1` and does not flatten inside `append`; `HeadMaterializer.quiet` clears the stale bit; `delta_window` of 100 is the `schema_meta` value.

Create `src/test/java/tools/dscode/store/HeadMaterializerTest.java`. Cases: rebuild after a forced stale head matches a fresh flatten; `jar_hash` on the head is empty in this phase.

Extend `tools.dscode.store.sql.StoreSchemaTest` so the new tables and the `universal_entity` meta key exist after `ensureSchema`, and `SCHEMA_VERSION` is still 1.

Windows command:

```
.\gradlew.bat :test --tests tools.dscode.store.UniversalEntityTest --tests tools.dscode.store.DeltaWindowTest --tests tools.dscode.store.HeadMaterializerTest --tests tools.dscode.store.sql.StoreSchemaTest --console=plain
```

### Done when

Those tests pass, the scoreboard row has the SHA and the pass count, and the docs match the verbs above.

## P2 — Incremental hydrate, JAR hash, flags, tags, paths, run variables

### Functionality

`tools.dscode.store.StoreHashes` gains `jarHash()`, the SHA-256 of the code-source jar that contains `StoreHydrator`. A missing code source (tests running from `build/classes`) hashes the classes directory the same way `WorkingTreeDiffHash` streams bytes, without loading the whole tree into one byte array. Store the hex in `schema_meta` key `jar_hash` and on `heads.jar_hash`.

`StoreHydrator.hydrate`: when the stored `jar_hash` differs, delete rows from the source tables (`files`, `feature_docs`, `scenarios`, `example_blocks`, `steps`, `entities`, `step_definitions`, `head_rows`, `deltas`, `heads`, and the tables added below) and hydrate from files. Do not open `runstate.db` for that wipe. When the jar hash matches, skip a file whose `content_hash` is unchanged. `store status` prints `reused=<n> rehydrated=<n> jar=<12 hex chars>`.

Additive columns, via `ALTER TABLE` when `pragma_table_info` does not already list them:

- `files.enabled INTEGER NOT NULL DEFAULT 1`, `files.norm_path TEXT NOT NULL DEFAULT ''`, `files.folder_key TEXT NOT NULL DEFAULT ''`
- `scenarios.enabled INTEGER NOT NULL DEFAULT 1`
- `steps.enabled INTEGER NOT NULL DEFAULT 1`
- `step_definitions.enabled INTEGER NOT NULL DEFAULT 1`

New tables:

- `folders(folder_key TEXT PRIMARY KEY, norm_path TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)`
- `scenario_tags(file_path TEXT NOT NULL, scenario_ord INTEGER NOT NULL, tag TEXT NOT NULL, PRIMARY KEY (file_path, scenario_ord, tag))`
- `run_variables(name TEXT PRIMARY KEY, value TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, source_file TEXT NOT NULL DEFAULT '', content_hash TEXT NOT NULL DEFAULT '')`

`files.norm_path` uses Windows rules on Windows: backslash separators and a lowercase drive letter. `folder_key` is the parent relative path. `StoreDiscovery` still stores every file that passes include, exclude, and size rules, including files outside the feature and glue roots. Those out-of-root rows are inserted with `enabled=0`. In-root rows default to `enabled=1`. A disabled row is not deleted.

Tag writes happen in the same transaction as the scenario upsert inside `StoreGherkin` / `StoreHydrator`. The existing `scenarios.tags` text stays. Readers that filter at runtime are not switched yet (R2). This phase's `store query --tag` keeps today's behavior and also matches `scenario_tags`.

`@pkb-ignore`: `tools.dscode.store.GherkinRewrite` gains `setIgnored(path, scenarioOrd, boolean)`. `store enable` on a `.feature` scenario or feature calls it. Off inserts the tag `@pkb-ignore` on that scenario or on the `Feature` line for a folder of features. On removes only that tag. The file write goes through the existing extract conflict check: a both-sides edit is refused until P3's choose command. A feature file that is not valid Gherkin after the edit rolls back, same as `feature put`.

Run variables: hydrate `pkb_*.properties` and the yaml keys the project already loads as run vars into `run_variables`. `store var set` on sandbox `main` writes the file back. A secret-looking value or a name in `secret-fields` is stored as the reference the caller passed. A cleartext secret is rejected with `error:` and `fix:`, same rule as `StoreSecrets`. Sandbox `store var set` updates the sandbox database only.

CLI:

- `store enable <feature|scenario|step|definition|folder> <id> --on|--off [--json]`
- `store tag add|remove --feature=<path> --scenario=<ord-or-name> --tag=<tag> [--json]`
- `store var list [--json]`
- `store var set <name> <value> [--json]`
- `store var enable <name> --on|--off [--json]`

`pkb_store_log=enable` logs each toggle with the entity id. Do not log variable values.

Inlinable JSON, XML, and YAML stay in `files.raw_content` as they do today when they are under the size limit. Java `.java` and `.class` stay references when they already qualify as binary refs, and otherwise stay stored bytes. Do not invent a new binary policy. The plan's "cannot be inlined" rule for Java source is satisfied by keeping `location` and `binary_ref` as they are and by the engine never opening them (R2). This phase does not strip Java source out of `raw_content` if discovery already stored it. Changing that would break `store glue` edits. Record that interpretation in the release note: Java source that the editor can change stays a stored row; the runtime still will not compile from a path it walks itself.

### Docs

Update `AGENTS.md` Store section, `docs/configuration.md` run-variable paragraphs, `docs/store-entities.md`, and `docs/agent/feature-map.md`. Release notes: JAR-hash wipe does not drop run history; `@pkb-ignore`; `enabled` defaults.

### Tests

Create `tools.dscode.store.JarHashResetTest`. Change the stored `jar_hash`, hydrate, assert source rows were rebuilt and a pre-seeded `runs` row in `runstate.db` is still there.

Create `tools.dscode.store.EnabledFlagTest`. A file outside the feature root hydrates with `enabled=0`. `store enable scenario --off` writes `@pkb-ignore`. `--on` removes it. `store enable folder --off` tags the Feature line of each feature in that folder.

Create `tools.dscode.store.ScenarioTagTableTest`. Hydrate a feature with a scenario tag and an Examples tag. `scenario_tags` has both. The Examples tag is not copied onto the other scenarios. This matches the existing Examples-tag rule.

Create `tools.dscode.store.RunVariableSyncTest`. `store var set` on main updates the file and the row. On a `--mode=copy` sandbox it updates the sandbox row and not the file. A cleartext value for a secret field fails.

Create `tools.dscode.store.PkbIgnoreRoundTripTest`. Hydrate, disable, extract, hydrate again, assert `enabled=0` because the tag is now in the file.

Extend `StoreHydrateTest` with one method that asserts an unchanged file increments `reused` and not `rehydrated`.

Extend `StoreSchemaTest` for the new columns and tables. `SCHEMA_VERSION` remains 1.

```
.\gradlew.bat :test --tests tools.dscode.store.JarHashResetTest --tests tools.dscode.store.EnabledFlagTest --tests tools.dscode.store.ScenarioTagTableTest --tests tools.dscode.store.RunVariableSyncTest --tests tools.dscode.store.PkbIgnoreRoundTripTest --tests tools.dscode.store.StoreHydrateTest --tests tools.dscode.store.sql.StoreSchemaTest --console=plain
```

## P3 — Conflict choice

### Functionality

`store conflicts` already lists a both-sides edit. Add `tools.dscode.store.StoreConflictChoice`.

CLI:

- `store conflicts show <relative-path> [--json]` prints the file text and the database text. Do not print a secret field's resolved value. Print the stored reference.
- `store conflicts choose <relative-path> --keep=db|file [--json]`

`--keep=db` writes the database bytes to the file and sets `last_synced_hash` to that hash. `--keep=file` replaces the database row from the file and clears `db_edited`. The discarded text is written to `.pickleball/store/discarded/<utc-timestamp>-<relative-path-with-separators-turned-into-underscores>`. On the next process start, delete a discarded file older than 24 hours, and delete any discarded file whose sibling marker names a different JVM start id. The start id is written to `.pickleball/store/discarded/session`.

No merge library. No three-way merge. `store sandbox merge` is unchanged.

`pkb_store_log=conflict` logs the path, the choice, and the discarded file name. Not the body.

### Docs

`AGENTS.md` conflict sentences, `docs/store-entities.md`, release notes.

### Tests

Create `tools.dscode.store.ConflictChoiceTest`. Both sides differ: `show` contains both bodies; `choose --keep=file` makes the database match the file; the discarded database text is on disk; a second JVM-style call of the cleanup with a clock 25 hours ahead deletes it. Use an injected clock on `StoreConflictChoice`, do not sleep. `--keep=db` is the mirror case. A secret field in the payload is asserted to be a reference in both the show output and the discarded file.

Extend `StoreExtractTest` so extract without `choose` still refuses a both-sides edit.

```
.\gradlew.bat :test --tests tools.dscode.store.ConflictChoiceTest --tests tools.dscode.store.StoreExtractTest --console=plain
```

## P4 — Copy-on-write sandboxes

### Functionality

`tools.dscode.store.StoreSandbox` create gains `--mode=cow|copy`. Default `cow`. Omitted `--mode` on existing callers in tests must be updated only where a test asserted a full copy without caring about mode. `SandboxIndependenceTest` calls `--mode=copy` explicitly and keeps its assertions: no parent, full data, no lineage.

Cow implementation is `tools.dscode.store.CowSandbox`. The file is still `.pickleball/store/sandboxes/<name>/source.db`. `schema_meta.sandbox_mode=cow`. `_pkb_branch` gains the key `parent` (the parent sandbox name, `main` when created from main). Do not use SQLite `ATTACH` on the run path. Edits write `deltas` in the child only. A delete is a tombstone delta. `HeadMaterializer` in the child walks the parent head, then the child deltas, and writes the child's `head_rows`.

`store sandbox promote <name> --to=parent|top --logs=clear|archive [--json]`. `--logs` is required. `--to=parent` flattens the child head into one expensive delta on the parent, then marks the child head as promoted. `--to=top` walks parents until `main`. Promote onto `main` syncs changed file rows back to the working tree through the existing extract path, skipping a both-sides conflict (report it, do not guess). Promote onto another sandbox does not touch the working tree. `--logs=clear` deletes the child's `run_logs` rows for that sandbox name in `runstate.db`. `--logs=archive` leaves them and sets `runs.note` prefix `archived`. This is the storage primitive. The Workbench button is R6.

`store sandbox extract` stays the explicit materialize. Cow create does not extract.

CLI help and `StoreSandboxCliTest` cover the new flags.

`pkb_store_log` capability `sandbox` already exists. Use it for cow create and promote. Entity is the sandbox name.

### Docs

`AGENTS.md` sandbox paragraphs: default is cow, `--mode=copy` is the independent database, promote requires `--logs`. `docs/store-entities.md`. Release notes state that this overrides the embedded-store "no parent" default and that `--mode=copy` preserves it.

### Tests

Create `tools.dscode.store.CowSandboxTest`. Create cow from main, edit one file row, assert the parent `files` row is unchanged, assert the child head shows the edit, assert a tombstone hides a deleted row, assert the child database file is smaller than a `--mode=copy` of the same parent on a fixture with more than one file.

Create `tools.dscode.store.SandboxPromoteTest`. Promote `--to=parent --logs=archive` writes one delta on the parent and leaves the run row. `--logs=clear` drops the child run logs. A both-sides conflict on main is reported and does not write the file. Missing `--logs` exits 2.

Update `SandboxIndependenceTest` to pass `--mode=copy` and keep every current assertion.

Extend `StoreSandboxCliTest` for `--mode` help text.

```
.\gradlew.bat :test --tests tools.dscode.store.CowSandboxTest --tests tools.dscode.store.SandboxPromoteTest --tests tools.dscode.store.SandboxIndependenceTest --tests tools.dscode.store.StoreSandboxCliTest --console=plain
```

## Out of R1

Do not change `StepRegistry`, flag-step bodies, the Swing window, Node, MCP, browser download behavior, or `runstate.db` snapshot tables. Do not bump `store_schema`. Do not run the consumer `@all` suite.
