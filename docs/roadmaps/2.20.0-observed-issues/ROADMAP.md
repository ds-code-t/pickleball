# Pickleball 2.20.0: Observed-Issues Roadmap (bugs 1–4)

**Repository:** https://github.com/ds-code-t/pickleball  **Branch:** `2.20.0`. Only this branch.
**Inputs on this branch:** `docs/roadmaps/2.20.0-observed-issues/PICKLEBALL_2.1.19_ISSUES.md` (the report) and `INVESTIGATION.md` (the first-pass root causes). This roadmap supersedes the first draft pushed at `c0bc542b` and corrects `INVESTIGATION.md` where noted below.
**Code baseline:** the code on 2.20.0 is identical to the reported 2.1.19. Its only commits since then are docs under `docs/roadmaps/2.20.0-observed-issues/`.
**Version:** `2.20.0` (Phase 0).

## Hard rules (apply to every phase)

- Push only to `2.20.0`. Before every push, fetch and rebase onto the current `origin/2.20.0`. Never commit, push, or merge to `master`. Open no pull request.
- Never read as a target, rebase onto, or cherry-pick from any 3.x branch (`3.0.0`, `3.0.0-*`, `3.*`).
- No tags, no releases, no Maven Central/JitPack publishing (`publishToMavenLocal` for local validation is fine), and no force-push.
- Never weaken, skip, `@Disabled`, or delete tests or assertions, and never widen allowlists to get green.
- Keep existing Gherkin syntax. Add no new syntax beyond what is specified here.
- Cucumber step annotations stay single string literals (no constants or concatenation).
- Never run `./gradlew --stop`.
- No secrets, credentials, or machine-specific paths in commits.
- The branch docs (`AGENTS.md`, `docs/`, `pickleball-workbench/AGENTS.md`) are the source of truth. Follow the AGENTS.md Definition of done, Required impact analysis, Test policy, Workbench controller-isolation invariant, and Verification lessons.
- **Every phase is mandatory and must be completed in full. Nothing is optional or deferrable unless Dan marks it so.**

## Testing rule

Between phases, run only the targeted tests for what that phase changed (listed per phase). Run the full regression once at the end (Phase 6). Never run `@all` as validation for the Workbench/launcher changes (AGENTS.md). Phases 3–4 use focused unit tests plus multi-run `scripts/consumer_sim.py` blocks. For Workbench bugs, unit tests plus a single consumer_sim run are not enough (AGENTS.md "Verification lessons"). Bugs 3 and 4 close only when their multi-run consumer_sim block passes.

## CLI-first and logging checklist (every phase that adds or changes behavior)

- [ ] Every new or changed capability is reachable from the launcher CLI (`-Dexec.args=...` / `mvnw exec:java`). New verbs and flags go into `WorkbenchCliHelp`, `docs/roadmaps/workbench-cli-inventory.json`, and `WorkbenchCliCoverageGateTest`.
- [ ] Non-CLI surfaces (Workbench UI, MCP `WorkbenchMcpTools`, command queue) only forward to the same launcher/session command. They contain no second implementation (AGENTS.md: "The GUI must not own behavior the agents cannot reach").
- [ ] CLI errors use the existing `error:` / `fix:` pair (`WorkbenchCliHelp.structured`) and the documented exit codes. `--json` output stays a single object where the command already supports it.
- [ ] Logging for new or changed code follows the repo's existing channels, each toggleable, date-stamped, and size-capped:
  - **Core phrase/assertion code (bugs 1–2):** `LogForwarder` at `debug` for resolution decisions and `info`/`warn` for user-facing outcomes. It is toggled by `pkb_loglevel`, timestamped by the existing log entries, and bounded by the existing diagnostic retention (`pkb_reportretention`).
  - **Launcher (bugs 3–4):** `launch-timing.txt` stage lines per run (`stage=sync mode=skip|resources|full`, `stage=warm-pool slot=<n> action=reuse|start|recycle|refuse|queue`). These stay off stderr unless `PKB_WORKBENCH_TIMING=1` or `-Dpkb_workbench_timing=true`. Add one line per decision to `.pickleball/agent-log` (the 3-day pruned tape).
  - **Warm pool:** a new `warm-pool.log` under the Workbench state `logs/` directory. Each line starts with an ISO-8601 timestamp. Toggle: `pkb_workbench_warm_log` (property, `PKB_WORKBENCH_WARM_LOG`, dotted `pkb_runvars.` member, or compact `pkb_runvars` member; default on). Size cap: 1 MiB with one rotated backup (`warm-pool.log.1`). Never log secrets or full RunVar maps.
- [ ] Docs that change also update their packaged copy under `src/main/resources/META-INF/pickleball/guidance/docs/` (`python scripts/sync_consumer_guidance.py`). Then run `python scripts/refresh_agent_index.py` when indexed files are added or moved.

---

## Verification against 2.20.0 and corrections to INVESTIGATION.md

### Bug 1: or-chain placeholders (confirmed, with one correction)

**Confirmed:**
- `LineData.addPhrase` (`LineData.java:420-421`) defers the whole conditional sentence.
- `AssertionChain.executeAssertionChain` (`:66-75`) clones the members and links each clone to the previous one.
- The first clone resolves and runs. On false, `PhraseData.resolveResults` sets `phraseConditionalMode = -1` (`:585-586`).
- `PhraseData.skipReferenceResolution` (`:305-321`) then returns true for the `or` member, because its conditional is `""` and the previous phrase is `<= 0`. That sets `suppressResolve`, and `resolveForExecution` (`:278-281`) returns early, so the phrase runs with the literal `<N>`.

**Correction (additional site):** the suppression is applied at two places, not one: `ParsedLine.runPhraseFromLine` (`:189-191`) and `Phrase.getNextResolvedPhrase` (`Phrase.java:365-372`). A fix in `skipReferenceResolution` covers both. Also, the suppressed phrase still logs `Running Phrase: <raw text>` (`Phrase.java:155`).

**Fix decision:** at the top of `PhraseData.skipReferenceResolution`, return `false` when `assertionChainMembership != null`. A chain member runs only when `AssertionChain.runChainPhrases` decides it must, so its short-circuit is the only gate.
- A chain whose head is in an untaken branch never executes, because the head is skipped by `shouldRun()`. Lazy semantics for untaken THEN bodies and ELSE/ELSE-IF branches are unchanged.

### Bug 2: `number` qualifier on equality (confirmed, with additions)

**Confirmed:**
- `DefinitionContext.java:277` (`valueTransform`) parses `"1" number`.
- `ElementType.fromString` (`:188-201`) adds `NUMERIC_VALUE`, and nothing reads it.
- `AssertionOperations.EQUAL` (`:24-42`) always calls `ValueWrapperComparisons.equals`, which compares numerically only when a wrapper's type is `NUMERIC` (`eitherNumeric`, `:82-85`). A quoted operand is `DOUBLE_QUOTED` (`ValueWrapper.java:160-169`).
- `numericEquals` (`:353-362`) has no callers.

**Additions:**
1. `ValueWrapper.asBigDecimal()` (`:233-262`) keeps only digits, `.`, and a leading sign. It silently drops letters (`"12abc"` → 12), treats every `.` except the last as a separator (`"1.2.3"` → 12.3), and returns 0 when there is no digit. So today every numeric operator, with or without a qualifier, treats non-numeric text as a number.
2. `NOT`/`UN` modes invert each comparison inside `ValueWrapperCompareReducer` (`:30-40, :160-164`). A "not numeric" outcome therefore must not be returned as `false` from the comparison lambda, or `is not equal to` would turn it into a pass.

**Semantics decision (normative for this release):**
- **When it applies:** the `number`, `integer`, or `decimal` qualifier is present on either operand. It applies to `is equal to`, `is not equal to`, `is less than`, `is less than or equal to`, `is greater than`, and `is greater than or equal to`.
- **Strict operand check:** each comparison value on both sides must be numeric before any comparison runs. A value is numeric when its normalized text contains at least one digit and the only characters `asBigDecimal()` would drop are:
  - currency symbols (Unicode category `Sc`, such as `$`, `€`, `£`)
  - the grouping separators `,`, `_`, and whitespace
  - additional `.` grouping separators, the same as today's last-dot rule

  Letters, `%`, and other symbols, as well as blank or null values, are not numeric. For `integer`, the parsed value must also be integral (`1.00` is accepted, `1.5` is not). `decimal` is the same as `number`.
- **Comparison:** numeric. Equality uses `ValueWrapperComparisons.numericEquals` (BigDecimal `compareTo`, honouring the margin), so `"1"`, `"1.00"`, `"$1.00"`, and `"1.5"`/`"1.50"` are handled correctly.
- **Non-numeric outcome:** the check runs before `ValueWrapperCompareReducer`, so `NOT`/`UN` never flip it.
  - In `ensure`, `verify`, `ASSERT:`, and `SOFT ASSERT:`, the phrase fails with: `number qualifier: '<value>' is not a number` (`integer qualifier: ... is not an integer`). Hard and soft behavior stays as each form already defines it.
  - In `IF:`, `ELSE-IF:`, `, if`, and `until`, the condition is false, the branch is not taken, and the same text is logged at `warn`. This follows `block-conditionals.md`: a false phrase condition selects a branch and does not fail.
- **Without a qualifier:** unchanged, for compatibility. Quoted equality stays text. Ordering operators keep today's lenient `asBigDecimal()`. Unquoted numeric operands keep the numeric path.
- **Deviation from the ordering operators, and why:** today `"abc" number is greater than or equal to "x"` passes (0 >= 0). Under the qualifier it now fails or is false with a clear message. The qualifier is an explicit request for numbers, so silent zero-coercion hid real defects (the report's `"abc" number is equal to "abc"`). Only phrases that use the qualifier change, and none of the current consumer features use it.

### Bug 3: stale called feature on the launcher cache hit (confirmed, with additions)

**Confirmed:**
- `WorkspaceLaunch.warmClasspathLocked` (`:213-235`) and `WorkbenchSpeed.cacheHit` (`:289-293`) key only on `InputFingerprint.lockHash`. A cache hit runs no wrapper goal, so there is no `process-test-resources`.
- The Workbench controller's `WorkbenchSyncPlanner` (`:13-33`), which makes the `RESOURCES_ONLY` decision, is not used by the launcher.
- `watchHash` includes `src/*/resources` (`InputFingerprint.java:34-39`), but it only restarts the warm JVM on the same stale directories.
- Docs: `workbench-agent-detail.md:124` documents the lock-file-only cache, and `pickleball-workbench/AGENTS.md:128` describes the controller sync.

**Additions:**
1. `WorkbenchDirectRun.run` (`:33-56`) hardcodes the discovery selector to `<project>/target/test-classes` (or `build/classes/java/test`), so any classpath redirection must also redirect this selector.
2. I did not trace the fresh-JVM wrapper fallback in full. It runs a Maven/Gradle test lifecycle, which includes resource processing.

**Fix decision:**
- On every launcher cache hit, compute split input fingerprints and compare them with the values stored next to `resolved-classpath.v1`:
  - **Resources:** `src/main/resources` and `src/test/resources`.
  - **Java:** `src/main/java` and `src/test/java`.
  - **Build:** the existing lock files.

  Use path, size, and mtime, plus a content hash for files whose mtime is within 2 s of now (the existing racy-window rule).
- Move the shared fingerprint code into `pickleball-control-protocol` (JDK-only) so the launcher and `WorkbenchSyncInputs` share one implementation. The Workbench must not gain a core dependency.
- Decision rules:
  - Build changed: today's miss path (full resolve).
  - Java changed: wrapper `test-compile` (Gradle `testClasses`).
  - Only resources changed: wrapper `process-test-resources` (Gradle `processTestResources`), offline first, `-q -B -DskipTests`.
  - Nothing changed: skip.

  Record the decision as `stage=sync mode=skip|resources|full` and store the new fingerprints only after the goal exits 0.
- **Interaction with bug 4 (the sync must not rewrite files a running warm JVM is reading):**
  - Warm JVMs do not run from `target/` directly. After the sync, the launcher materializes the project output (`target/classes` and `target/test-classes`, or the Gradle equivalents) into an immutable generation directory: `<workbench state>/warm/gen-<inputsHash>/{classes,test-classes}`.
  - Use a **copy, not hardlinks**. javac and maven-resources overwrite existing files in place, so a hardlinked snapshot would change underneath a running JVM.
  - A warm JVM is pinned to one generation. Its classpath lists the generation directories first and the dependency jars from their normal caches. `WorkbenchDirectRun` receives the generation's `test-classes` as its discovery selector through a new `-Dpkb_workbench_test_classes=<dir>`.
  - New runs on a new generation get a fresh or recycled JVM, while in-flight runs keep their generation.
  - Unreferenced generations are deleted by the next launcher command. On Windows, a deletion that fails because a file is open is retried later, never forced. This also keeps `mvn clean` unblocked, because no warm JVM holds files under `target/`.
  - The sync itself runs under a project-level cross-process lock (`warm/sync.lock`, a `FileChannel.lock`), so two launchers never run the wrapper into `target/` at the same time.

### Bug 4: second run drops the first (confirmed, with additional triggers)

**Confirmed:**
- When `watchHash` differs, `WorkbenchSpeed.warmRun` (`:304-317`) calls `stopStale` → `WarmProcess.release` (`WarmProcess.java:85-92`), which deletes the handle and kills the tree. The warm JVM also self-exits when its handle file disappears, even mid-run (`WorkbenchWarmMain.java:106, 120-125`).
- `exchange` also releases on a pre-`STARTED` failure (`WorkbenchSpeed.java:370, 381, 412, 419`).

**Correction (more triggers):** `WorkbenchSpeed.release(project)` is also called:
- by `stop` for any run id (`WorkbenchSessionCommands.java:939`)
- on session-start failures (`:580`, `:602`, `:642`)
- on session teardown (`:1042`)

So another agent's `stop` or a failed `isolate` also kills an in-flight warm run.

**Evidence that one JVM must never host concurrent runs:**
- `WorkbenchDirectRun.apply` (`:75-87`) clears and sets JVM-global `System` properties (`pkb_*`, `cucumber.*`).
- `WorkbenchWarmMain.serve` (`:195-214`) swaps the global `System.out`/`System.err`.
- A fresh class loader isolates Pickleball statics, but not these JVM-wide properties and streams, and not driver processes.

This confirms Dan's direction.

**Fix decision: a capped warm-JVM pool, one run per JVM.**
- **Slots:**
  - Pool members live under `<workbench state>/warm/slot-<n>/`, each with a handle file `warm-jvm.v2` (pid, port, generation hash, owner pid) and `warm-jvm.pid`.
  - The legacy `warm-jvm.v1` is read once. If it is idle (`PING` → `PONG idle`) it is released, and a busy legacy JVM is left to finish.
  - Each member binds its own ephemeral loopback port, as today.
- **Busy state:**
  - `PING` answers `PONG idle` or `PONG busy <runId>`.
  - The member writes `slot-<n>/busy` (holding the run id) right after `STARTED` and deletes it in `finally`.
  - The existing `BUSY` compare-and-set plus `EXIT 2` stays the race arbiter. A client that gets `EXIT 2` from a slot tries the next slot.
- **Selection:**
  1. Reuse an idle member on the current generation.
  2. Otherwise, if the pool is under its cap, start a new member in parallel.
  3. Otherwise wait for a slot until the queue timeout, then refuse.
- **Cap:** `pkb_workbench_warm_pool_max`, default 2, range 1–8. It uses the same key order as `pkb_workbench_warm_idle_timeout`: property, env `PKB_WORKBENCH_WARM_POOL_MAX`, dotted `pkb_runvars.` member, compact `pkb_runvars` member. Invalid values stay at 2.
- **Queue:** `--warm-wait=<seconds>` on `discover` and `confirm`, or `pkb_workbench_warm_queue_timeout`. The default is 0, meaning refuse immediately. Refusal prints `error: all <n> warm JVMs are busy (runs: <ids>)` and `fix: wait for a run to finish, pass --warm-wait=<seconds>, or raise pkb_workbench_warm_pool_max.`, then exits with code 2. This is final: the run does not fall back to a fresh JVM, which keeps the cap meaningful and matches today's `REJECTED` semantics.
- **Same run id:** a second concurrent launch of a run id that is already in flight is refused (`error: run <id> is already running`).
- **Recycle:**
  - A member whose generation is stale is recycled only when `PING` says idle.
  - A stale busy member is marked retire-after-run (`slot-<n>/retire`) and exits after its `EXIT`.
  - Idle timeout applies per member.
- **Never kill a member that is serving another run:**
  - `release` becomes `releaseIdle(project)`, which kills only idle members. `stop`, session-start failures, and teardown use it.
  - `stop --run-id=<id>` also kills the member serving `<id>`.
  - `warm-stop --all` is the explicit, documented kill-everything verb, and the only one.
  - Before releasing a slot, `exchange` checks that the slot is idle, never one that acknowledged another client.
- **Owner death:**
  - A member adopts a long-lived owner only for runs launched by that owner (`adoptParent` only when the request carries an owner).
  - A member serving a one-shot CLI run is not killed by owner death until it is idle. This is an adjustment from today, where the most recent owner is adopted for all runs.
- **CLI:** `warm-status [--json]` lists the slots (slot, pid, port, state, run id, generation, age) and `warm-stop --all` kills them all. Both are reachable via `exec:java`, and MCP/UI only forward to them.
- **Flags (reasons the plan could fail, and the adjustments):**
  - Memory: one JVM per concurrent run. The default cap of 2 addresses this.
  - Port and handle races: handled by per-slot directories and the busy compare-and-set.
  - A shared `target/`: handled by the bug 3 generation snapshots.
  - Windows file locks on deleting old generations: deferred deletion, as above.
  - Hardlink mutation: copy, not hardlinks.
  - `consumer_sim.py` `warm_pids()`/`stop_warm()` (`:296-327`) glob only `workbench/warm-jvm.v1`: update them to the slot layout.
  - Docs that state "the warm JVM" in the singular must be rewritten (see Phase 4 Docs).

---

## Phase 0: version 2.20.0

**Functionality:** the project version becomes `2.20.0`, following the pattern of the 2.1.19 bump (`docs/maintainer/2.1.19-issuefix-roadmap.md` Phase 0). AGENTS.md: never reuse a published version.

**Files:**
- `build.gradle` (`version = '2.20.0'`)
- `maven-consumer-project/pom.xml` (`pickleball.version`)
- `.github/workflows/agent-contract.yml` and `sqlite-index.yml` push filters: add `2.20.0`
- `docs/direct-control-handoff.md` header and its packaged copy
- current-version assertions in `src/test/java/tools/dscode/launcher/WorkbenchConsumerScenarioEndToEndTest.java`, `src/test/java/tools/dscode/testengine/MavenWorkspaceLaunchEndToEndTest.java`, `src/test/java/tools/dscode/launcher/WorkbenchCliCoverageGateTest.java`, and `docs/roadmaps/workbench-cli-inventory.json`

Do not rewrite historical fixtures.

**Docs:** add an empty `## 2.20.0` section above `## 2.1.19` in `docs/release-notes.md` and the packaged copy. Phases 1–4 fill it in.

**Targeted tests:** the three version-asserting tests above, plus `python scripts/verify_agent_contract.py` and `python scripts/sync_consumer_guidance.py --check`.

## Phase 1: or-chain placeholder resolution (bug 1)

**Functionality:** the fix decision above. Add one `debug` line when a chain member resolves (`chain member resolved: <text> -> <resolved>`).

**Docs:**
- `docs/block-conditionals.md`, after "Phrase-style conditions": one short paragraph saying that each phrase of an `, or` / `, and` chain fills its placeholders when it runs, and that a phrase the chain never reaches is not resolved.
- A new row in the `docs/agent/change-checklist.md` Verify table: "or-chain placeholder", feature `lazy-branch-resolution.feature`.
- A release note.
- Packaged copies.

**Tests:**
- Consumer scenario `@OrChainRepro` in `maven-consumer-project/src/test/resources/features/lazy-branch-resolution.feature` (already `@all @lazy-branch-resolution`). Group the forms there; do not add a feature file per form. Include:
  - the report's checks A, B, C, and D
  - unquoted `<N>`
  - `number` qualifier operands
  - a `RUN SCENARIOS` parameter source, following `docs/component-scenarios.md` and the `component-scenarios.feature` conventions
  - a three-phrase `or` chain where only the third phrase is true
  - a block `until ..., or "<X>" equals "done":` that stops on the second phrase
  - a negative guard: an `and` chain whose first phrase is false must not resolve the second phrase's `<$count branch ...>` (use the existing `branch count` steps)
- Unit test in `src/test/java/tools/dscode/common/treeparsing/preparsing/LazyBranchResolutionTest.java`: the second chain member has `evaluationsResolved=true` and `suppressResolve=false`. A skipped THEN body is still suppressed.

**Targeted tests:** `LazyBranchResolutionTest`, `ConditionalBranchArgumentTest`, and `PhraseSyntaxTest`. Then `publishToMavenLocal` and the consumer with `pkb_tags=@lazy-branch-resolution`.

## Phase 2: `number` / `integer` / `decimal` qualifier (bug 2)

**Functionality:** the semantics decision above.
- Add a numeric-operand check, `ValueWrapper.isQualifiedNumeric(boolean integerOnly)` or similar, next to `asBigDecimal`.
- Run the pre-check in `AssertionOperations` for EQUAL and the four ordering operations when either `ElementMatch` has `ElementType.NUMERIC_VALUE`. EQUAL then calls `numericEquals`.
- Keep the existing no-qualifier behavior.
- Log the non-numeric outcome at `warn` in conditional context and include it in the assertion failure message.

**Docs:**
- `docs/dynamic-steps.md`, § Assertions: a new "Number qualifier" paragraph with examples (`"1" number is equal to "$1.00"` passes, `"1" is equal to "1.00"` fails as text, `"abc" number is equal to "abc"` fails with the message, and `IF:` is false with a warning). The same short note goes in `docs/log-and-assert-steps.md` and `docs/block-conditionals.md` (one sentence each, linking to `dynamic-steps.md`).
- Change-checklist row "number qualifier" in feature `phrase-plain-forms.feature`.
- Release note.
- Packaged copies.

**Tests:**
- Consumer scenario `@NumericEqualityRepro` in `phrase-plain-forms.feature` (already `@all @phrase-plain-forms`). It covers every passing row of the report (`"1"` vs `"1.00"`, `"$1.00"`, `"1.5"` vs `"1.50"`, the unquoted mixes, and `<=`/`>=` with `$`) using `, ensure`. Add `IF:` forms that select the branch, an `IF: "abc" number is equal to "abc"` that skips, and a no-qualifier text-equality regression via `IF:`.
- Consumer-hosted Java checks `maven-consumer-project/src/test/java/tools/dscode/common/assertions/NumberQualifierChecks.java`. They observe without failing the parent:
  - `ensure "abc" number is equal to "abc"` hard-fails with the message
  - `verify` soft-fails
  - `is not equal to` with a non-numeric operand still fails (it is not inverted)
  - `"1.5" integer is equal to "1.5"` fails
  - the margin form passes

  Run them from `internal-framework-java-checks.feature` as a new `@internal-java-checks @number-qualifier` scenario. The step annotation is a single string literal.
- Root unit test `src/test/java/tools/dscode/common/assertions/ValueWrapperNumericTest.java` covering the operand classification and `numericEquals` (blank, letters, `%`, grouping, multi-dot, `Sc` symbols, integer rule).

**Targeted tests:** `ValueWrapperNumericTest`, `MultilineAssertTest`, `MultilineAssertFeatureTest`, and `AssertionClauseSplitterTest`. Then `publishToMavenLocal` and the consumer with `pkb_tags=@phrase-plain-forms or @number-qualifier`.

## Phase 3: launcher resource/Java sync and generation snapshots (bug 3)

**Functionality:** the bug 3 fix decision:
- split fingerprints in `pickleball-control-protocol`
- the sync decision on cache hit, under `warm/sync.lock`
- generation snapshots by copy
- `-Dpkb_workbench_test_classes` honoured by `WorkbenchDirectRun`
- unreferenced-generation GC

Log the `stage=sync` and `stage=materialize gen=<hash> files= bytes= ms=` lines in `launch-timing.txt` and one `agent-log` line. Keep controller isolation: no core dependency in `pickleball-workbench` or the protocol module.

**Docs:**
- `docs/workbench-agent-detail.md:124`: replace "A cache hit ... skips the wrapper" with the split-fingerprint rule.
- `docs/pickleball-workbench.md`: the warm classpath generation.
- `docs/configuration.md`: the warm state files.
- `pickleball-workbench/AGENTS.md:128`: one sentence saying that the launcher cache hit applies the same input-fingerprint rule.
- Feature-map rows for Workbench speed and launch.
- Release note.
- Packaged copies.

**Tests:**
- Unit tests in `src/test/java/tools/dscode/launcher/WorkbenchSpeedCacheTest.java` (extend it; keep the existing tests):
  - a cache hit with an edited `src/test/resources/features/*.feature` gives resources mode
  - an edited `.java` file gives full mode
  - unchanged gives skip
  - a racy-mtime same-size rewrite is detected
  - the new generation directory is a copy (inode differs on POSIX)
  - an in-use generation is not deleted
- Protocol unit tests in `pickleball-control-protocol/src/test/.../speed/` for the split fingerprints.
- **consumer_sim multi-run block** `observed_2_20_bug3()` (flag `--observed-2-20-only` runs bugs 3 and 4):
  1. Generate `features/sim-called-component.feature` plus a caller feature.
  2. Run A: `discover` and assert the old step text in `events`.
  3. Edit the component step, then run B: `discover` with a new run id and assert the new text, `stage=sync mode=resources`, and a new generation.
  4. Run C: no edit, assert `mode=skip` and the same generation.
  5. Edit the glue `.java` file, then run D: assert `mode=full`.

  Each run reports `summary status=PASSED`. Clean up the generated files as the report-7 block does.

**Targeted tests:** the unit tests above, then `python3 scripts/consumer_sim.py --observed-2-20-only`, twice in a row (repeat starts). Do not use `@all`.

## Phase 4: capped warm-JVM pool (bug 4)

**Functionality:** the bug 4 fix decision:
- slots, `PING` state, selection, cap, queue and refuse, same-run-id refusal, recycle and retire
- `releaseIdle` replacing `release` at all five call sites
- owner adoption scoped per run
- the `warm-status` and `warm-stop --all` CLI verbs, registered in the CLI inventory and coverage gate

MCP and UI only forward to them. Write `warm-pool.log` with the toggle and size cap from the checklist.

**Docs:**
- `docs/workbench-agent-detail.md` (§ warm JVM, `stop`, and the run-isolation paragraph)
- `docs/pickleball-workbench.md`
- `docs/configuration.md` (`pkb_workbench_warm_pool_max`, `pkb_workbench_warm_queue_timeout`, `pkb_workbench_warm_log`, the slot layout replacing `warm-jvm.v1`)
- `docs/consumer-agent-guide.md` (concurrent agents: what busy refusal looks like)
- `pickleball-workbench/AGENTS.md`
- feature map
- release note stating that concurrent runs never share a JVM, a busy run is never killed by another command, and the queue/refusal rules
- packaged copies

**Tests:**
- Unit tests in `src/test/java/tools/dscode/launcher/WorkbenchWarmFallbackTest.java` and `WorkbenchWarmLifecycleTest.java` (extend; keep every existing test). New stub-based tests:
  - idle reuse
  - a second run starts slot 2 while slot 1 is busy
  - at cap with `--warm-wait=0`, refused with exit 2, the message, and no fallback
  - at cap with a wait, it gets the slot when slot 1 finishes
  - a stale `watchHash` with a busy slot does not kill it and marks retire
  - a stale idle slot is recycled
  - `stop --run-id=B` does not kill the slot serving run A
  - session-start failure does not kill a busy slot
  - two clients racing for one idle slot, where exactly one gets it and the other takes a new slot
  - same run id refused
  - `warm-pool.log` rotates at the cap
- Update `SpeedTypesTest` for the slot handle format.
- **consumer_sim multi-run block** `observed_2_20_bug4()`:
  1. Generate a long scenario (a no-browser `wait` of about 40 s via `, wait 40 seconds`, or the existing step-duration pattern) and a short scenario.
  2. Start run L (`discover`) in the background.
  3. While L is running, edit a test resource, then run S (`discover`, new run id). Assert that S passes on slot 2, and L later exits 0 with `summary status=PASSED`, not `LAUNCH_ERROR` and without the "connection dropped" error.
  4. Repeat with `pkb_workbench_warm_pool_max=1`. Assert that S is refused with exit 2 and the busy message, and that L still passes.
  5. Repeat with `--warm-wait=120`. S waits and then passes.
  6. While L is running, run `stop --run-id=S` and a failing `isolate --run-id=sim-missing-workspace`. Assert that L still passes.
  7. Static isolation: run two concurrent runs with different `pkb_runvars` (a distinct `pkb_tags` and a saved value in each) and assert that each run record shows only its own values.
  8. At the end, `stop` leaves no warm JVM. `assert_warm_dead` and `warm_pids` read the slot layout.

  The block runs in `consumer_sim.ps1` on the Windows CI job too.

**Targeted tests:** the launcher and protocol unit tests above, `./gradlew verifyStrictControllerIsolation :pickleball-workbench:test`, and `python3 scripts/consumer_sim.py --observed-2-20-only` twice, plus `--report7-only` once (no regression in restart and timing gates). Do not use `@all`.

## Phase 5: docs, feature map, release notes

**Functionality:** none.

**Docs:**
- Finish `## 2.20.0` in `docs/release-notes.md` in plain language, one bullet per bug, each ending with whether a normal `mvn test` is affected (bugs 1–2: yes, the behavior fix; bugs 3–4: no).
- `docs/agent/feature-map.md` rows: dynamic steps/conditionals, assertions, Workbench speed/launcher, consumer_sim.
- `docs/agent/change-checklist.md` rows from Phases 1–2.
- `docs/maintainer/consumer-sim-harness.md`: the new blocks and flag.
- Packaged guidance copies via `python scripts/sync_consumer_guidance.py`, then `python scripts/refresh_agent_index.py`.
- Remove nothing from `INVESTIGATION.md`. Add a one-line pointer at its top saying this roadmap holds the corrections.

**Targeted tests:** `python scripts/verify_agent_contract.py`, `python scripts/refresh_agent_index.py --check`, and `python scripts/sync_consumer_guidance.py --check`.

## Phase 6: full regression (once)

1. `scripts/agent_validate.sh` (contract, index, guidance, `./gradlew test`).
2. `./gradlew verifyStrictControllerIsolation :pickleball-workbench:test`.
3. `./gradlew publishToMavenLocal`, then the consumer with `@all` (CHROME_HEADLESS), for the core behavior of bugs 1–2.
4. `@control-bridge`, then `@step-override-bridge`, sequentially with `pkb_parallel=80`.
5. Full `python3 scripts/consumer_sim.py` (all blocks including the new ones), then `--observed-2-20-only` again as a second multi-run pass.
6. After pushing, report the Windows CI run (`agent-contract.yml`, job `windows`, which runs `consumer_sim.ps1`) and its result.

Report anything that could not run and why. Never claim a pass without running it.

## Completion scoreboard (every reply)

Version · phases done · tests run and results · commit SHAs · blocker (none or a description).

All phases are mandatory. Do not stop early, do not skip phases, do not mark anything optional or deferred: complete every phase listed, in full. Stop only on an honest test failure or a hard-rule violation.
