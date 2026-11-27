# Build prompt: 3.0.0 spine, merge `3.0.0-spine-r8` into `3.0.0-spine`

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to merge branch `3.0.0-spine-r8` into `3.0.0-spine` with a real merge commit, resolve every conflict, fix the blockers that merge without a conflict, make the targeted tests pass, push to `3.0.0-spine`, and then stop.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`. Do not push to `3.0.0-spine-r8` or any other branch. No tags, releases, publishing, or force-push. Dan allows merges into 3.x branches; this one is authorized.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine`
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.

## Read first

Read these before you change anything. The branch docs win over this prompt if they disagree. If they disagree, follow the docs and say so on the merge scoreboard. Read the `3.0.0-spine-r8` copies with `git show origin/3.0.0-spine-r8:<path>`.

1. `AGENTS.md` on both branches
2. `docs/roadmaps/3.0.0-spine/R5-workbench-one-control-plane.md` and `docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md` (take the R8 roadmap from `3.0.0-spine-r8`; it must stay byte-identical to commit `b79a799b` after the merge)
3. `docs/maintainer/3.0.0-spine/R5-scoreboard.md` (including Fix-back 1 and 2) on `3.0.0-spine`, and `docs/maintainer/3.0.0-spine/R8-scoreboard.md` (including Fix-back 1, 2, and 3) on `3.0.0-spine-r8`
4. `docs/roadmaps/3.0.0-spine/final-regression.md`
5. `docs/tech-debt.md` and `docs/release-notes.md` on both branches
6. `docs/verb-inventory.md`, `docs/configuration.md`, `docs/pickleball-workbench.md`, `docs/dynamic-control-api.md` on both branches
7. `docs/plans/2026-11-25-spine-design.md`

Also follow the functionality-change rules in `AGENTS.md` for code, tests, docs, release notes, consumer guidance sync (`python scripts/sync_consumer_guidance.py`), and the repository index (`python scripts/refresh_agent_index.py`).

## State

- `origin/3.0.0-spine` is at `0227c864` or later: R1 to R4, R7, R5 with fix-backs 1 and 2. Its last commit before this prompt was `0227c864`.
- `origin/3.0.0-spine-r8` is at `20382588`: R8 with fix-backs 1, 2, and 3. Both are verified. R6 has not started; do no R6 work.
- A test merge found **8 conflicting files**: `docs/release-notes.md`, `docs/tech-debt.md`, `docs/verb-inventory.md`, `src/main/resources/META-INF/pickleball/guidance/docs/release-notes.md`, `src/main/resources/META-INF/pickleball/guidance/docs/verb-inventory.md`, `src/main/java/tools/dscode/launcher/VerbCatalog.java`, `src/main/java/tools/dscode/launcher/WorkbenchCommandLine.java` (R5 adds `"inject", "spine", "chat", "lease", "input"`, R8 adds `"bidi"`), and `src/main/java/tools/dscode/launcher/AgentHop.java` (R5 adds `"inject", "input", "lease"`, R8 adds `"bidi"`).
- It also found blockers that merge **without** a conflict:
  - `WorkbenchCliHelp` has `Map.entry("spine", …)` twice (around lines 16 and 199). `Map.ofEntries` throws on a duplicate key when the class loads, so the CLI crashes at startup.
  - `PickleballWorkbenchLauncher.dispatchSpineIfAny` (around line 79) sends every `spine …` call, including R5's `spine session new|show|end`, to R8's `SpineCli` through `VerbDispatch.run("spine", …)`. R5's handler `WorkbenchAgentCommands.spineSession` is never reached. R5's session gate in `VerbDispatch.run` (around line 72) exempts only `spine session new`, so after the merge nobody can create a session and `spine start|stop|warm|sample`, `store …`, and every other dispatched verb fail with `fix: spine session new`.
  - `bidi` is routed by `dispatchBidiIfAny` to `BidiSessionForward`, which does not go through `VerbDispatch`, so it skips R5's session rule.
  - Launcher ordering: the spine check runs before the bidi check, and both run before the generic dispatch the R5 verbs use.
  - `python scripts/verify_agent_contract.py` exits 1 on both branches with one error: the exported `docs/plans/2026-11-25-spine-design.md` links to `docs/tech-debt.md`, which is not in the guidance export.
  - R8 tests that call the launcher, `VerbDispatch`, or the spine verbs without a session will fail after the merge. Expected: `HoldJvmFlagsTest`, `WarmCdpTest`, `WarmRebuildRestartTest`, `WarmGlueCompilePathTest`, `ResourceGateTest`, `WarmColdParityTest`, `BidiCliSessionTest`. There may be others; find them by running the tests.

## Steps

1. **Merge.** In `C:\Users\Admin\IdeaProjects\pkb-spine`, confirm the tree is clean and on `3.0.0-spine`. `git fetch origin 3.0.0-spine 3.0.0-spine-r8`. Fast-forward to `origin/3.0.0-spine`. Then `git merge --no-ff origin/3.0.0-spine-r8`. A real merge commit: no squash, no rebase of r8, no cherry-picks, no force-push.
2. **Resolve the 8 conflicts in the merge commit.**
   - Release notes, tech-debt, verb inventory, and their guidance copies: keep both sides' content. Keep every `Spine R5 …` and `Spine R8 …` paragraph. For `docs/tech-debt.md`, keep R5's version of the `Deferred from the 3.0.0 consumer-fixes branch` table (R5 already removed the rows it delivered) and R5's `TypeScript package in the Workbench UI bundle` section unchanged, plus R8's live entries (including the `**Spine R8, Windows only:**` line next to the R5 one).
   - `VerbCatalog.java`, `WorkbenchCommandLine.java`, `AgentHop.java`: the union of both lists, no duplicates.
   - Make the merge commit compile (`.\gradlew.bat compileJava compileTestJava --console=plain`) before you commit it. Regenerate `docs/verb-inventory.md` and its guidance copy from the code if a generator exists, rather than hand-merging.
   - Commit the merge, then push it (see Branch rules). The blocker fixes may follow as separate commits.
3. **Fix the silent blockers.** Each one gets a test that fails before the fix (or would fail on the raw merge) and passes after, through the production caller:
   - (a) **One `spine` help entry.** Merge the two `Map.entry("spine", …)` entries in `WorkbenchCliHelp` into one entry that covers R5's `spine session new|show|end` and R8's `spine start|stop|warm status|sample`. Test: load `WorkbenchCliHelp`, and run `help spine` and `--help` through the real launcher (`PickleballWorkbenchLauncher` main in a separate process), checking both halves appear.
   - (b) **Spine routing.** `PickleballWorkbenchLauncher.dispatchSpineIfAny` sends `spine session …` to `WorkbenchAgentCommands` (`spineSession`) and every other spine verb to `SpineCli`. The `VerbDispatch` session gate keeps `spine session new` exempt. Test: a separate-process CLI test (`PickleballWorkbenchLauncher` main in its own JVM, argument file, not in-process) on a fresh project runs `spine session new`, then `spine start`, `spine warm status`, a `store …` verb, and `spine stop`, all exit 0 with the expected output.
   - (c) **`bidi` and the session rule.** Apply R5's session rule to `bidi script|intercept|grid-attach` the same way as every other agent verb: `--session=<id>` or the default session, else exit 2 with `error: missing session` and `fix: spine session new`. If the branch docs say `bidi` is exempt, follow them instead. Either way, document the choice in `docs/configuration.md`, `AGENTS.md`, help, and the scoreboard. Test through the CLI.
   - (d) **Launcher ordering.** Check by hand and with a test that the order is: spine, then bidi, then the generic dispatch, and that R5 fix-back 1's `ui` routing (commit `54a1faee`: bare `ui` opens the renderer, `ui page` goes to dispatch, `ui --ops` stays the catalog batch) still holds after the merge. Extend `UiPageDispatchTest` or add a launcher routing test.
   - (e) **`verify_agent_contract.py`.** Fix its single export error the right way: add `docs/tech-debt.md` to the guidance export (and sync it), or change the link in `docs/plans/2026-11-25-spine-design.md` to text that is not a broken export link. Do not weaken the checker or add an ignore. `python scripts/verify_agent_contract.py` must exit 0.
4. **R8 tests that now need a session.** Run the R8 tests; for each that fails only because there is no session, add session setup the way production does (`spine session new` through the CLI, or `SpineSession.ensure` / `SpineSession.create` in setup). Never weaken, remove, or change an assertion. Record each on the scoreboard with its before failure.
5. **Session exemptions stay as R5 has them.** Do not change whether `engine help`, `domengine version`, family help, or `export-guidance` need a session in this merge; Dan is deciding. Add a tech-debt line: `Session rule scope for help/version/export-guidance (awaiting Dan)`, noting that `export-guidance <dir>` treats the output folder as the project, so a consumer's `pickleball export-guidance .pickleball --warm` needs a session there.
6. **Tech-debt lines to add** (one line each, in a `## Spine merge follow-ups (open, 2026-11-26)` section):
   - Cold-child hooks: the R8 cold child scenario (`SnapshotScenario`) has no tags and fires its own before/after hooks, so hooks may run in both the parent and the child, and tagged hooks may miss in the child.
   - Renderer lease identity: the Node renderer sends no agent id and counts as `agent-1`, so a `ui` lease held by `agent-1` does not block the person in the renderer, and any page request can claim the holder with `--agent=`.
   - Warm-spine marker seam: `WarmWorkerReloadTest` writes the warm-spine marker with the test JVM's pid instead of a real `spine start` process.
   - Session rule scope (from step 5).
   Fix in place, not as debt: the `Spine R5 fix-back 2.` release-note wording that says generated pages get `script-src 'self'`; the header also allows `'unsafe-inline'`, so say so.
7. **Docs and generated files.** Add a `Spine R8 merge.` paragraph to `docs/release-notes.md` describing the routing, the single help entry, the `bidi` session choice, and the export fix. Update `AGENTS.md`, `docs/configuration.md`, `docs/verb-inventory.md`, `docs/roadmaps/workbench-cli-inventory.json`, and the help golden `src/test/resources/guidance-golden/documented-help.txt`. Run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. The R8 roadmap must stay byte-identical to `b79a799b` (check with `git diff b79a799b -- docs/roadmaps/3.0.0-spine/R8-warm-engine-resource-gate-browser-config.md`).

## Tests

- Targeted tests only, Windows only: every test class R5 and R8 touched (see the R5 and R8 scoreboards, all phases and fix-backs) plus the new ones. This is not the full regression; that stays deferred per `docs/roadmaps/3.0.0-spine/final-regression.md`. Use `.\gradlew.bat :test --tests <class> ... --console=plain`, `.\gradlew.bat :pickleball-workbench:test --tests <class> --console=plain`, and `.\gradlew.bat :dom-engine:test --tests <class> --console=plain`, batched by module. No unfiltered `:test`, `:dom-engine:test`, `:pickleball-workbench:test`, `:pickleball-control-protocol:test`, `:pickleball-control-api:test`, and no consumer `@all`.
- Each blocker fix: run its new test once before the fix (or on the raw merge) and record the failure.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, `assumeTrue` skips, or empty tests. Keep Gherkin syntax. Step annotations stay single string literals.
- Real-browser tests use the locally installed Chrome or Edge. No browser download.
- No Linux or macOS runs. No GitHub Actions or workflow runs, no `workflow_dispatch`, no pull request, no push to a CI-triggering branch.
- If a test that passed on its own branch fails after the merge for a reason other than the items above, fix the merged code (not the test) and record it on the scoreboard. If it cannot be fixed without R6, record it as `waiting on R6` with the reason.

## Scoreboard

Add `docs/maintainer/3.0.0-spine/merge-r8-scoreboard.md` with one row per item: the merge commit, each conflict group, blockers (a) to (e), each R8 test given a session, the tech-debt and docs items, and the final targeted batch. Each row: status, commit SHA, before failure (or `n/a`), test classes with pass and fail counts. A row with any failing test is not `done`.

## Branch rules

- Push after the merge commit and after every later change, to `3.0.0-spine` only: `git fetch origin 3.0.0-spine`, then check `origin/3.0.0-spine` has not moved. For later commits, `git rebase origin/3.0.0-spine` is fine only if it does not rewrite the merge commit; if origin moved before the merge commit is pushed, redo the merge on the new tip instead of rebasing it. Then `git push origin HEAD:3.0.0-spine`. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`.
- Never push to `master` or `3.0.0-spine-r8`. Do not modify `3.0.0-spine-r8`. No tags, releases, publishing, or force-push. Never run `gradlew --stop`.
- Read-only for `C:\Users\Admin\IdeaProjects\pkb-spine-r8`; never touch `C:\Users\Admin\IdeaProjects\pkb-spine-r7`.
- No R6 work.
- No secrets or tokens in cleartext in code, logs, pages, commits, or process arguments.

## Shared Maven local repository

Before any `publishToMavenLocal` or `maven-consumer-project` `mvnw` run, check whether a java, gradle, or mvn process whose command line contains `pkb-spine-r8` is running `publishToMavenLocal` or `mvnw`. If one is, wait with `Start-Sleep 120` and re-check, up to 60 minutes.

## Finish

After the last push, confirm the working tree is clean, `origin/3.0.0-spine` equals your HEAD, the merge commit has both `0227c864`-or-later and `20382588` as parents, `python scripts/verify_agent_contract.py` exits 0, and the R8 roadmap is byte-identical to `b79a799b`. Then STOP and report the head commit SHA, the merge commit SHA, the scoreboard rows, and anything recorded as waiting on R6.
