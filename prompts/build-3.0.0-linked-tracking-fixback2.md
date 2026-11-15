# Grok Build Prompt: Pickleball 3.0.0 Linked Tracking — Fix-back 2 (finish everything)

You are Grok Build. Linked tracking (docs/roadmaps/3.0.0-linked-tracking-roadmap.md, design docs/maintainer/3.0.0-linked-tracking-design.md) is implemented on branch 3.0.0, and Fix-back 1 landed at 5b3158d1 (scoreboard a69c9af3). An independent verification at a69c9af3 found the residual gaps R1–R5 below. It also reproduced three red or flaky tests, T1–T3. The owner approved finishing ALL of them. Fix every row on branch 3.0.0 with real tests that would have caught it. Work only on these rows.

## HARD GUARDRAILS (must follow every phase)

- Work only on branch `3.0.0`. NEVER commit, push, or merge to `master`, `2.2.0`, or `2.1.19`. No force-push, no tags, no releases, no publishing. Version stays 3.0.0.
- Do not change existing Gherkin syntax. Do not weaken, skip, or delete tests. Cucumber step annotations stay single string literals with identical values.
- Do not touch open PRs #2 and #3 on `ds-code-t/dom-composite`.
- CLI-first: every capability is reachable from the Dalton/Pickleball CLI. Workbench, MCP, Gradle tasks and any HTTP surface only forward to the CLI. The Workbench never opens SQLite or uses Dalton classes directly.
- Logging for new code: toggleable, per-entity, ISO date-stamped, size-capped, following the existing `pkb_dalton_log` / `DaltonLog` pattern.
- Docs are rewritten IN PLACE to describe only what is currently implemented (AGENTS.md included). No append-only notes and no stale or superseded wording. History goes to `docs/release-notes.md`.
- Regenerate `docs/agent/repository-index.md` only with `python3 scripts/refresh_agent_index.py`. Before every push, run `python3 scripts/refresh_agent_index.py --check`, `python3 scripts/verify_agent_contract.py`, and `python3 scripts/sync_consumer_guidance.py --check`.
- Pull the tip before each push. Push every completed change to `origin/3.0.0`; never leave work unpushed.
- Tests: after each phase, run only SHORT TARGETED tests (`timeout 600 ./gradlew <module>:cleanTest <module>:test --tests <filter>`), named per phase in the roadmap. Do NOT run the full multi-module regression, `validate --all`, Maven `@all`, or first-start timing. The owner is holding those.
- Never write placeholder or 0-byte files. Verify file sizes and contents before claiming success.
- Secrets: never store cleartext secret values in any new table, log, timeline, or dashboard. Mask through the existing secrets approach.

## DOC SAFETY (added by the coordinator after the design push)

- The design push (210acc83/cf8d56c4) replaced `AGENTS.md` (about 66 KB) and `docs/agent/feature-map.md` (about 62 KB) with placeholder text, which broke Agent Contract. Both were restored in 4634e720. Never replace, truncate, or regenerate either file wholesale. Edit only the specific paragraphs your phase changes, keep every other line byte-for-byte, and check before every push that neither file shrank by more than a few hundred bytes (`wc -c AGENTS.md docs/agent/feature-map.md`).
- AGENTS.md must keep the rules on dom-composite PRs #2 and #3, Query.run with frame/shadow/pierce rejected on desktop, the empty breaking-change list, and the Workbench controller guidance that `verify_agent_contract.py` and the Windows consumer check look for (for example `pickleball-control-protocol`, `Pickleball may contain Workbench`, `Workbench must not contain Pickleball`, `@control-bridge`, `pkb_parallel=80`).
- When a phase lands, replace the "Planned, not implemented yet: linked tracking" bullet under `## Dalton` in AGENTS.md with an accurate description of what is now implemented.
- Known pre-existing red test, not yours unless your phase touches `GherkinRewrite`: `GherkinRewriteTest.addModifyAndDeleteKeepCommentsAndValidGherkin`.

### Coordinator note for this fix-back

- T1 below brings `GherkinRewriteTest.addModifyAndDeleteKeepCommentsAndValidGherkin` IN SCOPE. The "known pre-existing red test" line above no longer excuses it. After T1 lands, remove that wording wherever the branch docs still call it a known red test (if any do).
- The "Planned, not implemented yet" bullet is already gone from AGENTS.md. Edit only the `## Dalton` paragraphs that your rows change.

## START PROCEDURE

1. `git fetch origin 3.0.0` and check out the CURRENT tip of `origin/3.0.0` (expected a69c9af3, or a later tip that holds this prompt). Record the SHA you start from.
2. Read AGENTS.md (`## Dalton` and the Workbench/MCP rules), the linked-tracking design and roadmap, `docs/dalton-entities.md`, `docs/release-notes.md` (3.0.0 entry), and the code cited below. The branch docs win over this prompt wherever they disagree. Line numbers are from a69c9af3. Re-locate them if the tip moved.
3. Reproduce T1, T2 and T3 with the targeted commands below before you change anything, so that you know what red looks like.

## ROWS TO FIX (each verified with file and line evidence at a69c9af3)

### R1. Repeated `PickleballEngine.run` calls in one JVM merge into one Dalton run
- Evidence:
  - `PickleballEngine.java:41-79` (`run(ScenarioSource, EngineOptions)`) loops `runScenario` → `NativeScenarioRun.run` and never closes the tracked scenario.
  - `NativeScenarioRun.java:121` calls `DaltonExecution.beforeStep` and `:146` calls `afterStep`. Nothing on this path calls `DaltonExecution.finishScenario` (`DaltonExecution.java:242-262`, the only place that does `ACTIVE.remove()`). The only callers are `NativeSuiteExecution.java:161` and `DaltonRunTrace.java:49`.
  - `DaltonExecution.runId()` (`:311-323`) mints a new id and then `System.setProperty("pkb_run_id", created)`, so every later in-process run sees it as a caller-set id.
  - `active(...)` (`:336-347`) reuses the leftover thread-local `ACTIVE` whenever project, examples row and scenario key/name match (for example `memory:|2`).
- Probe (one JVM, caller never set `pkb_run_id`):
  - Run A PASSED, then run B FAILED, left ONE run row, `20261115-040200Z-ba48bd59 | FAILED`.
  - A third run, after clearing `pkb_run_id` the way the warm worker does, still merged into that row.
  - After a single run, `ACTIVE` was still set on the thread.
- Not affected: `dalton run` (fresh worker per run), Gradle/Maven, and the engine CLI process. Affected: in-process API users (tests, ParityHarness/DualRun, embedders).
- Fix:
  - Close the tracked scenario at the end of every scenario the native API runs, with the scenario's real status. Do not double-finish on the `NativeSuiteExecution` path (`finishScenario` is a no-op once `ACTIVE` is cleared; keep the plugin status authoritative there).
  - Mint the run id per top-level `PickleballEngine.run`. Keep honoring a caller-set `pkb_run_id` and an active `DiagnosticRuntime.runId()`, and keep sharing the minted id with a diagnostic reporter that starts during that run.
  - At the end of the top-level run, do not leave a self-minted id behind as if the caller had set it (restore the prior value, or track that it was minted). Nested runs (`ExecutionContext.current() != null`) stay inside the outer run.
- Acceptance test: in DaltonExecutionRecordingTest (or a new class), three `PickleballEngine.run` calls in one JVM with no caller `pkb_run_id` (PASS, FAIL, PASS, including two that reuse the same in-memory scenario key) give THREE run rows with statuses PASSED, FAILED, PASSED and their own scenario_executions. A fourth run with an explicit `pkb_run_id=caller-x` writes to `caller-x`. After each run, `ACTIVE` is empty (no step context leaks).

### R2. `--tag` and `--feature` select whole runs, not scenarios
- Evidence: `DaltonTrack.java:196-201` (`--feature`) and `:202-216` (`--tag`) OR-include `run_id IN (SELECT e.run_id FROM scenario_executions e ...)`. So every step of every run that touched a matching scenario passes the filter.
- Probe: every run touched both pay.feature and other.feature. `query --feature=other.feature` returned 120/120 events, including the 60 pay.feature steps. `query --tag=@fast` (only pay.feature's scenario is tagged) also returned all 120.
- Fix: narrow scenario, step, usage and log rows to the matching scenario keys. Run-level rows may stay (a run row has no scenario key) only if that is documented in place in the design, AGENTS.md `## Dalton` and the CLI help. Keep it in SQL, with no in-memory filtering.
- Acceptance tests: in DaltonQueryCliTest and DaltonTimelineTest, with two runs that each execute a pay.feature scenario tagged `@fast` and an other.feature scenario:
  - `--feature=other.feature` returns no step or scenario row whose scenario_key starts with `features/pay.feature`.
  - `--tag=@fast` returns no other.feature step rows.
  - The counts match the expected scenario-scoped numbers exactly.
  - Assert the same narrowing through `dalton timeline --json`.

### R3. `total` changes meaning with `--cursor`
- Evidence: `DaltonTrack.java:81`, `int total = count(connection, filter, timeline, after, null);`, passes the forward cursor, so `total` counts only events after the cursor. With `--latest`/`--before`/offset, `total` is the full filtered count.
- Probe: 132 events. Paging with `--cursor` reported total=50. Other pages reported 132.
- Fix: make `total` the full filtered count on every page (count without the cursor). If a "remaining" number is useful, add a separately named field. Document it in place.
- Acceptance test: DaltonTimelineTest pages forward with `--cursor` and back with `--before` over more than 2 pages, and asserts that `total` stays equal to the full filtered count on every page.

### R4. Garbled release-note sentence
- Evidence: `docs/release-notes.md:35` reads "Each tracked run gets its own diagnostic-shaped id unless `pkb_run_id` is already set, and that id is the configured `pkb_dalton_project` database."
- Fix: rewrite it to say what the code does. Example: "Each tracked run gets its own diagnostic-shaped id unless `pkb_run_id` or an active diagnostic run id is already set. Rows go to the configured `pkb_dalton_project` database." After R1, also state that each in-process `PickleballEngine.run` gets its own id. Check AGENTS.md and the design for the same wording.
- Acceptance: the sentence is accurate, and no doc claims more than the code does.

### R5. Open in Explorer with a blank run id (or no Explorer) returns silently
- Evidence: `WorkbenchFrame.java:1300-1306` `openRecordedRun` skips a blank `runId` with no message. `showDiagnosticRun(String,long)` at `:1314-1315` returns silently when `diagnosticView` or `diagnosticNavigator` is null. The only test is `WorkbenchTrackingForwarderTest.java:138`, which asserts the static string from `WorkbenchFrame.unmatchedDiagnosticRun("sample-run")`, not the UI path.
- Fix: a blank run id shows a visible status (for example `Select a run with a run id first`). An Explorer that isn't ready shows `Explorer is not ready` (or similar), through the same status or gap surface the Workbench already uses. Do not add SQLite or Dalton imports to the Workbench.
- Acceptance test: extend WorkbenchTrackingForwarderTest (or the existing Workbench UI test style, headless-safe) to drive `openRecordedRun` / the Tracking "Open in Explorer" action for three cases (blank id, unknown id, Explorer not initialised). Assert the status or gap payload that is actually pushed (for example capture the `window.setDiagnosticState` JSON or the status label text), not only the helper's return value.

### T1. GherkinRewriteTest fails deterministically (Dalton is on by default)
- Reproduced at a69c9af3: `./gradlew --offline :cleanTest :test --tests 'tools.dscode.dalton.GherkinRewriteTest'` gives `2 tests completed, 1 failed`.
  ```
  GherkinRewriteTest > addModifyAndDeleteKeepCommentsAndValidGherkin() FAILED
  org.opentest4j.AssertionFailedError: # keep me
  Feature: Pay
  ...  (file unchanged; no "Scenario: Two")
   ==> expected: <true> but was: <false>      at GherkinRewriteTest.java:51
  ```
- Cause: the test (`GherkinRewriteTest.java:24-102`) writes no `dalton.properties`, so Dalton is on (AGENTS.md:110, "Dalton is on by default"; docs/configuration.md:636). `dalton feature scenario-add` takes the DB-edit path (`dbEdited`), and the test then reads the feature FILE (`:47-53`).
- The designed Dalton-on behavior is already asserted by `daltonOnSetsDbEditedAndAConflictRefusesTheRewrite` (`:104-133`): the DB is edited and the file is untouched.
- docs/roadmaps/3.0.0-workbench-edit-roadmap.md:179 records the design as "Dalton-on db_edited, Dalton-off file, conflict refused".
- Decide the designed behavior from the branch docs and fix it WITHOUT weakening coverage. The expected fix:
  - The file-mode test runs with Dalton explicitly off (`dalton.properties` `enabled=false` in its temp project, or `pkb_dalton=false` scoped and restored), and keeps EVERY existing assertion.
  - Add, or extend, a Dalton-on case that exercises add, modify, step-add/change/remove and delete against the DB copy (`DaltonQuery.file(...)`), with the same comment-preservation and valid-Gherkin assertions, plus the file staying unchanged.
  - If the docs say a file write should happen with Dalton on (for example an export path), assert that path instead. Do not change production behavior unless the docs demand it.
- Acceptance: GherkinRewriteTest is green, with no assertion removed or loosened, and the test count is at least 3.

### T2. WorkbenchMcpServerTest.snapshotCommandReturnsTheCompositeSnapshotShape fails
- Reproduced: `./gradlew --offline :pickleball-workbench:cleanTest :pickleball-workbench:test --tests 'tools.dscode.workbench.mcp.WorkbenchMcpServerTest'` gives `11 tests completed, 1 failed`.
  ```
  WorkbenchMcpServerTest > snapshotCommandReturnsTheCompositeSnapshotShape() FAILED
      java.lang.IllegalArgumentException: error: snapshot arguments do not match dev.domengine.snapshot.CompositeSnapshots.offline.
          java.lang.IllegalArgumentException: error: cannot adapt dev.domengine.tree.DomNode.
  ```
- Cause:
  - `dom-engine` is on the Workbench runtime and test classpath (`pickleball-workbench/build.gradle:91-96`, `runtimeOnly`/`testRuntimeOnly`, since 57202216).
  - `SnapshotCommand.call` (`pickleball-workbench/src/main/java/tools/dscode/workbench/mcp/SnapshotCommand.java:32-48`) picks the first static method named `offline` by reflection and finds `CompositeSnapshots.offline(DomNode, SnapshotOptions)` (`dom-engine/.../snapshot/CompositeSnapshots.java:19`). `adapt` (`:68-85`) can only build String, Map or JsonNode, so it throws.
  - The test (`WorkbenchMcpServerTest.java:121-130`) still expects the producers-absent placeholder (`g3-producers-absent`), which can't happen while dom-engine is on the test classpath.
- Architecture: the Workbench does not compile against dom-engine (SnapshotCommand javadoc; AGENTS.md:429 says Workbench carries a non-transitive dom-engine jar that is not a compile dependency). Keep it reflective, or forward to the `domengine` CLI/MCP the way `DomEngineCliForwarder` does.
- Recommended fix: dom-engine already has an adapter, `dev.domengine.mcp.SnapshotDispatch.render(JsonNode)` (public static, `SnapshotDispatch.java:22`), which builds the DomNode/SnapshotOptions arguments and returns producer JSON or its own placeholder. `McpRegistryTest.java:46-50` proves it returns the real shape. Have SnapshotCommand call that one method deterministically (exact name plus a `JsonNode` parameter type), falling back to `SnapshotProducers.page` and then the placeholder only when the class is absent. Never pick an overload by name alone.
- Acceptance:
  - `snapshotCommandReturnsTheCompositeSnapshotShape` asserts the real shape with dom-engine present (schema `pickleball.composite-snapshot`, version 1, `premap` and `nodes` present, no `g3-producers-absent`).
  - Add a separate test for the producers-absent placeholder, through a class-name seam or an isolated class loader without dom-engine. It keeps the `g3-producers-absent` assertion, so that coverage isn't lost.
  - Keep the `surfaces`/`html-write` assertion.

### T3. AgentSurfaceRouterTest is flaky (temp dir deleted while the popup is alive)
- Reproduced: 3 runs of `./gradlew --offline :cleanTest :test --tests 'tools.dscode.common.agentsurface.AgentSurfaceRouterTest'` gave run 1 FAILED, and runs 2 and 3 green (google-chrome is installed on the box).
  ```
  AgentSurfaceRouterTest > routingTable() FAILED
      org.junit.platform.commons.JUnitException: Failed to close extension context
          java.io.IOException: Failed to delete temp directory /tmp/junit-7353965360239086819. The following paths could not be deleted ...: <root>, .pickleball, .pickleball/runs, .pickleball/runs/r, .pickleball/runs/r/agent, .pickleball/runs/r/agent/popup, .pickleball/runs/r/agent/popup/profile
      Suppressed: java.nio.file.DirectoryNotEmptyException: /tmp/junit-7353965360239086819/.pickleball/runs/r/agent/popup/profile ...
  ```
- Cause:
  - `AgentSurfaceRouterTest.routingTable` (`:28-66`) routes with `pkb_agent_popup=auto|always`. That starts a separate `PopupHostMain` JVM through `PopupHost.open` (`PopupHost.java:18-66`, owner-kind `cli`).
  - That JVM launches Chromium with `--user-data-dir=<project>/.pickleball/runs/r/agent/popup/profile` (`PopupHostMain.java:38-59`) and only exits on a ledger `close` row, browser exit, idle timeout or owner exit (`:68-90`).
  - The test's `@AfterEach` (`:22-26`) only clears properties. JUnit `@TempDir` cleanup then races with Chrome still writing into `profile`.
- Fix (never `@Disabled`, never weaken assertions):
  - In teardown, route `popup-close` for run `r` (`AgentSurfaceRouter.java:90-96` appends the ledger `close` row).
  - Then wait, bounded at about 10 s, for the host to exit: `host.json` pid/browser pid gone, or the `lifecycle ... exit` log line from `PopupHostMain.java:89`, which runs after `destroyTree` and `delete(profile)`.
  - Make sure the browser tree is gone before `@TempDir` cleanup (for example manage the temp dir yourself and delete it after the wait, with a short retry for straggling files).
  - If a production helper makes this cleaner (for example a `PopupHost.closeAndWait(project, runId, timeout)` used by the CLI `popup-close` too), add it with its own test, and keep docs in place.
  - Apply the same teardown to any sibling popup test that opens a real host in a `@TempDir`, if it has the same race.
- Acceptance: AgentSurfaceRouterTest passes 3 times in a row with fresh `:cleanTest`, and no `PopupHostMain` or Chrome process from the test is left running afterward (`pgrep -fa PopupHostMain` is empty).

## TESTS (targeted only)

Run only these, each with a fresh cleanTest, plus any new test classes you add:
- Root module (`timeout 600 ./gradlew :cleanTest :test --tests <filter>`):
  - DaltonSchemaUpgradeTest
  - DaltonStepDefinitionCatalogTest
  - DaltonFoundationTest
  - DaltonExecutionRecordingTest
  - DaltonEntityUsageTest
  - DaltonLogLinkTest
  - DaltonQueryCliTest
  - DaltonTimelineTest
  - DaltonLinkedTrackingSmokeTest
  - GherkinRewriteTest
  - AgentSurfaceRouterTest, run 3 TIMES in a row, each with `:cleanTest`
- Workbench (`timeout 600 ./gradlew :pickleball-workbench:cleanTest :pickleball-workbench:test --tests <filter>`):
  - WorkbenchTrackingForwarderTest
  - WorkbenchMcpServerTest

Use the `:`-prefixed root tasks so that `--tests` doesn't fan out to every module.

The full multi-module regression, `validate --all`, Maven `@all` and first-start timing are HELD by the owner. Do NOT run them.

## SCOREBOARD

In docs/roadmaps/3.0.0-linked-tracking-roadmap.md, add a "### Fix-back 2" section right after "### Fix-back 1". Use one row per item (R1, R2, R3, R4, R5, T1, T2, T3) and these columns: item | status | SHA | CI run (Agent Contract / SQLite parity) | tests | notes. Every row needs the full commit SHA that fixed it and the CI run ids for the push that carried it. Fill the CI ids in a follow-up scoreboard commit once the runs exist (`gh run list --branch 3.0.0` or the Actions page). If a run is red for a known unrelated flake (Maven Central 403, the Windows cold-restart timing over 1500 ms, the dom-engine live `ChromeProcessTest`), say so in the row, as Fix-back 1 did.

## DOCS

Rewrite docs IN PLACE to match the code after each row: AGENTS.md `## Dalton`, the linked-tracking design, `docs/dalton-entities.md`, the CLI help, and the workbench docs as touched. History goes only to the 3.0.0 entry in `docs/release-notes.md`. Never add append-only "update" notes. Follow DOC SAFETY (`wc -c AGENTS.md docs/agent/feature-map.md` before every push).

## FINAL

- Push after EVERY row, or a tightly related pair (R2+R3 may share a commit, and so may R4 with whatever row touches the same docs): pull the tip, run the three check scripts, commit, `git push origin HEAD:refs/heads/3.0.0`.
- When every row is done, report:
  - the start SHA and the final SHA
  - one line per row (item, SHA, test names and counts)
  - the three AgentSurfaceRouterTest run results
  - the three check-script results
  - the CI run ids and their conclusions
- Do not start other work.
- This prompt is fully standalone. Do not depend on any chat history.
