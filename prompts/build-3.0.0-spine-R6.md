# Build prompt: 3.0.0 spine R6 (dynamic mode, branching, promote) plus the session-rule exemption

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to implement Dan's session-rule exemption decision (phase P0 below) and then R6 in full (P1, P2, P3), on branch `3.0.0-spine` only, and then stop. Do not start anything after R6.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`, never `3.0.0-spine-r8`. No tags, releases, publishing, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine`
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R6-dynamic-mode-branching-promote.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. Cow sandboxes, promote, and the pre-step hook are closed. Do not reopen them.
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R6-scoreboard.md`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Do not use `./gradlew`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

Read these in full before you change anything. The branch docs win over this prompt if they disagree. If they disagree, follow the docs and say so on the scoreboard.

1. `AGENTS.md` (Store sandboxes, dynamic control, Workbench isolation, the session rule).
2. `docs/roadmaps/3.0.0-spine/R6-dynamic-mode-branching-promote.md`, every phase, including **Out of R6**.
3. `docs/roadmaps/3.0.0-spine/final-regression.md`.
4. `docs/tech-debt.md`, especially `## Spine merge follow-ups (open, 2026-11-26)` and the Linux and macOS section.
5. The design sections on cow sandboxes, promote, and dynamic mode.
6. `docs/roadmaps/3.0.0-spine/R5-workbench-one-control-plane.md` and `docs/maintainer/3.0.0-spine/R5-scoreboard.md` (the Node workbench and the session rule R6 builds on), and `docs/maintainer/3.0.0-spine/merge-r8-scoreboard.md`.
7. `docs/release-notes.md`, `docs/pickleball-workbench.md`, `docs/dynamic-control-api.md`, `docs/mapping-and-templating.md` (confirm it is the parsing-map guide the feature map names), `docs/verb-inventory.md`.
8. Code: `CowSandbox.java`, `StoreSandbox.java`, `StoreCli.java`, `EngineCli.java`, `NativeScenarioRun.java`, `ParsingMap.java`, `VerbCatalog.java`, `VerbDispatch.java`, `SpineSession.java`, `WorkbenchCliHelp.java`, `WorkbenchUiServer.java`, `workbench-ui/server.mjs`, `workbench-ui/pages/index.html`.

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync, and the repository index.

## State

`3.0.0-spine` has R1 to R5, R7, and R8 (merged from `3.0.0-spine-r8`), all verified. R6 has not started; its scoreboard is all `pending`. Things R6 must account for:

- **Session rule.** Every `VerbDispatch` call requires `--session=<id>` or the default session from `spine session new`, else exit 2 with `error: missing session` and `fix: spine session new`. `spine session new` is exempt. Every R6 verb goes through `VerbDispatch`, so R6 CLI tests create a session first, the way production does (`spine session new` through the CLI, or `SpineSession.ensure` / `SpineSession.create` in setup). The Node renderer already ensures a default session and adds `--session` on `/store`.
- **Renderer writes are lease-gated** (`LeaseHold.require` for `ui`) in `WorkbenchUiServer`; new mutating verbs the pages call (`branch fork|discard|promote|link`, `map set`, `glue put --sandbox`) must be covered by that gate.
- **Promote verb name.** The R1 storage primitive is `store sandbox promote <name> --to=parent|top --logs=clear|archive` (`SandboxPromoteTest`). The roadmap names `store branch promote`. Add `store branch promote` as the R6 verb that calls the same R1 code path (no second implementation), keep `store sandbox promote` working, and record the choice on the scoreboard.
- **Stale scoreboard header.** `R6-scoreboard.md` says the full regression is the last phase of R8. The current rule is `docs/roadmaps/3.0.0-spine/final-regression.md`: it runs once, after R6, on Dan's Windows PC. Correct that header line in place.

## Phase P0: Dan's session-rule exemption decision (Dan, 2026-11-26 9:06 PM MST)

Do this first, as its own commit(s), before P1.

1. **Explicit allow-list.** Exempt `engine help`, `domengine version`, family help (`<family> help`, `help <family>`, and `--help` for a family), and `export-guidance` from the session rule through one explicit allow-list in code at the `VerbDispatch` session gate (a named constant with a comment pointing to this decision). No pattern that silently exempts other verbs.
2. **Separate-process test, fail-before.** A test that runs each exempt command through `PickleballWorkbenchLauncher` main in its own JVM (argument file, not in-process) on a fresh project with no session, and asserts each exits 0 with its normal output. Run it against the current head first and record the failure (each exits 2 with `error: missing session`).
3. **Other verbs still refused.** The same test (or a sibling) asserts that non-exempt verbs (for example `store status`, `chat list`, `inject on`, `spine start`, `bidi script`) still exit 2 with `error: missing session` and `fix: spine session new` when there is no session.
4. **Update the R5 test that locks non-exemption** (`RendererStoreSessionTest`, which asserts `engine help` and `domengine version` fail with `error: missing session`, and any other test that asserts it). This is a documented rule change, not a weakening: change those assertions to the new rule (exempt commands succeed without a session), keep or add the assertion that other verbs are still refused, and record the old and new assertion on the scoreboard with Dan's decision as the reason.
5. **`export-guidance --warm`.** If `export-guidance --warm` starts a long-lived warm engine or controller, that path must still require a session or create a short-lived session of its own that it ends when it is done. Pick the behavior the docs support, implement it, and test it in a separate process: plain `export-guidance` runs with no session; `--warm` either refuses without a session (`error:`/`fix:`) or leaves no stray default session behind afterwards. `PolishRoadmapTest.warmPingIsUnderOneSecond` must still pass without weakening its timing assertion; adjust its session setup only if the chosen behavior requires it.
6. **Tech-debt.** In `docs/tech-debt.md`, replace the `Session rule scope for help/version/export-guidance (awaiting Dan)` line with the decision (what is exempt, the allow-list location, the `--warm` behavior). Add a new line in the same follow-ups section: `Revisit the session-rule exemption for help/version/export-guidance` (check later whether exempting them was right).
7. **Docs.** Rewrite in place `AGENTS.md`, `docs/configuration.md`, the session-rule text in help, and the consumer guidance copies. Add a `Session-rule exemptions.` entry to `docs/release-notes.md`. Regenerate `documented-help.txt` if help text changes.
8. **Scoreboard.** Add a `P0 — session-rule exemptions (Dan, 2026-11-26)` row to `R6-scoreboard.md` with the SHA, the before failure, and pass counts.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred. Do not start any work after R6.
- Targeted tests only: the Gradle command each phase names, plus the direct tests of any class you edit and the R5 or R8 tests that touch it. No full regression; it is deferred per `docs/roadmaps/3.0.0-spine/final-regression.md`. No unfiltered `:test`, `:pickleball-workbench:test`, `:dom-engine:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`.
- Windows only (Dan's standing rule). No Linux or macOS runs. No GitHub Actions or workflow runs, no `workflow_dispatch`, no pull request to get CI. Append any new Windows-only class to the Windows-only list in the Linux and macOS section of `docs/tech-debt.md` (a `**Spine R6, Windows only:**` line). Do not delete that section.
- Every new behavior gets a test that fails before the change and passes after, through the production caller (`PickleballWorkbenchLauncher` / `VerbDispatch`, `StoreCli`, `EngineCli`, `NativeScenarioRun`, the Node bridge `/store`), not a test seam. Record the before failure on the scoreboard.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. The P0 rule change to the R5 assertion is the one documented exception and must be recorded as such. Keep Gherkin syntax. Step annotations stay single string literals.
- `store_schema` stays 1. The database stays under `.pickleball/store/`. Do not move it to `target/` or `build/`.
- Do not change `pkb_callpath` or `pkb_componentpath` path grammar.
- Entering dynamic mode is `store branch fork`. There is no automatic fork on a keystroke. The Node page calls the verb. It does not merge and it does not open SQLite.
- Workbench isolation holds. Do not add `implementation project(':')`. Do not put pickleball classes or `node_modules` into the workbench jar. Do not add npm packages; R6's `workbench-ui/color.mjs` and `workbench-ui/colors.json` are plain files.
- Promote stays the R1 primitive. `--logs=clear|archive` is required. The shell will not submit until one is chosen. The word in the UI and docs is promote, not merge. `branch_parents` is optional metadata. Resolution ignores it. A test asserts promote does not walk `branch_parents`.
- No secrets in cleartext. `map set` on a secret key refuses a cleartext value. `map show` prints `~MASK;`. Logs print key counts, not values.
- A/B comparison is R7 territory. Do not add `--head` on the promote or fork verbs.
- Docs describe only the behavior this phase implemented. Rewrite them in place. History goes in `docs/release-notes.md`. Do not replace `AGENTS.md` wholesale.
- A new verb updates, in the same commit: the CLI help, `docs/roadmaps/workbench-cli-inventory.json`, `docs/verb-inventory.md`, `VerbCatalog`, and `src/test/resources/guidance-golden/documented-help.txt`, and the `WorkbenchCliCoverageGateTest` coverage methods it requires.
- Update `tools.dscode.browser.SurfaceAssetsTest` only if surface-asset bytes change. R6 should not change them.
- Record the implementation SHA, the test command, and the pass count on the scoreboard after each green phase, in the same commit as the code or the immediately following commit before the push.
- Push after every change. Before every push, each command on its own (do not chain with `&&`, `;`, or a pipe):

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Never force-push. Never push to `master` or `3.0.0-spine-r8`. Never run `gradlew --stop`.
- Never touch `C:\Users\Admin\IdeaProjects\pkb-spine-r8` or `C:\Users\Admin\IdeaProjects\pkb-spine-r7`.
- `python scripts/verify_agent_contract.py` exits 0 today. Keep it at 0.
- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation. Report what you ran and what failed. Do not mark a phase done when its tests were not run or any of them failed.

## CLI-first and logging

Verbs: `branch fork`, `branch discard`, `branch promote`, `branch link`, `branch tree` on `StoreCli`; `map show` and `map set` on `EngineCli`. Register every one in `VerbCatalog`. The Node pages only forward through `/store` (and the bridge's catalog check). `pkb_store_log` capability `delta`, entity is the branch name. `pkb_engine_log=match` logs `pre-step hook keys=<n>`, not the values. Off unless named. Date-stamped, size-capped, sandbox name on the store line. Do not add a new logger class.

## Phases

Implement P0, then P1, then P2, then P3. The roadmap is the acceptance criteria; it names the classes, verbs, tables, and test classes. Use those names. Do not invent a parallel set.

- **P1.** `store branch fork --from-run=<runId> [--name=<name>] [--json]` creates a cow sandbox whose pin copies that run's `commit_sha` and `head_id`. Completed run rows are not updated. First delta `kind=fork` payload `{"fromRun","fromHead"}`. `store branch discard --name=<name>` deletes an empty fork. A non-empty fork is refused without `--force`. The shell calls discard on exit when `store delta list` shows only the fork delta. Title from `store pin show`. Accent color from the fixed six-color list in `workbench-ui/colors.json`, hashed in `workbench-ui/color.mjs`; `BranchColorTest` checks `main` and `fork-a` against that JSON (one table, two readers). Fork activity uses a new run id. `NativeScenarioRun` writes the fork sandbox through `StoreScope`, not the original run's log rows. Tests and command: as the roadmap's P1 (`BranchForkTest`, `BranchColorTest`, `VerbCatalogTest`), plus a renderer test through the real `/store` path that the page's fork control posts `store branch fork` with the session.
- **P2.** `engine map show --run=<id> --step=<ord> [--json]` with JSON fields `boundRun` and `boundStep`, secrets as `~MASK;`. `engine map set` writes a `kind=map` delta on the fork only and refuses cleartext for a secret key. `PreStepHook` in `NativeScenarioRun` applies the deltas immediately before the step: children of that step see the value; the parent step and later non-descendant steps do not. The hook reads deltas, not the Node page. The Map, Logs, and Screenshots tabs call `map show` / `map set`, `store timeline`, and the artifact list. `store glue put --sandbox=<fork>` does not change main's `step_definitions`. Tests and command: as the roadmap's P2 (`PreStepHookTest`, `ForkGluePutTest`).
- **P3.** The shell promote control posts `store branch promote <name> --to=parent|top --logs=clear|archive` and will not submit without `--logs`. Table `branch_parents(name TEXT PRIMARY KEY, parent_name TEXT, linked INTEGER NOT NULL DEFAULT 0)` on the child source database. `store branch link <name> --parent=<other> --on|--off` sets it. Resolution ignores it. `store branch tree --file=<relative> [--json]` lists fork and promote deltas for that file, newest first; the page draws it as a collapsible list from the verb's rows. Tests and command: as the roadmap's P3 (`BranchTreeTest`, `SandboxPromoteTest`; extend `SandboxPromoteTest` only if the required `--logs` case is missing).

Each phase's docs are listed in the roadmap (`docs/pickleball-workbench.md`, `docs/dynamic-control-api.md`, `docs/mapping-and-templating.md`, `AGENTS.md`, release notes). Rewrite them in place.

## Out of R6

Do not add snapshot retention, the cross-cutting store, the warm classloader, or a browser CDN download. Do not change R8's warm engine, `SpineCli`, BiDi, or browser ports. Do not start the final regression.

## Finish

You are done when P0 to P3 are green, the scoreboard has a SHA and pass counts for each, `python scripts/verify_agent_contract.py` exits 0, the working tree is clean, and `origin/3.0.0-spine` equals your HEAD. Then STOP and report the head commit SHA and the scoreboard table.
