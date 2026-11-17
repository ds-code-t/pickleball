# 2.20.0 bug 5: warm runs skip the consumer runner's lifecycle hooks

Reported on 2026-11-17 against `2.20.0` at `849f32af` (after bugs 1–4 were fixed).

## Report (transcribed)

In Pickleball 2.20.0, Workbench warm runs never call the consumer runner's `@LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)` methods, and no error is logged. Anything those methods register is missing for the run: custom element categories, starting contexts (such as a default switch into an iframe), and system properties.

The cause is the new warm-JVM build-folder copy. `WorkbenchSpeed.startSlot` → `WarmGeneration.pinClasspath` replaces `target/test-classes` with `.pickleball/v/<version>/workbench/warm/gen-<hash>/test-classes`, and does not set `-Dpickleball.workbench.testOutputRoot`. `DynamicSuiteBootstrap.isPreferredTestOutputRoot` is unchanged and only searches folders ending in `/target/test-classes/`, `/build/classes/.../test/`, or `/workbench/live/classes/`. So the runner is never found, and `LifecycleManager` registers no hooks. Glue `@Before`/`@After` hooks still run because they come from `pkb_glue`, which hides the problem.

The same probe passes on 2.1.19, and on 2.20.0 with `PKB_WORKBENCH_SPEED=sequential`. It fails on 2.20.0 warm runs, whether the warm JVM is reused or freshly started.

Workaround until the fix: `PKB_WORKBENCH_SPEED=sequential`, or stay on 2.1.19.

## Verified root cause

Confirmed in code at `849f32af`:

- `WorkbenchSpeed.startSlot` launches the warm JVM with `-cp` from `WarmGeneration.pinClasspath`, which puts `warm/gen-<hash>/test-classes` first and drops the project's `target/`/`build/` output. No `pickleball.workbench.testOutputRoot` is passed.
- Each `RUN` sends `-Dpkb_workbench_test_classes=<gen>/test-classes` (`generationProperties`), and `WorkbenchDirectRun` selects that directory for discovery, but it never sets `pickleball.workbench.testOutputRoot` either.
- `DynamicSuiteBootstrap.findDynamicSuiteSubclass` gets only a `DirectorySelector`, so it falls back to `findDynamicSuiteSubclassInPreferredTestRoots`, which filters classpath roots with `isPreferredTestOutputRoot`. No warm root matches, so `DefaultDynamicSuite` is used, with only a `debug` line.
- `LifecycleManager` reads hooks from `DynamicSuiteBootstrap.getDiscoveredSuiteClass()`, which stays null, so no `@LifecycleHook` method runs. Runner `globalTestDefaults` are skipped too; Workbench `-D` RunVars mask that.

Reproduced with the new consumer_sim block before the fix: `observed220-hook-fresh` failed (`summary status=FAILED`, the probe step reported the hook never ran).

## Fix

- `WorkbenchSpeed.startSlot` starts every warm slot with `-Dpickleball.workbench.testOutputRoot=<gen>/test-classes`.
- `WorkbenchSpeed.generationProperties` sends the same property with every `RUN`, so a reused JVM and a JVM started after a generation re-pin both carry the current copy. A value the caller already supplied wins.
- `WorkbenchDirectRun` sets the property to the selected generation copy when the run did not supply one (defense in depth).
- `DynamicSuiteBootstrap.isPreferredTestOutputRoot` also accepts `.../workbench/warm/gen-<hash>/test-classes[/]`, with backslashes normalized for Windows.
- When discovery falls back to the default suite in a launch that expected a consumer runner (a directory selector, `pickleball.workbench.testOutputRoot`, or `pkb_workbench_test_classes`), it prints a `WARNING:` to stderr and the log naming the scanned roots and the fix. It can no longer fail silently.

A normal `mvn test` and `PKB_WORKBENCH_SPEED=sequential` were never affected.

## Tests

- Unit: `DynamicSuiteBootstrapWorkbenchRootTest` (warm generation roots, Windows paths, strict negatives, explicit root, missing-runner warning) and `WarmRunnerRootTest` (per-run tokens, caller override, fresh-slot argument, `WorkbenchDirectRun` pin).
- Consumer: `lifecycle-before-run-hook.feature` (`@lifecycle-before-run-hook`) with the probe step `the consumer runner BEFORE_CUCUMBER_RUN hook has run`.
- Multi-run consumer_sim: `python3 scripts/consumer_sim.py --observed-2-20-hook-only` (also part of `--observed-2-20-only`): fresh warm JVM, reused, re-pinned generation, re-pinned and reused, and sequential.

## Validation (2026-11-17, Linux, JDK 21, targeted only; no full regression by request)

- The unit tests above, plus `WorkbenchWarmPoolTest`, `WorkbenchSpeedCacheTest`, `WorkbenchLaunchCorrectnessTest`, `Release218RecommendationsTest`, `GuidancePackagingTest`, and `GuidanceCommandGoldenTest`: all pass.
- `--observed-2-20-hook-only` without the fix: fails at `observed220-hook-fresh`. With the fix: passes (run twice).
- `--observed-2-20-only` (bugs 3, 4, and 5 in one multi-run pass): passes.
- `refresh_agent_index.py --check`, `sync_consumer_guidance.py --check`, `verify_agent_contract.py`: pass.
