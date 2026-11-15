# Grok Build Prompt: Pickleball 3.0.0 Linked Tracking — Fix-back 1

You are Grok Build. The linked-tracking roadmap (docs/roadmaps/3.0.0-linked-tracking-roadmap.md, design docs/maintainer/3.0.0-linked-tracking-design.md) was implemented on branch 3.0.0 at f0931bb1..0924540c. An independent verification at tip 0924540c found the gaps below. Fix ALL of them on branch 3.0.0, with real tests that would have caught each one. Work only on these gaps.

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

## START PROCEDURE

1. `git fetch origin 3.0.0` and check out the CURRENT tip of `origin/3.0.0` (expected 0924540c or later). Record the SHA you start from.
2. Read AGENTS.md (the `## Dalton` section), the linked-tracking design and roadmap, `docs/dalton-entities.md`, and the code cited below. The branch docs win over this prompt wherever they disagree.

## GAPS TO FIX (verified with file and line evidence at 0924540c)

1. **Re-hydrate breaks once tracking has data.** `DaltonStepCatalog.java:67` deletes all `step_definitions`, but `step_executions` references them with no ON DELETE rule (`DaltonDatabase.java:235`, foreign keys enabled at about line 99). After any tracked run with a matched step, a second hydrate or `dalton hydrate` exits 1 with a FOREIGN KEY error. Fix: rebuild the catalog by upsert on a stable key. Keep the existing rows that step executions reference (mark them stale or removed instead of deleting them), or use another design-consistent approach that keeps history linked. Test: hydrate, run a tracked scenario with a matched step, hydrate again, both of which succeed, then confirm the old executions still resolve to their definitions.
2. **Every `dalton run` overwrites the previous run.** `DaltonRunWorker.java:83` defaults the run id to `dalton-run`, and the derived ids upsert. Two runs (PASSED then FAILED) leave one run row, and entity usages pile up as duplicates. Fix: give each run a unique id by default (timestamp plus random suffix or UUID, per the design), so the derived scenario and step execution ids are unique per run and entity usages are never duplicated. Test: two consecutive runs leave two run rows with their own statuses, executions and usages, and the timeline shows both.
3. **Tracking writes to the wrong database on the consumer JUnit path.** `NativeSuiteExecution.java:88` and `DaltonExecution.java:118` and `:192` resolve the DB from the working directory instead of the configured `pkb_dalton_project`, while entity reads use the configured project. Fix: resolve the Dalton project the same way everywhere, through the existing `DaltonConfig` resolution. Test: with `pkb_dalton_project` pointing somewhere other than the working directory, run recording through the consumer JUnit and native suite path. Rows must land only in the configured project's DB, and none in the working directory.
4. **The Workbench Tracking timeline is stuck on its first 50 events.** `TrackingPanel.java:66` calls `timeline --json` with the default limit and has no paging or date range. Fix: add paging (next and previous, through cursor and offset) and from/to range controls that forward to the CLI, with no SQLite or Dalton imports in the Workbench. Test: extend WorkbenchTrackingForwarderTest to assert that the paging and range arguments are forwarded.
5. **Docs claim a Retain control that doesn't exist.** `DaltonBrowser.retain()` is called only from a test, yet AGENTS.md (around line 119) and docs/release-notes.md (around line 34) say the Tracking tab forwards `dalton retain`. Fix: either add a real Retain control in the Tracking tab that forwards to `dalton retain` (preferred, with a forwarding test), or correct the docs. Docs must match the code.
6. **Design filters are missing.** Design (around line 192) promises filters by tag, feature, run id and free-text log search, but `dalton query` and `dalton timeline` accept none of them. Implement those filters in the CLI, document them in place and expose them through the Workbench and MCP forwarding. Extend DaltonQueryCliTest and DaltonTimelineTest.
7. **Rewind and scale.** (a) Add a real backward cursor to `dalton timeline` (for example `--before <cursor>`, or a `prev` cursor in the JSON output) so rewinding doesn't depend on `--offset`. (b) `DaltonTrack` loads every event into memory before filtering. Push the filters and limit into SQL. (c) `examples_row` is never set. Populate it for Scenario Outline example rows. (d) Add one test to DaltonEntityUsageTest that captures entity usage from a REAL executed scenario step, not a hand-set step context. Keep the existing tests.

## TESTS (targeted only)

Run only these, plus any new test classes you add: DaltonSchemaUpgradeTest, DaltonStepDefinitionCatalogTest, DaltonFoundationTest, DaltonExecutionRecordingTest, DaltonEntityUsageTest, DaltonLogLinkTest, DaltonQueryCliTest, DaltonTimelineTest, WorkbenchTrackingForwarderTest, DaltonLinkedTrackingSmokeTest. Extend DaltonLinkedTrackingSmokeTest to cover hydrate twice, two runs and the configured project path. Do NOT run the full regression.

## SCOREBOARD

In docs/roadmaps/3.0.0-linked-tracking-roadmap.md, add a "Fix-back 1" section with one row per gap (gap | status | SHA | tests | notes). Also fill the existing CI column for Phases 1–7 with these run ids (Agent Contract / SQLite parity), noting that phase commits were pushed together with their scoreboard commits: P1 43658405930/43658405896; P2 43659585579/43659585540; P3 43659871522/43659871535; P4 43660134214 (red: transient Windows cold-restart timing 1840ms>1500ms, green on the next run)/43660134167; P5 43660613388 (red: live-Chrome ChromeProcessTest in dom-engine, untouched by these phases)/43660613350; P6 43661062560/43661062640; P7 43661550005/43661550004.

## FINAL

Commit and push after each gap or small group of gaps (pull the tip first, run the three check scripts). When every gap is done, report the final SHA, the test results and the three check results. Do not start other work. This prompt is fully standalone. Do not depend on any chat history.
