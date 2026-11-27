# Build prompt: 3.0.0 spine final regression, R1 to R8 sweep, and docs audit

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Roadmaps R1 through R8 are implemented and verified on `3.0.0-spine`, including every fix-back and the merge of `3.0.0-spine-r8`. The last implementation head is `fa107755`. Dan held the full regression for the end; this is the end. Your job is the one final pass described in `docs/roadmaps/3.0.0-spine/final-regression.md`: the full Windows regression, the R1 to R8 sweep, and the cross-roadmap docs audit. Fix what fails, at the root. Then stop.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never `3.0.0`, never `3.0.0-spine-r8`, never a 2.x branch. No tags, releases, publishing to a remote repository, pull requests, or force-push.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine` (Dan's Windows PC, DESKTOP-TUUEDJG).
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`, `.\maven-consumer-project\mvnw.cmd`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.
- **Scoreboard (you create it):** `docs/maintainer/3.0.0-spine/final-regression-scoreboard.md`

## Read first

Read these in full before you run anything. The branch docs win over this prompt if they disagree; if they do, follow the docs and say so on the scoreboard.

1. `docs/roadmaps/3.0.0-spine/final-regression.md`.
2. `AGENTS.md`, including the test and validation sections and the functionality-change rules (code, tests, docs, release notes, consumer guidance sync, repository index).
3. `docs/roadmaps/3.0.0-spine/README.md` and every roadmap it links: `R1-core-hydration-spine-universal-entity.md`, `R2-cucumber-as-adapter-decoupling-ingest.md`, `R3-step-definition-language-adapters.md`, `R4-cli-mcp-universal-surface-verb-inventory.md`, `R5-workbench-one-control-plane.md`, `R6-dynamic-mode-branching-promote.md`, `R7-runtime-snapshots-retention-cross-store.md`, `R8-warm-engine-resource-gate-browser-config.md` (its P4 "Full regression" and "Docs" sections define this pass's commands and doc targets).
4. Every scoreboard under `docs/maintainer/3.0.0-spine/` (`R1-scoreboard.md` to `R8-scoreboard.md`, including their Fix-back sections, and `merge-r8-scoreboard.md`).
5. The fix-back and merge prompts under `prompts/`: `build-3.0.0-spine-R5-fixback1.md`, `build-3.0.0-spine-R5-fixback2.md`, `build-3.0.0-spine-R8-fixback1.md`, `build-3.0.0-spine-R8-fixback2.md`, `build-3.0.0-spine-R8-fixback3.md`, `build-3.0.0-spine-merge-r8.md`, `build-3.0.0-spine-R6.md`, `build-3.0.0-spine-R6-fixback1.md`.
6. `docs/plans/2026-11-25-spine-design.md`, `docs/tech-debt.md`, `docs/release-notes.md`, `docs/agent/feature-map.md`, `docs/agent/change-checklist.md`.

## Platform and CI (quoted word for word from `final-regression.md`)

### Platform (Dan, 2026-11-26)

- Run the final regression only on Dan's Windows PC (DESKTOP-TUUEDJG), locally in the `3.0.0-spine` worktree.
- No Linux or macOS desktop runs for this pass, and no GitHub Actions or workflow runs. Do not use `workflow_dispatch`, and do not open or update a pull request or push to a CI-triggering branch to get CI results. This conserves Grok Bot and GitHub resources.
- Informal spot checks on another platform are allowed only if they are easy. They are not part of the pass and are not recorded as results.
- Heavier Linux and macOS desktop testing is deferred to a later cycle. It stays in the Linux and macOS section of `docs/tech-debt.md`.

### CI jobs that must not be triggered for this pass

`.github/workflows/agent-contract.yml` runs on any `pull_request` and on `push` to `master`, `2.2.0`, and `3.0.0`. It has a `macos` job (`macos-latest`), a `dom-engine-live` job (`ubuntu-latest`), and a `gate` job that needs both. `.github/workflows/sqlite-index.yml` runs on any `pull_request` and on `push` to `master`, `2.1.18`, and `3.0.0` (Windows only). Pushes to `3.0.0-spine` trigger neither workflow. A pull request from `3.0.0-spine`, or a push to `3.0.0`, would trigger both, including the macOS and Linux jobs. Neither happens during this pass.

In short: Windows PC only; no Linux or macOS runs; no GitHub Actions or workflow runs; no pull request and no push to `3.0.0` (or any CI-triggering branch) during this pass.

## Part A: full regression on Windows

Run exactly the commands in R8 P4 "Full regression", in this order, each on its own, in the `pkb-spine` worktree:

```
.\gradlew.bat :test --console=plain
.\gradlew.bat :dom-engine:test --console=plain
.\gradlew.bat :pickleball-workbench:test --console=plain
.\gradlew.bat :pickleball-control-protocol:test --console=plain
.\gradlew.bat :pickleball-control-api:test --console=plain
.\gradlew.bat publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
```

Then:

```
.\gradlew.bat verifyStrictControllerIsolation --console=plain
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

- `publishToMavenLocal` writes only to the local `~/.m2` on this PC. Never publish to a remote repository.
- Maven-local wait rule: before `publishToMavenLocal` or `mvnw`, check whether another worktree on this PC (for example `pkb-spine-r8` or `pkb-spine-r7`) has a Gradle publish or `mvnw` running. If so, wait, re-checking every 120 s for up to 60 min, and record the wait on the scoreboard. Do not kill other processes.
- Record for every command: total, passed, failed, skipped, and duration, from the Gradle or Surefire reports (`build/test-results`, `maven-consumer-project/target/surefire-reports` or the Cucumber report). Skipped counts must be explained (which tests, why, and whether the skip is in the code base already or OS-gated).
- After any fix, re-run the failing class or scenario first, then re-run the full command that failed, until that command is green. Before you finish, every command above must have a green run on the final head, or an honest, evidenced failure you could not fix (see "Stop").

## Part B: R1 to R8 sweep

For every roadmap R1 to R8, every phase and every item (including each fix-back item and each merge-r8 item), confirm in the code on the current head that:

1. The feature is present and reachable through its production caller (the CLI via `PickleballWorkbenchLauncher` / `VerbDispatch`, `StoreCli`, `EngineCli`, `SpineCli`, `NativeScenarioRun`, `ExecutionContext.open`, the warm loader, `DesktopTree`, the Node renderer and its `/store` bridge, and so on), not only through a test hook, an in-process seam, or a docs claim. An item reachable only that way is **hollow** and is a gap.
2. The tests that the roadmap or scoreboard names for it exist, are not weakened, and passed in Part A (name the test classes).
3. The scoreboard SHA for it exists on the branch.

Fix every hollow item or missing test at the root, with a test that fails before and passes after through the production caller. Items already logged in `docs/tech-debt.md` as known open follow-ups (`Spine merge follow-ups`, `Spine R6 follow-ups`, the TypeScript package reinvestigation, and the Linux and macOS section) stay logged; do not silently close them and do not record them as passes. If Part A or the sweep shows one of them is an actual failure, fix it and update the line.

## Part C: docs audit

- `AGENTS.md`, the consumer guidance under `src/main/resources/META-INF/pickleball/guidance/`, and the docs under `docs/` describe only the current implemented design. Remove or rewrite stale references in place (for example the Swing window as current design, superseded verb names, `awaiting Dan` wording, behaviour that a later fix-back changed). History goes only in `docs/release-notes.md`.
- Follow R8 P4 "Docs": `AGENTS.md` matches the shipped warm, gate, and browser behaviour; `docs/tech-debt.md` has no leftover from the README mapping except the Linux and macOS section and the explicitly logged open follow-ups; `docs/release-notes.md` has one paragraph per roadmap that landed, written as current behaviour plus a short history line; feature-map rows that name relations, store, engine, or the workbench match the code.
- Append every Windows-only live class from R1 through R8 that did not run on Linux or macOS to the existing Linux and macOS section of `docs/tech-debt.md`. Do not remove classes already listed.
- Do not edit the roadmap files under `docs/roadmaps/3.0.0-spine/` to match the code. If a roadmap and the code disagree, fix the code, or record the disagreement on the scoreboard for Dan.
- Guidance copies are synced (`python scripts/sync_consumer_guidance.py`), the index is refreshed (`python scripts/refresh_agent_index.py`), the `--check` forms pass, and `python scripts/verify_agent_contract.py` exits 0. Regenerate `documented-help.txt` and `docs/verb-inventory.md` with their existing update switches if help or verbs change.

## Rules

- Every failure is fixed at the root, with a reproducing test that fails before the fix and passes after. Record the before failure and the SHA on the scoreboard.
- Never weaken, skip, disable, or delete a test. No `@Disabled`, no new `assumeTrue`/`assumeFalse` skips, no empty tests, no loosened assertions or timeouts to make a test pass. Keep Gherkin syntax. Step annotations stay single string literals.
- Flaky or environment-only problems (for example a port in use, a missing local browser, a Windows timing issue you can reproduce as environmental) are logged in `docs/tech-debt.md` under a section `Final regression findings (open, 2026-11-27)` with evidence (test name, command, the error text, how often it reproduced). They are not recorded as passes. Re-run a suspected flake at least twice and record each result.
- Push only to `3.0.0-spine`, after each fix. Before every push, each command on its own (do not chain with `&&`, `;`, or a pipe):

```
python scripts/sync_consumer_guidance.py
python scripts/refresh_agent_index.py
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Never push to `master`, `3.0.0`, or `3.0.0-spine-r8`. No force-push, no tags, no releases, no pull request, no workflow runs.
- Never touch the `pkb-spine-r8` or `pkb-spine-r7` worktrees.
- Do not start new feature work beyond what a failing test, a hollow item, or a stale doc requires.
- Stop only on an honest failure you cannot fix without weakening a test, or on a hard-rule violation. Record it on the scoreboard with evidence and keep going with the rest of the pass where you can.

## Scoreboard

Create `docs/maintainer/3.0.0-spine/final-regression-scoreboard.md` in your first commit and update it as you go. It must contain:

1. **Test totals:** one row per Part A command, with total, passed, failed, skipped, duration, the head SHA it ran on, and the report timestamp. A first-run row and a final-run row for each command.
2. **Failures and fixes:** one row per failure: test or scenario, the error, root cause, fix SHA, the reproducing test, and before/after results.
3. **Sweep table:** one row per roadmap phase or item (R1 to R8, each fix-back item, each merge-r8 item): status (`present`, `fixed`, or `gap`), production caller, test classes, and the scoreboard SHA.
4. **Docs-audit list:** each stale reference found, the file, and the fix SHA.
5. **Environment and flake log:** pointers to the tech-debt lines.

Also add one line to the R8 scoreboard P4 notes pointing to this scoreboard for the full-regression counts.

## Finish

You are done when every Part A command has a green run on the final head (or an honest, evidenced failure on the scoreboard), the sweep table has no unexplained `gap`, the docs audit is complete, `verify_agent_contract.py` exits 0, the `--check` scripts pass, the working tree is clean, and `origin/3.0.0-spine` equals your HEAD. Then STOP and report the head commit SHA, the test totals table, and any open items.
