# Regression tiers — 3.0.0-spine

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

There are three regression tiers (Dan, 2026-11-27). Between phases and fixes, run targeted tests only: the classes and scenarios the change touched.

| Tier | Scope | When it may run |
|---|---|---|
| **Full** | Every suite and every function. Every browser the branch supports. Windows, Linux, and macOS. The consumer project. The CI-equivalent checks. Live and real-browser tests, headed and headless. | Never, unless Dan explicitly asks for the full regression. |
| **Short (Chrome + Windows)** | The Windows suites on Dan's PC, limited to the Chrome browser. | The default end-of-work regression. It runs only when 100% completion is possible: every roadmap is implemented, and every phase and every fix has been verified with its targeted tests. |
| **Manual human collaboration (PKB-HCR)** | [PKB-HCR](manual-collaboration-regression.md): a numbered catalog of 75 agent-run instructions (HCR-01 through HCR-75) on a live headed Chrome page. Invoke with `Run PKB-HCR`, `Run PKB-HCR HCR-12`, or `Run PKB-HCR paired only`. The agent plays the human collaborator (Human Mode tools and the human view only) or the agent (every tool, including consumer direct control, HTML injection, listeners, and the DevTools interfaces). | The whole catalog: never, unless Dan explicitly asks (`Run PKB-HCR`). A single test: without asking, when it covers functionality that a new implementation may have impacted, or when the request names it. |

## Short regression (Chrome + Windows)

### When

Run it only at the end of the whole implementation. All roadmaps must be implemented, and every phase and every fix individually verified with targeted tests. Do not run it to find out how far along the work is.

### Platform (Dan, 2026-11-26)

- Run the short regression only on Dan's Windows PC (DESKTOP-TUUEDJG), locally in the `3.0.0-spine` worktree.
- No Linux or macOS desktop runs for this pass, and no GitHub Actions or workflow runs. Do not use `workflow_dispatch`, and do not open or update a pull request or push to a CI-triggering branch to get CI results. This conserves Grok Bot and GitHub resources.
- Informal spot checks on another platform are allowed only if they are easy. They are not part of the pass and are not recorded as results.
- Heavier Linux and macOS desktop testing belongs to the full tier. It stays in the Linux and macOS section of `docs/tech-debt.md`.

### Chrome only

- Google Chrome must be installed on the PC. Four live classes fall back to Edge when Chrome is missing: `OverlayAckLiveTest`, `PageJvmRouteTest`, `BidiCliSessionTest`, and the `SpineR6PageLive` classes (`SpineR6FixBack1DiscardTest`, `SpineR6FixBack1PageControlsTest`).
- On Windows the popup host and the Node renderer window prefer Edge (`PopupBrowserLocator` reads the registry). Set `PKB_AGENT_POPUP_BROWSER` to the Chrome binary in the shell that runs Gradle. Then `AgentSurfacePopupLiveTest`, `PopupChatLiveTest`, and the renderer window use Chrome. Tests that set `pkb_agent_popup_browser` themselves still win.
- The consumer run uses `-Dpkb_runvars.pkb_browser=CHROME_HEADLESS`.
- Firefox and Edge live methods have no exclusion filter. On Dan's PC they skip today only because of the environment: Firefox is not installed, and the Edge snapshot lookup reads Linux paths only. Record each skip in the scoreboard. The missing filter is logged in `docs/tech-debt.md` under `Regression tier filters (open, 2026-11-27)`.
- Classes that only build Edge or Firefox options, or resolve versions, in process (for example `JavaScriptDialogTest`, `BrowserVersionResolverTest`, `RunVarMatrixParityTest`) stay in. They start no browser.

### Commands

Run them in this order, each on its own, in the `3.0.0-spine` worktree. On Dan's Windows PC, pass the fork properties below. CI and the Linux box keep the default of 1 fork (omit the `-P` flags).

On 2026-11-27, `:test --rerun-tasks -Ppkb.test.forks=24` on the PC took about 8 minutes: 2118 tests, 79 failed in 60 classes. Those 60 classes rerun at 12 forks: 497 ran, 24 failed (20 classes). The same 24 methods rerun at 1 fork: 96 ran, 0 failed. They are parallel-contention failures (live Chrome/CDP attach, Playwright CDP, timing budgets, popups), not product bugs. Those 60 classes now run in `:testLive` at `-Ppkb.test.liveForks` (default 1). `:test` excludes them and may use `-Ppkb.test.forks=24`. `:test` then runs `:testLive` and `:testSerial` (`SuiteDualRunTest`, port 8765 and the repo calls link). Do not raise `liveForks` above 1 unless a later measurement shows it is safe for every class in that group, including the 20 that failed at 12 forks.

```
$env:PKB_AGENT_POPUP_BROWSER = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
.\gradlew.bat :test -Ppkb.test.forks=24 -Ppkb.test.liveForks=1 --console=plain
.\gradlew.bat :dom-engine:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat :pickleball-workbench:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat :pickleball-control-protocol:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat :pickleball-control-api:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat verifyStrictControllerIsolation --console=plain
.\gradlew.bat publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

Use the real Chrome path if it differs. `publishToMavenLocal` writes only to the local `~/.m2`. `@all` is allowed here because this is the end of the work, not a Workbench migration check. The `:test` line already runs `:testLive` and `:testSerial` after the fork pool; you do not need to name those tasks separately. `--tests` still routes a class to `:test`, `:testLive`, or `:testSerial`.

The end-of-work pass also sweeps every roadmap item, and the fix-backs, through its production caller, and audits the docs. Record the results in `docs/maintainer/3.0.0-spine/final-regression-scoreboard.md`.

## Failures: batch the fixes (Dan, 2026-11-27)

This applies to every tier.

1. Let the tier run to the end and collect every failure.
2. Fix them all at once if practical. Fix each at the root with a reproducing test that fails before and passes after. Never weaken, skip, or delete a test.
3. Run the targeted tests for all the fixes together.
4. Do not run the tier again until every targeted test for every fix passes in that one batch. Do not rerun the tier after each single fix.
5. Then run the short tier again. Run the full tier again only if Dan asks. For the manual tier, re-run only the manual tests that failed, unless Dan asks for the whole catalog.

Flaky or environment-only problems go in `docs/tech-debt.md` with evidence, not as passes.

## Manual human-collaboration regression (PKB-HCR)

**PKB-HCR** (pickleball Human Collaboration Regression) is the tag for this tier. The catalog, roles, setup, invocation phrases, and the table for picking targeted tests are in [PKB-HCR](manual-collaboration-regression.md). The results template is `docs/maintainer/3.0.0-spine/manual-collaboration-scoreboard-template.md`. After `export-guidance` a consumer agent loads `.pickleball/PKB-HCR.md`. It is not part of the full or short tier, and neither of them triggers it. The batching rule above applies to its failures.

## Full regression

### When

Never, unless Dan explicitly asks for the full regression. Finishing the roadmaps does not trigger it. A short-tier pass does not trigger it either.

### Scope and commands

Run everything below on each platform. Record each command's totals and every skip with its reason. Linux and macOS commands stay at the default of 1 fork unless a later measurement on that platform says otherwise.

**Windows (Dan's PC).** Install Chrome, Edge, Firefox with `geckodriver` on `PATH`, and PowerShell 7 (`pwsh`). Leave `PKB_AGENT_POPUP_BROWSER` unset so the popup host uses its Windows default. The same fork properties as the short tier are recommended: `-Ppkb.test.forks=24 -Ppkb.test.liveForks=1` on `:test`, and `-Ppkb.test.forks=24` on the module test tasks.

```
.\gradlew.bat :test -Ppkb.test.forks=24 -Ppkb.test.liveForks=1 --console=plain
.\gradlew.bat :dom-engine:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat :pickleball-workbench:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat :pickleball-control-protocol:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat :pickleball-control-api:test -Ppkb.test.forks=24 --console=plain
.\gradlew.bat verifyStrictControllerIsolation --console=plain
.\gradlew.bat publishToMavenLocal
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME -Dpkb_runvars.pkb_tags=@all
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=EDGE -Dpkb_runvars.pkb_tags=@all
.\scripts\consumer_sim.ps1
python scripts/verify_agent_contract.py
python scripts/refresh_agent_index.py --check
python scripts/sync_consumer_guidance.py --check
```

**Linux.** Install Chrome, Edge, Firefox with `geckodriver`, `xvfb`, and the AT-SPI bus. Run `:dom-engine:test` under `xvfb-run` with the AT-SPI bus up, as the `dom-engine-live` CI job does.

```
./gradlew :test --console=plain
./gradlew :dom-engine:test --console=plain
./gradlew :pickleball-workbench:test --console=plain
./gradlew :pickleball-control-protocol:test --console=plain
./gradlew :pickleball-control-api:test --console=plain
./gradlew verifyStrictControllerIsolation --console=plain
./gradlew publishToMavenLocal
./maven-consumer-project/mvnw -f maven-consumer-project/pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@all
(cd maven-consumer-project && mvn -B test -Dpkb_tags=@dom-composite -Dpkb_engine=native -Dpkb_composite=on -Dpkb_runvars.pkb_browser=CHROME_HEADLESS)
(cd maven-consumer-project && mvn -B test -Dpkb_tags=@dom-composite -Dpkb_engine=cucumber -Dpkb_composite=on -Dpkb_runvars.pkb_browser=CHROME_HEADLESS)
```

Then run headed (not headless) reruns of the live collaboration and push classes listed in the Linux and macOS section of `docs/tech-debt.md`.

**macOS.** Run the same Gradle tasks and consumer `@all` as on Linux. Include `MacAccessibilityScriptTest` and `SnapshotCoverageTest.liveDesktopWhenThePlatformExists` with Accessibility granted, the live Chrome and Edge suites, a live Safari (`safaridriver`) run, and the process-tree checks listed in `docs/tech-debt.md`.

**CI-equivalent checks.** Run the steps of the `windows`, `macos`, and `dom-engine-live` jobs in `.github/workflows/agent-contract.yml`, and the `sqlite-index.yml` step, locally on the matching platform. Trigger GitHub Actions only if Dan's request for the full regression says so.

**Remote browsers.** `GRID_*`, `REMOTE_*`, and `SAUCE_*` consumer configs need outside infrastructure. Run them only if Dan provides it. Otherwise record them as not run.

## CI jobs that must not be triggered unless Dan asks

`.github/workflows/agent-contract.yml` runs on any `pull_request` and on `push` to `master`, `2.2.0`, and `3.0.0`. It has a `windows` job (`windows-latest`), a `macos` job (`macos-latest`), a `dom-engine-live` job (`ubuntu-latest`), and a `gate` job that needs all three. `.github/workflows/sqlite-index.yml` runs on any `pull_request` and on `push` to `master`, `2.1.18`, and `3.0.0` (Windows only). Pushes to `3.0.0-spine` trigger neither workflow. A pull request from `3.0.0-spine`, or a push to `3.0.0`, would trigger both, including the macOS and Linux jobs. Neither happens during a short-tier pass.

## Build prompts

A Build prompt for the short tier quotes the "Platform" section and the "CI jobs" section above word for word. It names the tier and includes the batching rule. A prompt for the full tier is written only after Dan asks for the full regression.
