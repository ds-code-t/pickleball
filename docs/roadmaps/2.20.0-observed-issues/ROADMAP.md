# Pickleball 2.20.0 Observed Issues — Phased Roadmap

**Branch:** 2.20.0 (tip at start of this roadmap: 5b4c2752)
**Goal:** Fix the four observed issues reported on 2.1.19 (code unchanged on 2.20.0). Version becomes 2.20.0.
**Hard rules:** Every phase is mandatory and must be completed in full. Nothing is optional or deferrable unless Dan marks it so. Do not stop early. Never commit/push/merge to master. Never touch any 3.x branch. No tags, releases, Maven publishing, or force-push. Never weaken, skip, or delete tests. Keep Gherkin syntax. Step annotations stay single string literals. Never run `./gradlew --stop`. Fetch and rebase before every push. No secrets in cleartext. Branch docs are the source of truth.

**Verification notes (root causes confirmed against 2.20.0 code):**

1. **Placeholders after first phrase of `, or` condition not resolved (High).** Confirmed. Regression from lazy branch-resolution. `PhraseData.skipReferenceResolution` treats or-chain members as untaken branches when previous phrase is false. `AssertionChain` already short-circuits. Fix: do not apply the skip when the phrase has `assertionChainMembership != null` (or force resolve in `AssertionChain.runChainPhrases` before `runPhraseFromLine`). Keep lazy rule for real untaken branches (THEN body, ELSE after taken). Covers `until ..., or` as well.

2. **`number is equal to` compares text when operands are quoted (Medium).** Confirmed. `AssertionOperations.EQUAL` always uses general `equals`; `NUMERIC_VALUE` from the qualifier is never consulted. Ordering operators already use numeric paths via `asBigDecimal()`. **Semantics decision:** When the `number`/`integer`/`decimal` qualifier is present on either operand, equality uses the numeric path (`ValueWrapperComparisons.numericEquals`). Non-numeric text (no digits) is treated as `BigDecimal.ZERO` by existing `asBigDecimal()`, matching other numeric operators. Without the qualifier, quoted operands remain text comparison. Document this in `docs/dynamic-steps.md`.

3. **Workbench discover runs stale classpath copy of called feature (Medium).** Confirmed. Launcher cache-hit path (`WorkbenchSpeed.cacheHit` / `WorkspaceLaunch.warmClasspathLocked`) uses only `InputFingerprint.lockHash` (pom.xml + wrappers). It skips Maven, so no `process-test-resources`. Nested `RUN SCENARIOS` reads `classpath:features/...` from `target/test-classes`. Top-level files are read from disk via feature index. Fix: on cache-hit, compare resource and Java-source fingerprints (move/reuse `WorkbenchSyncInputs` logic into protocol so launcher can use it). Resources changed → wrapper `process-test-resources` (offline, -q -B). Java changed → `test-compile`. Unchanged → skip. Warm JVM sees refreshed classes without restart.

4. **Second Workbench run on warm JVM kills run in progress (Low).** Confirmed. `WorkbenchSpeed.warmRun` calls `stopStale`/`WarmProcess.release` when `watchHash` differs, killing a busy JVM. Preferred direction (Dan): never share one JVM between concurrent runs (static RunVars, registries, saved values, drivers). Keep a small capped pool of warm JVMs. A run reuses an idle warm JVM; otherwise starts a new one in parallel. At the cap, the run queues or receives a clear busy refusal. A JVM serving another run is never killed. Current `WarmProcess` is single-JVM per project; the pool requires multiple handles, idle tracking, and refusal/queue logic. Feasible; update docs that currently describe single-JVM busy rejection.

**Testing rule for Build:** Between phases run only the targeted tests for what that phase changed. Run the full regression (full suite + consumer sims) once at the end.

## Phase 0 — Version bump to 2.20.0
**Functionality:** Align project version with branch name.
**Docs:** Update `docs/release-notes.md` (add empty `## 2.20.0` section above 2.1.19; fill in later phases), packaged guidance copies, any version stamps in consumer POM, workflows, tests, and feature-map if needed.
**Tests:** Targeted version-string checks. Do not rewrite historical fixtures.
**Files:** `build.gradle` (`version = '2.20.0'`), `maven-consumer-project/pom.xml` pickleball.version, relevant workflow filters, `docs/release-notes.md` + packaged copy, any current-version assertions.

## Phase 1 — Or-chain placeholder resolution (Bug 1)
**Functionality:** Placeholders resolve in every phrase of an `, or` (or `until ..., or`) chain. Lazy skip applies only to real untaken branches.
**Docs:** Release note in 2.20.0 section. No change needed in `docs/block-conditionals.md` beyond the note (it already promises same resolution as dynamic steps).
**Tests:**
- Add report's `@OrChainRepro` as no-browser consumer scenario in `maven-consumer-project` (checks A/B/C/D). Add variants: unquoted, `number` qualifier, `RUN SCENARIOS` parameter, three-phrase or where only third is true, `until ..., or "<X>"`.
- Unit test next to `LazyBranchResolutionTest` asserting second chain member has `evaluationsResolved=true` and `suppressResolve=false`.
- Negative: `and` chain with false first phrase still skips later resolution. Existing LazyBranchResolutionTest stay green.
**Targeted tests:** the new unit + consumer scenario + LazyBranchResolutionTest.

## Phase 2 — Numeric equality with `number` qualifier (Bug 2)
**Functionality:** `number is equal to` (and `is not equal to`) uses numeric comparison when the qualifier is present. Non-numeric operands become 0 via `asBigDecimal`. Without qualifier, quoted stays text.
**Docs:** Add short paragraph to `docs/dynamic-steps.md` (Assertions section) describing the `number`/`integer`/`decimal` qualifier, numeric vs text behavior, and non-numeric-as-zero. Release note.
**Tests:**
- Add report's `@NumericEqualityRepro` as no-browser consumer scenario. Cover quoted `"1"` vs `"1.00"`/`"$1.00"`/`"1.5"` vs `"1.50"`, unquoted mixes, `is not equal to`, and non-numeric (`"abc" number is equal to "abc"` passes as 0==0 or document expected).
- Regression: `"1" is equal to "1.00"` (no qualifier) still fails as text.
- Unit test that EQUAL with `NUMERIC_VALUE` takes numeric path.
- Margin case if syntax applies.
**Targeted tests:** new unit + consumer scenario + any nearby assertion tests.

## Phase 3 — Workbench resource sync on cache-hit (Bug 3)
**Functionality:** Cache-hit path detects resource or Java changes via fingerprints and runs the appropriate Maven goal before using the warm classpath. Called features stay fresh.
**Docs:** Update `docs/workbench-agent-detail.md` and `docs/pickleball-workbench.md` / `pickleball-workbench/AGENTS.md` to describe the resource/Java fingerprint check on the launcher cache-hit path. Release note.
**Tests:**
- Launcher unit test: cache hit + edited `src/test/resources/features/x.feature` schedules resources-only sync; unchanged tree does not.
- Multi-run consumer_sim (per AGENTS.md Workbench rule): run feature that calls a component in a second feature; edit the component; discover again with new run id; assert new step text runs and `target/test-classes` holds the new copy.
**Targeted tests:** new unit + the multi-run consumer_sim scenario. Do not use `@all`.

## Phase 4 — Capped warm JVM pool (Bug 4)
**Functionality:** Replace single warm JVM with a small capped pool. Reuse idle warm JVM; start a new one in parallel if under cap. At cap, queue or clear busy refusal. Never kill a JVM that is serving another run. Static state remains isolated per JVM.
**Docs:** Update Workbench docs (`pickleball-workbench.md`, `workbench-agent-detail.md`, release notes, AGENTS.md sections) to describe the pool, idle reuse, parallel start under cap, and refusal/queue at cap. Note that concurrent runs never share a JVM.
**Tests:**
- Unit tests for pool logic: idle reuse, parallel start under cap, refusal/queue at cap, never-release-busy.
- Multi-run consumer_sim: start a long discover, start a second short discover with different run id while first is in progress; first continues to completion (no LAUNCH_ERROR from dropped connection); second either runs on its own JVM or is cleanly refused/queued. No kill of the first JVM.
**Targeted tests:** new unit + multi-run consumer_sim. Do not use `@all`.

## Phase 5 — Docs finish and full regression
**Functionality:** Complete the `## 2.20.0` section in `docs/release-notes.md` covering all four fixes in plain language. Update `docs/agent/feature-map.md` if ownership or contracts changed. Ensure packaged guidance copies match.
**Docs:** `docs/release-notes.md` + packaged copy, feature-map if needed, any other affected guides.
**Tests:** Full Gradle regression under JDK 21 + full consumer sims. Do not weaken tests.

## Completion scoreboard (required in every reply)
- VERSION: 2.20.0
- Phases completed: (list)
- Tests run and results: (targeted per phase + final full suite)
- Commit SHAs: (list)
- Blocker: none / (description)

All phases are mandatory. Do not stop early. Do not skip phases. Do not mark anything optional or deferred: complete every phase listed, in full. Stop only on an honest test failure or a hard-rule violation.
