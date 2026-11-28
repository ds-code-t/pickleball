# Build prompt: 3.0.0-spine configurable test parallelism on Dan's PC

This prompt stands alone. You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Your job is to make the test suites fast on this 24-core, 64 GB Windows PC with a configurable fork count, without weakening or skipping any test, then measure `:test` at 24, 48, and 80 forks and record the fastest stable setting. Then STOP and report the head.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine-parallel-tests` only. Never `master`, never `3.0.0`, never `3.0.0-spine`, never `3.0.0-spine-doc-gaps`, never a 2.x branch. No tags, releases, publishing to a remote repository, pull requests, or force-push.
- **Start head:** `cf3da2bfe` on `origin/3.0.0-spine`. Create and stay on `3.0.0-spine-parallel-tests` from that head if you are not already on it.
- **Worktree:** `C:\Users\Admin\IdeaProjects\pkb-spine-parallel-tests` (Dan's Windows PC, DESKTOP-TUUEDJG). Do not use `pkb-spine`, `pkb-spine-r7`, `pkb-spine-r8`, `pkb-spine-doc-gaps`, or any other worktree.
- **Toolchain:** JDK 21. Windows commands. `.\gradlew.bat`, `.\maven-consumer-project\mvnw.cmd`. Never run `.\gradlew.bat --stop` or `./gradlew --stop`. Use `python`, not `python3`.
- **Scoreboard (you create it):** `docs/maintainer/3.0.0-spine/parallel-tests-scoreboard.md`

## Read first

Read these in full before you change anything. The branch docs win over this prompt if they disagree; if they do, follow the docs and say so on the scoreboard.

1. `AGENTS.md` (test policy, functionality-change rules).
2. `docs/roadmaps/3.0.0-spine/final-regression.md` (three tiers, batching, current commands).
3. `docs/tech-debt.md`.
4. Root `build.gradle` around the `allprojects { tasks.withType(Test) }` block (today `maxParallelForks = 1`, `maxHeapSize = '1536m'`, `minHeapSize = '128m'`), `gradle.properties` (`org.gradle.parallel=false`, `org.gradle.jvmargs=-Xmx768m`), `dom-engine/build.gradle` (`maxParallelForks = 1`), other module test blocks, and `.github/workflows/agent-contract.yml` plus `sqlite-index.yml` (Windows CI must stay modest).
5. How tests share state: `LocalTestSite` and port `8765`, store directories under `.pickleball` or temp project dirs, debug / CDP ports, warm JVM / `spine start`, browser and popup hosts.

## Hard rules

- Windows PC only. No Linux or macOS desktop runs. No GitHub Actions or workflow runs. No pull request. No push to `3.0.0` or any other CI-triggering branch.
- Do not run the **full** regression. Do not run the **short** (Chrome + Windows) regression as a sign-off pass. Measuring `:test` at three fork counts is a benchmark of that one suite, not the short or full tier. When the setting is recorded and the last targeted tests are green, STOP and report the head.
- **Batching (Dan, 2026-11-27):** if a fork-count run or a targeted test fails, let the run finish and collect every failure. Fix them all at once where practical, each at the root with a reproducing test that fails before and passes after. Run all of those targeted tests together. Do not start the next measured `:test` until that whole batch passes. Never weaken, skip, or delete a test. Never move a failure into an assumption, `@Disabled`, or a looser assertion to gain speed. Keep Gherkin syntax; step annotations stay single string literals. No secrets in cleartext.
- **Parallelism (Dan, 2026-11-27):** this Build exists to make tests on this PC as fast as possible with a high fork count (aim for 50 to 80 where the suite is stable, otherwise the highest count that memory and stability allow). Light tests should use a smaller heap so more forks fit in 64 GB. Isolate tests that cannot run side by side.
- **File ownership, to avoid overlap with `3.0.0-spine-doc-gaps`:** you may edit `build.gradle`, module Gradle files, `gradle.properties`, consumer `pom.xml` / Surefire config, `.github/workflows/*` only to keep Windows CI modest, `docs/roadmaps/3.0.0-spine/final-regression.md`, `docs/tech-debt.md` only for parallelism notes, and one release-notes paragraph that starts with `Test parallelism.`. Do not edit `WorkbenchCliHelp`, help goldens, `VerbTiers`, `AGENTS.md` human-tier wording, or the manual-collaboration catalog. Do not rewrite the three-tier lead or the `Docs and help.` paragraph.
- After every green change: run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py` if docs or guidance changed, confirm `python scripts/verify_agent_contract.py` exits 0, commit, `git fetch origin`, rebase onto `origin/3.0.0-spine` (and onto `origin/3.0.0-spine-parallel-tests` if that remote branch already has your earlier commits), then `git push origin HEAD:refs/heads/3.0.0-spine-parallel-tests`. Never push to `3.0.0-spine`. Never force-push.
- Never touch grok pids 48332 or 41064, or java pids 48240 or 50896, or the other IdeaProjects worktrees. Never run `gradlew --stop`.

## Work items

1. **Configurable forks.** Add `-Ppkb.test.forks` (exact name) that sets `maxParallelForks` on every `Test` task, including `dom-engine` and the other modules. Default is `1`, so CI and the box stay as they are unless a workflow or a developer opts in. On this PC, the wrappers and the commands you record will pass a high value. Keep Windows CI modest (leave the default, or set an explicit low number in the workflow if a job already overrides it). Do not raise default heap or default forks for GitHub Actions.

2. **Heap for light tests.** Today every test worker may take up to 1536m, plus `minHeapSize = 128m`. Tune this so light tests do not reserve the ceiling, and so 24 to 80 forks can fit in 64 GB beside the Gradle JVM. Keep a high enough ceiling for heavy tests (live Chrome, `SuiteDualRunTest`, store spikes) so they do not OOM. Record the rule you chose (per-test `maxHeapSize`, a heavy-test list with a larger heap, or another honest scheme).

3. **Isolate parallel-unsafe tests.** Known shared resources include `LocalTestSite` port 8765, shared store directories, debug / CDP ports, and the warm JVM. Put those tests in a serial group, or give each worker a unique port, temp directory, and store root. Never drop or weaken a test to make parallelism work. If a test needs the warm JVM, it must not collide with another worker's warm JVM.

4. **Consumer `mvnw` parallelism, if feasible.** If Surefire or the consumer surefire config can run in parallel without breaking `@all` or shared port 8765, add a property with a safe default of 1 and a documented high value for this PC. If it is not feasible, record why on the scoreboard and leave the consumer serial.

5. **Measure `:test` on this PC.** After the mechanism is in and a small targeted smoke (a few known-safe classes plus one previously unsafe class) is green at 2 and 8 forks, run:

```
.\gradlew.bat :test --console=plain -Ppkb.test.forks=24
.\gradlew.bat :test --console=plain -Ppkb.test.forks=48
.\gradlew.bat :test --console=plain -Ppkb.test.forks=80
```

Use `--rerun-tasks` (or an equivalent that actually executes the tests) so a cached UP-TO-DATE run is not treated as a measurement. Record wall time, total / passed / failed / skipped, peak memory if you can read it honestly, and any flake or collision. If 80 is unstable or memory-bound, also try a value between 48 and 80, or stop at the highest stable count and say why. You do not have to re-run module suites or `mvnw @all` as a sign-off. If you have time after the `:test` measurements, a single timed `:dom-engine:test`, workbench, protocol, and control-api run at the winning fork count is useful; it is not a short or full regression.

6. **Record the setting.** Put the fastest stable `:test` fork count, the heap rule, and the exact command lines into `docs/roadmaps/3.0.0-spine/final-regression.md` so the short-tier and full-tier Windows commands use `-Ppkb.test.forks=<winning>` (and the consumer flag if you added one). Default remains 1 when the property is omitted. Add the `Test parallelism.` release-notes paragraph. If some tests must stay serial, name them in `docs/tech-debt.md` only if the isolation is incomplete; otherwise name the serial group in `final-regression.md`.

## Tests

- After the Gradle and isolation changes, run targeted tests that prove forks work and that a previously unsafe test is isolated (for example the class that binds 8765, plus a smoke of ordinary unit tests at `-Ppkb.test.forks=8`).
- Then do the three measured `:test` runs. Those measurements are required. They are not permission to run the short or full regression (no `mvnw @all` sign-off, no Linux or macOS, no CI).
- If a measured run fails, batch the fixes, run the fixes' targeted tests together, and only then repeat that fork count. Do not advance to the next count on a red run.
- After any docs change, confirm `python scripts/verify_agent_contract.py` exits 0 and the two `--check` scripts pass.

## Stop

Stop when the property is in, isolation is honest, the three (or best-effort) measurements are recorded, `final-regression.md` has the winning command, the branch is pushed, and the last targeted tests are green. Do not start the short or full regression. End with a scoreboard: branch, head SHA, winning `-Ppkb.test.forks` value, wall times at 24/48/80, tests, remaining gaps, blocker if any.
