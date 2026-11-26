# Grok Build prompt: pickleball 3.0.0 spine R7

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R7 only, in full. R1 and R2 are on this branch. R5 and R6 may already be there. This roadmap does not need the Node window. Do not redo earlier roadmaps and do not start R8.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R7-runtime-snapshots-retention-cross-store.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. The snapshot shape, retention, the exception model, and the cross-cutting store are closed. Do not reopen them.
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R7-scoreboard.md`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat` and `.\maven-consumer-project\mvnw.cmd`. Do not use `./gradlew`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. `python`, not `python3`.

## Read first

1. `AGENTS.md` (Store, run history, retention, exceptions, diagnostic reporting).
2. The design sections on runtime snapshots, report/script, exceptions, and the cross-cutting store.
3. The R7 roadmap, every phase, including the DDL and **Out of R7**.
4. `SqlDialect.java` (`RUN_DDL`), `StoreExecution.java` or the class that writes run rows, `StoreCli.java`, `DashboardHtml.java`, `ElementOperations.java`, `PickleBallExceptionInterface.java`, `StatusException.java`, `StepCreationException.java`, `PickleballWorkbenchLauncher.java`.

The roadmap names the tables, columns, verbs, and test classes. Use those names. The DDL in the roadmap is the schema. Do not invent a second snapshot database and do not copy the source database per run. If `SnapFindIndexTest` cannot live in `tools.dscode.common.domoperations` because `ElementOperations` tests live elsewhere, put the test next to those tests and record the real class name on the scoreboard.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred. Do not start R8.
- Each phase runs targeted tests only, the Gradle command in that phase. Do not run `@all`. Full regression is the last phase of R8, not here.
- The consumer `RECORD ENTITY` scenario, after `publishToMavenLocal`, runs that feature's real tag only. Search for an existing entity feature first. Use `area-b-control-flow.feature` only when no entity feature exists. Do not add a new feature file if one already covers entities. Do not use `@all`.
- Testing is on Windows only. Append any new Windows-only class to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section. Do not run Linux or macOS.
- Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals. The two new phrases are exactly the literals in the roadmap, on `ObjectRegistrationSteps` and `MetaSteps`.
- `store_schema` stays 1. Additive tables and `schema_meta` keys only, the `example_blocks` pattern. `cross_schema` starts at 1. Source DB stays `.pickleball/store/sandboxes/<name>/source.db`. Run state stays `.pickleball/store/runstate.db`. Do not move either to `target/` or `build/`.
- Do not change `pkb_callpath` or `pkb_componentpath` path grammar.
- Do not add a ReportPortal server or a new ReportPortal dependency. The dashboard is `DashboardHtml` over SQL. HTML export is read-only and is not a runnable script.
- No ML library and no model. `exception_clusters` is a SQL view.
- JAR-hash wipe still does not delete `runstate.db`. Retention deletes runs only through `store retain` and `store history retain`. No background deleter.
- No secrets in cleartext. Script values that fail `StoreSecrets` are `~MASK;`. The journal stores `args_hash`, never raw args. `project_hash` is the SHA-256 of the project path. The raw path is not stored. A secret-shaped note body is rejected.
- Workbench and MCP only forward. `CrossStore` is opened by the pickleball launcher JVM, not by the workbench process. Do not add `implementation project(':')`.
- Docs describe only the behavior this phase implemented. Rewrite them in place. History goes in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale. Remove tech-debt row 6.3.1 in P2 and row 6.8.8 in P4. Do not add links to `docs/roadmaps/`, `docs/maintainer/`, or `prompts/` inside `docs/tech-debt.md`.
- Before every push, each command on its own:

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

Update `tools.dscode.browser.SurfaceAssetsTest` only if surface-asset bytes change. R7 should not change them.

- Store `--json` stays an `AgentEnvelope` (R4). The snapshot object goes under `items`. Do not add a second JSON shape.
- A new verb updates, in the same commit: the CLI help, `docs/roadmaps/workbench-cli-inventory.json`, `docs/verb-inventory.md`, and `documented-help.txt`.
- Record the implementation SHA, the test command, and the pass count on `docs/maintainer/3.0.0-spine/R7-scoreboard.md` after each green phase, in the same commit as the code or in the immediately following commit before the push.
- Fetch and rebase before every push. Each git command stands alone. Do not chain with `&&`, `;`, or a pipe. Never force-push. Never push to `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation. Report what you ran and what failed. Do not mark a phase done when its tests were not run.

## CLI-first and logging

New verbs: `store report write`, `store report export`, `store run --script`, `store diff`, the retain defaults, `store head rebuild --from-archive`, and the `cross` family (`note add|list`, `journal list|retain`, `link add|list`). `cross` is dispatched by `PickleballWorkbenchLauncher` before `store`. Workbench and MCP only forward. `pkb_store_log` capability `pin` for snapshot writes. Entity is the run id. `pkb_journal` defaults on. `pkb_journal=off` writes no journal row and does not create `cross.db` for the journal alone. A journal write failure logs `error:` once and does not fail the verb. `pkb_engine_log=match` entity `find` logs `index miss`. Off unless named. Date-stamped, size-capped. Do not add a new logger class.

## Phases

Implement P1, then P2, then P3, then P4. The roadmap DDL is the schema. Do not shorten it.

- **P1.** On `runstate.db`: `run_snapshots`, `run_snapshot_entities`, `run_attributes`. Columns on `runs`: `commit_sha`, `head_id`, `parent_commit`, `parent_head_id`. Existing rows get empty strings. Foreign keys `ON DELETE CASCADE` from `runs`. `schema_meta` key `run_snapshots=1` on the run-state database. Source `store_schema` stays 1. `enabled_hash` is the SHA-256 of the sorted `enabled=1` entity keys. Entity rows store identities and hashes, not payloads. HTML and screenshots stay artifact files (path plus sha256). `StoreExecution` writes the snapshot at run start. `store history show <run-id> --json` includes it under `items`.
- **P2.** Extension `.pkb.json`, `format` `pickleball.report-script`, `formatVersion` 1. `store run --script` and `--head` for A/B. `store diff` reports `break` when a definition is missing and `changed` on a hash change. `steps.visual_ref` points at an artifact of kind `dom` or `screenshot`. Playback requires both the regex and the identity-chain replay. A missing artifact is `error:` and `fix:`, not a skip. `{{name}}` is replaced from the parsing map before the match. `RECORD ENTITY` writes `entities`, `entity_usages` operation `mention`, and a snapshot entity row. `PickleballException` extends `RuntimeException` and implements `PickleBallExceptionInterface`. Do not rename the interface. `StatusException` and `StepCreationException` extend it. Default scope is `entity` when the running step has an entity usage, else `universal`. `environmental` only when run attribute `environment` is set (`pkb_environment` or `MARK ENVIRONMENT`). Signature is the class name plus the first non-pickleball stack frame. View `exception_clusters`. Table `snap_find_index`. `find` uses the index only when the live document version equals the snapshot's document version.
- **P3.** `pkb_store_retain_days` default 14. `pkb_store_retain_bytes` default 2147483648 (2 GiB). Distinct from `pkb_store_changes_days`. Retention does not delete a `heads` row that a retained run's `head_id` still names. Folded deltas past window 100 move to `delta_archive` in `runstate.db` and leave the source `deltas` table. `store head rebuild --from-archive=<seq>` refuses when that seq is still in the live window. No background deleter.
- **P4.** `tools.dscode.launcher.CrossStore`. Windows path `%USERPROFILE%\.pickleball\spine\cross.db`, else `~/.pickleball/spine/cross.db`, override `pkb_cross_store`. Created on the first `cross` verb or journal write, not on ordinary open. Tables `schema_meta`, `notes`, `journal`, `links`. It does not hold scenario bodies. The file survives deleting the project directory. `cross` verbs are catalog category `cli`, not `stream`.

## Out of R7

Do not build the warm classloader, the heap gate, or the browser version ladder.

## Finish

You are done when P4 is green, the scoreboard has four SHAs, and the branch is pushed. Reply with the scoreboard table and the tip SHA.
