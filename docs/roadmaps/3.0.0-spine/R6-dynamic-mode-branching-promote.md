# R6 — Dynamic mode, branching, and promote

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R1 and R5. Design: `docs/plans/2026-11-25-spine-design.md`. Scoreboard: `docs/maintainer/3.0.0-spine/R6-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R6.md`.

Every phase is mandatory and must be completed in full. Nothing in R6 is optional or deferred. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Targeted tests only. Do not run `@all`. Windows only. The end-of-work regression is the short (Chrome + Windows) tier in [Regression tiers](final-regression.md). It runs only after every roadmap and fix is done and verified with targeted tests. The full tier runs only when Dan asks. Append new Windows-only class names to `docs/tech-debt.md`. Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. Update `tools.dscode.browser.SurfaceAssetsTest` only if surface-asset bytes change. R6 should not change them.

The Node pages call verbs. They do not implement fork or promote themselves.

## CLI-first and logging checklist

- Verbs: `branch fork`, `branch discard`, `branch promote`, `map show`, `map set`. All on `StoreCli` or `EngineCli` as specified below, registered in `VerbCatalog`.
- `pkb_store_log` capability `delta`, entity is the branch name. Off unless named.

## P1 — Fork on enter, empty discard, color

### Functionality

`store branch fork --from-run=<runId> [--name=<name>] [--json]` creates a cow sandbox (R1 `CowSandbox`) whose pin copies the run's `commit_sha` and `head_id`. The completed run's rows in `runstate.db` are not updated. The new sandbox's first delta is `kind=fork` with payload `{"fromRun":"<id>","fromHead":"<headId>"}`.

Entering dynamic mode in the Node shell is this verb. There is no automatic fork on a keystroke. `store branch discard --name=<name>` deletes an empty fork (only the `fork` delta, no later deltas) and does not touch the parent. A non-empty fork is refused without `--force`. On exit the shell posts `store branch discard --name=<fork>` with one keepalive `fetch`. The verb deletes the fork only when the sole delta is the fork delta.

The shell reads `store pin show` and sets the title to the branch name. Accent color is a deterministic color from the name (hash to a fixed six-color list in the page CSS). Two different names do not share a color unless the hash collides. The test asserts two known names map to the documented colors, not that the hash is collision-free.

Original run logs stay on the original `run_id`. The fork's new activity uses a new run id. `NativeScenarioRun` on a fork passes the fork sandbox to the existing store binding (`StoreScope`). It does not open the original run's log rows for write.

CLI help, inventory JSON, `VerbCatalog`, and `documented-help.txt` update together.

### Docs

`docs/pickleball-workbench.md` dynamic mode: fork is explicit, the original run is immutable, an empty fork is discarded. Release notes. `AGENTS.md` sandbox paragraph gains `store branch`.

### Tests

Create `src/test/java/tools/dscode/store/BranchForkTest.java`. Fork from a finished run. Append a run log on the fork's new run. Assert the original run's log count is unchanged. `discard` on an empty fork removes the sandbox. `discard` on a fork that has a second delta fails without `--force`.

Create `src/test/java/tools/dscode/launcher/BranchColorTest.java`. The page's color function, extracted as a small pure function in `workbench-ui/color.mjs` and mirrored by a Java check of the same six-color table committed in `workbench-ui/colors.json`, returns the expected color for `main` and for `fork-a`. One table, two readers. The test fails if the JSON and the page disagree.

```
.\gradlew.bat :test --tests tools.dscode.store.BranchForkTest --tests tools.dscode.launcher.BranchColorTest --tests tools.dscode.launcher.VerbCatalogTest --console=plain
```

## P2 — Map tab and the pre-step hook

### Functionality

`engine map show --run=<id> --step=<ord> [--json]` prints the `ParsingMap` tree for that step: name, value reference, parent name. Values that are secrets are `~MASK;`. The view is labeled with the run id and the step ord in the JSON (`boundRun`, `boundStep`) so a client cannot confuse it with the live project.

`engine map set --run=<id> --step=<ord> --key=<k> --value=<v>` writes a delta `kind=map` on the fork only. The value is the text the user typed. If the key is a secret field, the command refuses a cleartext value. The delta does not modify the original run.

`tools.dscode.engine.adapter.cucumber.NativeScenarioRun` applies those deltas in `tools.dscode.engine.runtime.PreStepHook` immediately before the step runs. Children of that step see the value. The parent step's map and later steps that are not descendants do not. The hook reads deltas. It does not read the Node page.

The shell's Map tab calls `map show` and `map set`. It appears when a run or a fork is selected. Logs and screenshots tabs call the existing `store timeline` and artifact list. They are not a new store.

On-the-fly step definitions: `store glue put` into the fork sandbox, which R1 already isolates. The shell's editor passes `--sandbox=<fork>`. A put on the fork does not change main's `step_definitions`.

`pkb_engine_log=match` logs `pre-step hook keys=<n>` for the step. Not the values.

### Docs

`docs/dynamic-control-api.md` and `docs/pickleball-workbench.md` Map tab. The parsing-map guide (`docs/mapping-and-templating.md` if that is the current guide; confirm and edit the one the feature map names) gains the pre-step hook. Release notes.

### Tests

Create `src/test/java/tools/dscode/engine/runtime/PreStepHookTest.java`. A fork delta sets key `city` on step 2. Step 2 and a child see `city`. Step 1 and step 3 do not. A secret key with cleartext fails `map set`.

Create `src/test/java/tools/dscode/store/ForkGluePutTest.java`. `store glue put --sandbox=<fork>` changes the fork catalog and not main.

```
.\gradlew.bat :test --tests tools.dscode.engine.runtime.PreStepHookTest --tests tools.dscode.store.ForkGluePutTest --console=plain
```

## P3 — Promote, history tree, parent metadata

### Functionality

`store branch promote` from R1 is the storage primitive. This phase adds the shell button and the metadata the plan requires.

`store branch promote <name> --to=parent|top --logs=clear|archive` stays required. The shell shows a control that will not submit until `clear` or `archive` is chosen. The page posts the verb. It does not merge.

`--to=parent` and `--to=top` behave as R1 specified. This phase adds column-level metadata, not a second chain: `schema_meta` is wrong for a list. Table `branch_parents(name TEXT PRIMARY KEY, parent_name TEXT, linked INTEGER NOT NULL DEFAULT 0)` on the source database of the child. `linked=0` is the default. `store branch link <name> --parent=<other> --on|--off` sets it. Resolution ignores `branch_parents`. A test asserts a promote does not walk `branch_parents`.

Per-file history: `store branch tree --file=<relative> [--json]` lists the fork and promote deltas whose payload names that file, newest first. The shell draws it as a collapsible list. The verb returns rows. The page does not query SQLite.

A/B is R7. Do not add `--head` here.

### Docs

`docs/pickleball-workbench.md` promote section. The word is promote, not merge. `AGENTS.md` one paragraph. Release notes.

### Tests

Create `src/test/java/tools/dscode/store/BranchTreeTest.java`. Two forks that touch one file appear in `store branch tree --file`. A fork that does not touch it is absent. `branch link --off` leaves promote results unchanged: the parent head matches a promote that never wrote `branch_parents`.

Extend `SandboxPromoteTest` from R1 if a case is missing for `--logs` required. Do not duplicate it if R1 already covers that.

```
.\gradlew.bat :test --tests tools.dscode.store.BranchTreeTest --tests tools.dscode.store.SandboxPromoteTest --console=plain
```

## Out of R6

Do not add snapshot retention, the cross-cutting store, the warm classloader, or browser CDN downloads.
