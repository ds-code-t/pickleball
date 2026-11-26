# Grok Build prompt: pickleball 3.0.0 spine R6

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R6 only, in full. R1 and R5 are on this branch. Do not redo them and do not start R7.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R6-dynamic-mode-branching-promote.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. Cow sandboxes, promote, and the pre-step hook are closed. Do not reopen them.
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R6-scoreboard.md`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Do not use `./gradlew`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. `python`, not `python3`.

## Read first

1. `AGENTS.md` (Store sandboxes, dynamic control, Workbench isolation).
2. The design sections on cow sandboxes, promote, and dynamic mode.
3. The R6 roadmap, every phase, including **Out of R6**.
4. `CowSandbox.java`, `StoreSandbox.java`, `StoreCli.java`, `NativeScenarioRun.java`, `ParsingMap.java`, `VerbCatalog.java`.

The roadmap names the classes, verbs, tables, and test classes. Use those names. Do not invent a parallel set. The exact `--tests` filters are in the roadmap.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred. Do not start R7.
- Each phase runs targeted tests only, the Gradle command in that phase. Do not run `@all`. Full regression is the last phase of R8, not here.
- Testing is on Windows only. Append any new Windows-only class to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section. Do not run Linux or macOS.
- Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals.
- `store_schema` stays 1. The database stays under `.pickleball/store/`. Do not move it to `target/` or `build/`.
- Do not change `pkb_callpath` or `pkb_componentpath` path grammar.
- Entering dynamic mode is `store branch fork`. There is no automatic fork on a keystroke. The Node page calls the verb. It does not merge and it does not open SQLite.
- Workbench isolation holds. Do not add `implementation project(':')`. Do not put pickleball classes or `node_modules` into the workbench jar.
- Promote stays the R1 verb. `--logs=clear|archive` is required. The shell will not submit until one of those is chosen. The word in the UI and the docs is promote, not merge. `branch_parents` is optional metadata. Resolution ignores it. A test asserts promote does not walk `branch_parents`.
- No secrets in cleartext. `map set` on a secret key refuses a cleartext value. `map show` prints `~MASK;`. Logs print key counts, not values.
- A/B comparison is R7. Do not add `--head` on the promote or fork verbs.
- Docs describe only the behavior this phase implemented. Rewrite them in place. History goes in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale. Confirm the parsing-map guide the feature map names (expected `docs/mapping-and-templating.md`) and edit that file.
- Before every push, each command on its own:

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
```

Update `tools.dscode.browser.SurfaceAssetsTest` only if surface-asset bytes change. R6 should not change them.

- A new verb updates, in the same commit: the CLI help, `docs/roadmaps/workbench-cli-inventory.json`, `docs/verb-inventory.md`, and `documented-help.txt`.
- Record the implementation SHA, the test command, and the pass count on `docs/maintainer/3.0.0-spine/R6-scoreboard.md` after each green phase, in the same commit as the code or in the immediately following commit before the push.
- Fetch and rebase before every push. Each git command stands alone. Do not chain with `&&`, `;`, or a pipe. Never force-push. Never push to `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation. Report what you ran and what failed. Do not mark a phase done when its tests were not run.

## CLI-first and logging

Verbs: `branch fork`, `branch discard`, `branch promote`, `branch link`, `branch tree` on `StoreCli`; `map show` and `map set` on `EngineCli`. Register every one in `VerbCatalog`. The Node pages only forward. `pkb_store_log` capability `delta`, entity is the branch name. `pkb_engine_log=match` logs `pre-step hook keys=<n>`, not the values. Off unless named. Date-stamped, size-capped, sandbox name on the store line. Do not add a new logger class.

## Phases

Implement P1, then P2, then P3. The roadmap is the acceptance criteria.

- **P1.** `store branch fork --from-run` creates a cow sandbox whose pin copies that run's `commit_sha` and `head_id`. Completed run rows are not updated. First delta `kind=fork` payload `{"fromRun","fromHead"}`. `store branch discard --name` deletes an empty fork. A non-empty fork is refused without `--force`. The shell calls discard on exit when `delta list` shows only the fork delta. Title from `store pin show`. Accent color from the fixed six-color list in `workbench-ui/colors.json`, hashed in `workbench-ui/color.mjs`. `BranchColorTest` checks `main` and `fork-a` against that JSON. Two names may collide. Fork activity uses a new run id. `NativeScenarioRun` writes the fork sandbox, not the original run's log rows.
- **P2.** `engine map show` and `engine map set`. JSON fields `boundRun` and `boundStep`. `PreStepHook` runs immediately before the step. Children of that step see the value. The parent step and later non-descendant steps do not. The hook reads deltas, not the Node page. Map, logs, and screenshots tabs call `map show` / `map set`, `store timeline`, and the artifact list. `store glue put --sandbox=<fork>` does not change main's `step_definitions`.
- **P3.** Shell promote control posts `store branch promote <name> --to=parent|top --logs=clear|archive`. Table `branch_parents(name TEXT PRIMARY KEY, parent_name TEXT, linked INTEGER NOT NULL DEFAULT 0)` on the child source database. `store branch link --parent --on|--off` sets it. `store branch tree --file` lists fork and promote deltas for that file. Extend `SandboxPromoteTest` only if the required `--logs` case is missing.

## Out of R6

Do not add snapshot retention, the cross-cutting store, the warm classloader, or a browser CDN download.

## Finish

You are done when P3 is green, the scoreboard has three SHAs, and the branch is pushed. Reply with the scoreboard table and the tip SHA.
