# Pickleball bug report: code investigation on branch 2.20.0

_Read-only investigation on 2026-11-17, Tue (MST). Target: **branch 2.20.0 (`85a8cdc2`)** only. Source came from `gh api` (git tree, contents) only. Nothing was cloned, built, or run, and no 3.x branch was read. The report itself was written against 2.1.19. Every path, line number, and doc reference below is from 2.20.0: each fetched file's git blob SHA was checked against the 2.20.0 tree._

Docs read on 2.20.0 first: `AGENTS.md`, `docs/agent/feature-map.md`, `docs/block-conditionals.md`, `docs/log-and-assert-steps.md`, `docs/dynamic-steps.md`, `docs/workbench-agent-detail.md`, `docs/pickleball-workbench.md`, `pickleball-workbench/AGENTS.md`, `docs/release-notes.md`, and `docs/maintainer/2.1.19-issuefix-roadmap.md`.

---

## Issue 1: placeholders after the first phrase of an `, or` chain are not resolved

**Root cause.** This is a regression from the lazy branch-resolution change `a2a360ad` (Oct 2, 3:53 PM MST, "a conditional branch resolves mappings and expressions only when that branch is taken, and only once"). The code that decides "this branch was not taken, so don't resolve it" treats the second phrase of an or-chain as an untaken branch.

How it happens:
1. `LineData.addPhrase` (`src/main/java/tools/dscode/common/treeparsing/preparsing/LineData.java:420-421`) builds every phrase of an `IF`/`until` sentence as deferred:
   `boolean defer = deferConditionalChain || opensConditionalChain(phraseText); phrases.add(new Phrase(phraseText, termination, this, null, !defer));`
   Each phrase keeps its raw `<N>` text until `resolveForExecution()` runs.
2. The `IF` phrase becomes the head of an `AssertionChain`, and `or "2" is equal to "<N>"` becomes a chain member (`ParsedLine.runPhraseFromLine`, `.../preparsing/ParsedLine.java:194-208`). `AssertionChain.executeAssertionChain` (`src/main/java/tools/dscode/common/assertions/AssertionChain.java:66-75`) clones the members, which stay deferred (`Phrase.copyPhraseWithModifications`, `Phrase.java:385-389`), and links each clone to the previous one. `runChainPhrases` (`AssertionChain.java:84-110`) then runs them in order. With `or`, it moves on to the next phrase only when the previous one was **false**.
3. The first clone runs and resolves normally. That produces the `Running Phrase: if "2" is less than "2"` line. Its result is false, so `PhraseData.resolveResults` sets `phraseConditionalMode = -1` (`PhraseData.java:585-586`).
4. Before the second clone runs, `ParsedLine.runPhraseFromLine` (`ParsedLine.java:189-191`) does this:
   ```java
   if (phrase.deferEvaluations && !phrase.shouldResolveBranchReferences()) {
       phrase.suppressResolve = true;
   }
   ```
   `PhraseData.skipReferenceResolution` (`PhraseData.java:305-321`) returns `true`, because the second phrase's conditional is `""` (it has a conjunction, not `if`) and the previous phrase is false:
   ```java
   if (previous != null && previous.phraseConditionalMode <= 0
           && !cond.equals("if") && !cond.equals("until")) { return true; }
   ```
   That rule exists for "the body of a condition that was not taken". In an or-chain, though, a false previous phrase is exactly the case where the next phrase has to be evaluated.
5. With `suppressResolve = true`, `resolveForExecution()` returns early (`PhraseData.java:278-281`, called from `Phrase.executePhrase`, `Phrase.java:150-151`). The phrase is parsed and executed with the literal text `<N>`, so `"2" is equal to "<N>"` is false and the branch is skipped. No error is raised, which matches the report's log: `or "2" is equal to "<N>", : Executing Assertion EQUAL`.

This explains every variant in the report:
- **Literal or-chains work:** there is nothing to resolve.
- **A single phrase works:** there is no second phrase.
- **A true first phrase works:** the chain short-circuits before the second phrase.
- **`and` chains are not affected:** the second phrase runs only when the first was true (`phraseConditionalMode = 1`).
- **The value source doesn't matter:** saved values and `RUN SCENARIOS` parameters fail the same way.

The same path also covers `until ..., or ...` chains, which are deferred through `opensConditionalChain`. Plain `, ensure` / `, verify` / `ASSERT:` chains are built non-deferred, so they should not be affected. I inferred that from the code and did not run it.

**Confidence:** high. The code path matches the reported logs and all the variants.

**Fix direction:**
- Chain members are not branches. The `AssertionChain` already does its own short-circuiting, so the lazy-skip rule should not apply to its members. Two options:
  - In `PhraseData.skipReferenceResolution` (or `shouldResolveBranchReferences`), return `false` when `assertionChainMembership != null`.
  - Or have `AssertionChain.runChainPhrases` call `resolveForExecution()` on each clone right before `runPhraseFromLine`, so that phrase never gets `suppressResolve` set.
- Keep the lazy rule for real untaken branches: `THEN`/body phrases after a false condition, and `ELSE`/`ELSE-IF` after a taken branch.
- **Tests:**
  - Add the report's `@OrChainRepro` scenario as a no-browser consumer feature, with checks A, B, C, and D. Add variants for unquoted `<N>`, the `number` qualifier, a `RUN SCENARIOS` parameter source, a three-phrase `or` chain where only the third phrase is true, and an `until ..., or "<X>" ...` loop.
  - Add negative guards: an `and` chain whose first phrase is false must still not resolve the later phrase's `$`/missing reference, and the existing `LazyBranchResolutionTest` cases must stay green.
  - Add a unit test next to `LazyBranchResolutionTest` that asserts the second chain member has `evaluationsResolved=true` and `suppressResolve=false`.
- **Existing tests:** `src/test/java/.../preparsing/LazyBranchResolutionTest.java` has only 2 tests (`skippedThenAndLaterElseIfStayUnresolvedAtConstruction` and `falseConditionDoesNotResolveItsRunStepBody`), and neither covers an `or` chain. No consumer feature has an `IF:` with `, or` plus a placeholder. `step-state-elements.feature:34` has `until ..., or the Step Repetition is greater than 3`, but it has no placeholder, so it does not catch this bug.
- **Docs:** `block-conditionals.md` says phrase conditions use "the same assertions, elements, contexts, chains ... as dynamic steps". The docs promise placeholder resolution in chains. No doc change is needed beyond a release note.

---

## Issue 2: `number is equal to` compares text when the operands are quoted

**Root cause.** The `number` qualifier is parsed but never used for equality.
- `DefinitionContext.java:277` (`valueTransform`) recognizes `"1" number` and rewrites it as a value with the unit `InternalValueUnit` + `number`. `ElementType.fromString` (`ElementType.java:188-201`) maps `NUMBER`/`INTEGER`/`DECIMAL` to `NUMERIC_VALUE` + `RETURNS_VALUE`.
- `NUMERIC_VALUE` (`ElementType.java:31`) is **never read anywhere** else in the codebase.
- `AssertionOperations.EQUAL` (`.../phraseoperations/AssertionOperations.java:24-42`) always calls the general `ValueWrapperComparisons.equals(left, right, margin)`.
- `ValueWrapperComparisons.equalsInternal` (`src/main/java/tools/dscode/common/assertions/ValueWrapperComparisons.java:200-219`) takes the numeric path only when a wrapper's **type** is `NUMERIC`:
  ```java
  private static boolean eitherNumeric(ValueWrapper a, ValueWrapper b) {
      return a.type == ValueWrapper.ValueTypes.NUMERIC || b.type == ValueWrapper.ValueTypes.NUMERIC; }   // :82-85
  ```
  A quoted operand becomes `DOUBLE_QUOTED` (`ValueWrapper.java:160-169`), even when its text is numeric. An unquoted `1` becomes `NUMERIC` (`ValueWrapper.java:196-199`). So `"1" number is equal to "1.00"` falls through to `StringUtils.equals` and is logged as `checking equality between: '1' and '1.00'`, while `1 is equal to "1.00"` is numeric. This matches the report.
- The ordering operators always call `numericLessThan`, `numericGreaterThanOrEqualTo`, and so on (`AssertionOperations.java:44-128`). Those use `asBigDecimal()`, which strips everything except digits, `.`, and a leading sign (`ValueWrapper.java:233-262`). That is why `$1.00` works there.
- A public `ValueWrapperComparisons.numericEquals(...)` already exists (`ValueWrapperComparisons.java:353-362`) but has **no callers**.
- Related: `asBigDecimal()` returns `BigDecimal.ZERO` for text with no digits. So a `number` comparison never checks that its operands are numbers. `"abc" number is equal to "abc"` passes on the string path, and `"abc" number is less than "x"` would compare 0 with 0.

**Confidence:** high.

**Fix direction:**
- In `AssertionOperations.EQUAL`, use `ValueWrapperComparisons.numericEquals(left, right, margin)` when either `firstElement` or `secondElement` has `ElementType.NUMERIC_VALUE`. The existing `equals` path still handles temporal values first, and `numericEquals` already falls through to temporal values. Negated `is not equal to` gets the same behavior because it flips EQUAL.
- Optional product decision: make the `number`/`integer`/`decimal` qualifier fail clearly when an operand has no digits, instead of silently treating it as 0.
- Without the qualifier, keep today's behavior: quoted operands compare as text.
- **Tests:**
  - Add the report's `@NumericEqualityRepro` scenario as a no-browser consumer feature. It should cover `"1"` vs `"1.00"`, `"$1.00"`, `"1.5"` vs `"1.50"`, unquoted mixes, and `is not equal to` variants.
  - Add a regression check that `"1" is equal to "1.00"` (no qualifier) still fails as text.
  - Add a unit test that the EQUAL operation with `NUMERIC_VALUE` calls the numeric path.
  - Add a margin case such as `"1" number is equal to "1.04" within 0.05`, if margin syntax applies.
- **Existing tests:** I found no `ValueWrapperComparisons` unit test on the branch. `MultilineAssertTest` and `MultilineAssertFeatureTest` cover only text equality. `area-c.feature:207` covers unquoted `5 is greater than 1`. No feature uses the `number` qualifier.

**What the docs say about `number`:** nothing. `docs/dynamic-steps.md` (§ Assertions, lines ~213-226) lists the comparison words (`equals`, `contains`, ..., `is less than or equal to`), and `log-and-assert-steps.md` / `block-conditionals.md` reuse them. No doc mentions the `number`/`integer`/`decimal` qualifier or the rule that quoted means text. The only "number" in the docs is unrelated (`dynamic-steps.md:282`, "A number word such as `two` is not a duration"). The report's point that the qualifier is undocumented is correct, so the fix should also add a short paragraph to `dynamic-steps.md`.

---

## Issue 3: Workbench `discover` runs a stale copy of a classpath-resolved (called) feature

**Root cause.** The CLI `discover`/`confirm` fast path never syncs resources. The input-fingerprint resource sync the report expected exists only in the Workbench controller (`pickleball-workbench/.../sync/WorkbenchSyncPlanner.java:13-33`, which returns `RESOURCES_ONLY` and leads to `WorkbenchSynchronizer.java:466` running `process-test-resources`). The launcher does not use it. Nothing under `src/main/java/tools/dscode/launcher` references `WorkbenchSynchronizer`, `WorkbenchSyncPlanner`, or `process-test-resources`.

The launcher's decision uses only lock files:
- `WorkspaceLaunch.warmClasspathLocked` (`src/main/java/tools/dscode/launcher/WorkspaceLaunch.java:213-235`): if `ClasspathCache.load(state, InputFingerprint.lockHash(root))` hits and a `target/` or `build/` output exists, it republishes the cached classpath and returns `true`. **Maven does not run, so there is no `process-test-resources`.**
- `InputFingerprint.lockHash` (`pickleball-control-protocol/.../speed/InputFingerprint.java:45-47`) hashes only `pom.xml`, the wrapper and Gradle files, and `.mvn/*` (`LOCK_FILES`). Edits under `src/test/resources` are not part of it.
- `WorkbenchSpeed.tryDirect` / `cacheHit` (`src/main/java/tools/dscode/launcher/WorkbenchSpeed.java:250-293`) use the same lock-hash cache and then run the selection on the warm JVM, whose classpath comes from the cached entries (`target/test-classes`, or `live/classes` when the cached list came from a live session).
- `InputFingerprint.watchHash` **does** include `src/test/resources` size and mtime (`InputFingerprint.java:34-39, 50-52`). It is used only to decide whether the warm JVM process should be restarted (`WorkbenchSpeed.warmRun`, `:304-317`). The restarted JVM gets the same stale classpath directories.

Why the top-level file was fresh and the called file was stale:
- The feature index narrows top-level discovery to explicit feature paths (`-Dpickleball.workbench.topFeatures`, per release notes), so the top-level file is read from disk (`file:///...`).
- Nested `RUN SCENARIOS` uses `CucumberScanUtil.listPickles` with the configured feature set (`classpath:features/...`). That reads from `target/test-classes`, which nothing refreshes on a cache hit.
- Running `mvnw process-test-resources` by hand fixes it. This matches the report exactly.
- The fresh-JVM wrapper fallback runs a Maven test lifecycle, which includes resource processing. So the staleness is specific to the cache-hit and warm paths. I did not trace the fallback command line in full.

**Confidence:** high for the mechanism. It is deterministic on a cache hit, not a timing issue. The report's "seen once" is likely only because most edits are to the top-level file.

**Fix direction:**
- On the launcher cache-hit path (`warmClasspathLocked` and before `tryDirect`), compare a resource fingerprint and a Java-source fingerprint against values stored with the classpath cache. Reuse the Workbench rule:
  - Resources changed: run the wrapper `process-test-resources` (offline, `-q -B -DskipTests`).
  - Java changed: run `test-compile`.
  - Nothing changed: skip.
  `WorkbenchSyncInputs` (`pickleball-workbench/.../sync/WorkbenchSyncInputs.java`) already computes split fingerprints that could be moved into `pickleball-control-protocol` so the launcher can use them. That keeps the module boundary that `AGENTS.md` requires.
- If the cached classpath is `live/classes`, the resources-only path must also re-materialize the changed resources into `live/classes`.
- Because the warm JVM uses a new class loader per run (`WorkbenchWarmMain.serve`, `:196`), it will see the refreshed `target/test-classes` without a restart.
- **Tests:**
  - A launcher unit test: a cache hit plus an edited `src/test/resources/features/x.feature` must schedule a resources-only sync, and an unchanged tree must not.
  - A consumer_sim / `maven-consumer-project` scenario. Run a feature that calls a component in a second feature. Edit the component step. Run `discover` again with a new run id. Assert that the new step text runs and that `target/test-classes` holds the new copy. Per `AGENTS.md` (lines ~332-343), Workbench fixes need a multi-run consumer scenario, not only unit tests.
- **Existing tests:**
  - `src/test/java/tools/dscode/launcher/WorkbenchSpeedCacheTest.java` (`cacheHitRepublishesTheClasspathWithoutMaven`, `liveWorkerListIsNotADiscoverCacheHit`, `sequentialModeIsTheFreshJvmFallback`) asserts the current "cache hit skips Maven" behavior and has no resource-edit case.
  - `pickleball-workbench/src/test/.../sync/WorkbenchSyncPlannerTest.java` and `WorkbenchSynchronizerTest.java` cover `RESOURCES_ONLY`, but only for the Workbench controller.

**What the docs say:**
- `pickleball-workbench/AGENTS.md:128` says input fingerprints decide skip, resources-only, or full compile. The report quotes this, but it describes **Workbench synchronization** (controller/session).
- The CLI path is documented differently in `docs/workbench-agent-detail.md:124`: "A cache hit (`resolved-classpath.v1`, hash of `pom.xml` and the lock files, and a `target/` or `build/` output directory) skips the wrapper."
- So, read literally, the docs predict this bug for `discover`. Neither doc warns that edited called features need a resource sync on that path. Either the docs or the code should change; the code fix above is the better choice.

---

## Issue 4: a second warm-JVM run drops a run that is in progress

**Root cause.** The second client kills the busy warm JVM when the watch hash has changed. Busy rejection works only when the hash still matches.
- The warm JVM itself handles concurrency correctly. `WorkbenchWarmMain.acceptOne` serves each connection on its own thread (`WorkbenchWarmMain.java:146-159`), and `serve` replies `EXIT 2` when `BUSY` is already set (`:182-186`). On the client side, `WorkbenchSpeed.exchange` maps that to `REJECTED` without killing anything (`WorkbenchSpeed.java:374-380`). This is the documented behavior in `docs/release-notes.md` (top section): "A second `RUN` that arrives while that JVM is already busy is rejected and is not executed beside the first run."
- The problem is in `WorkbenchSpeed.warmRun`, which runs before any handshake (`WorkbenchSpeed.java:304-317`):
  ```java
  String watch = InputFingerprint.watchHash(project);
  Optional<WarmProcess.Handle> reusable = WarmProcess.reusable(state, watch);
  if (reusable.isEmpty()) {
      stopStale(state);                       // -> WarmProcess.release(state)
      if (!startDaemon(project, state, watch)) return DirectAttempt.missed();
  ```
  `WarmProcess.reusable` (`pickleball-control-protocol/.../speed/WarmProcess.java:67-79`) returns empty whenever the stored watch hash differs. The watch hash includes the size and mtime of every file under `src/main|test/java` and `src/main|test/resources`. `WarmProcess.release` (`:85-92`) deletes the handle and calls `ProcessTree.kill(pid)`. Even without the kill, the warm JVM exits on its own when its state file disappears, "including during a run" (`WorkbenchWarmMain.java:106, 120-125`).
- So if any source or resource file changed between the first and second `discover`, the second client kills the busy warm JVM, starts a new one, and runs normally. The first client's socket then closes after `STARTED`, which leads to `noteInterrupted`: `error: warm run was accepted and then the connection dropped` (`WorkbenchSpeed.java:393, 416`; message in `noteInterrupted` :424-428), followed by `LAUNCH_ERROR`. The change could be an edited feature file, which is the issue-3 workflow, or a file written under `src/test/resources`.
- That matches the report. The second run "finished normally" rather than being rejected, and the second run can only have succeeded if the old JVM was replaced.
- A secondary path: if the busy JVM fails to answer the handshake within 20 s, or answers with something other than `STARTED` or `EXIT n`, `exchange` also calls `releaseQuietly(releaseState)` (`:370, :381, :412, :419`). That kills a JVM that acknowledged a **different** client's run. With the threaded accept this should be rare.
- The maintainer roadmap on this branch (`docs/maintainer/2.1.19-issuefix-roadmap.md` Phase 2) says "Never call WarmProcess.release after acknowledgement". That rule was applied only to the same client's own run.

**Confidence:** medium-high. The mechanism is certain from the code. That it caused this specific incident is inferred from "second run finished normally", and the report doesn't record whether files changed between the runs.

**Fix direction:**
- Before calling `stopStale` or `releaseQuietly`, check whether the warm JVM is busy. For example, have it answer `PING` with `PONG busy`, or write a busy marker in the state directory.
- If it is busy and the hash is stale:
  - Option 1: do not kill it. Either return `REJECTED` (consistent with the documented "busy is rejected"), or start a **separate** fresh JVM for the second run without touching the first. Run ids are already isolated.
  - Option 2: mark the old JVM "retire after current run" so it exits when its run finishes.
- Never kill a warm JVM that is serving another client's acknowledged run.
- Also limit `releaseQuietly` in `exchange` so it kills only a JVM that is not busy, rather than any JVM that did not acknowledge this client.
- **Tests:**
  - Extend `WorkbenchWarmFallbackTest`: start a warm stub that holds a run after `STARTED` (`setAfterStarted`), change the watch hash (touch a file under `src/test/resources`), and call `tryDirect` for a second run. The first exchange must still finish with `COMPLETED`, and the second must be `REJECTED` (or run elsewhere), never `INTERRUPTED`.
  - Add a consumer_sim multi-run scenario: a long discover, an edit, then a short discover on a second run id.
- **Existing tests:**
  - `src/test/java/tools/dscode/launcher/WorkbenchWarmFallbackTest.java` covers `secondRunWhileBusyIsRejected` and `secondRunDuringAnInFlightServeDoesNotReleaseOrFallBack`. Both call `exchange` on an existing port with a matching handle, so the hash-mismatch `stopStale` path in `warmRun` is untested.
  - `WorkbenchWarmLifecycleTest.java` covers stop, idle, owner death, and the deleted state file.

**What the docs say:**
- `docs/workbench-agent-detail.md:75-77`: "The isolation unit is the run id ... Two runs never share a folder, a config copy, a session file, or a browser profile." That is about run directories. Separately: "When a Workbench window is already open, only one agent drives it."
- `docs/workbench-agent-detail.md:126`: the warm JVM "stays up across separate CLI commands until `stop`, session teardown, or idle".
- `docs/release-notes.md` (top section): a busy second `RUN` "is rejected and is not executed beside the first run".
- None of these mention that a source or resource edit makes the next command kill and replace the warm JVM, even mid-run. So the observed behavior contradicts the documented "rejected, not dropped" rule. The report's expectation (queue or refuse, never drop) matches the docs.

---

## Summary table

| # | Present on 2.20.0? | Root cause (short) | Key location | Confidence |
|---|---|---|---|---|
| 1 | Yes | Lazy branch resolution marks an or-chain's 2nd+ phrase as an untaken branch (`suppressResolve`) because the previous phrase was false | `PhraseData.skipReferenceResolution` :305-321; `ParsedLine.runPhraseFromLine` :189-191; `AssertionChain.runChainPhrases` | High |
| 2 | Yes | The `number` qualifier sets `NUMERIC_VALUE`, which nothing reads; EQUAL uses the type-based `equals`, and quoted means text | `AssertionOperations.EQUAL` :24-42; `ValueWrapperComparisons.eitherNumeric` :82-85; unused `numericEquals` :353 | High |
| 3 | Yes | The CLI discover cache is keyed on lock files only, so a cache hit skips Maven and called features stay stale in `target/test-classes` | `WorkspaceLaunch.warmClasspathLocked` :213-235; `InputFingerprint.lockHash`; `WorkbenchSpeed.cacheHit` :289 | High (mechanism) |
| 4 | Yes | A second client sees a changed watch hash and calls `stopStale` → `WarmProcess.release`, which kills the busy warm JVM | `WorkbenchSpeed.warmRun` :304-317; `WarmProcess.release` :85-92 | Medium-high |

Local copies of the fetched 2.20.0 sources: `/workspace/pickleball-legacy/src-2.20.0/` (selected files only, not a clone).
