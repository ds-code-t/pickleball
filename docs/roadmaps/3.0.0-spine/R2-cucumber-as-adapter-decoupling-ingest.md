# R2 — Cucumber as adapter, decoupling and ingest

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R1 on the same branch. Design: `docs/plans/2026-11-25-spine-design.md`. Scoreboard: `docs/maintainer/3.0.0-spine/R2-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R2.md`.

Every phase is mandatory and must be completed in full. Nothing in R2 is optional or deferred. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Targeted tests only. Full regression waits until the end of R8. Windows only. Append new Windows-only class names to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section.

Never weaken, skip, or delete a test. Keep Gherkin syntax. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`. When a phase removes an empty method body, the consumer scenario that used to assert "this does nothing" must be updated to the new real behavior in the same change. That is not a weakened assertion. It is the behavior the leftover asked for.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. R2 does not change surface-asset bytes. If it does, update `tools.dscode.browser.SurfaceAssetsTest`.

Rewrite docs in place. History goes in `docs/release-notes.md`. Record the SHA on the scoreboard.

Do not change `pkb_callpath` or `pkb_componentpath` grammar. `store_schema` stays 1.

## CLI-first and logging checklist

- Ingest and match behavior is visible from `store hydrate` (counts) and `engine run` / `engine step`. No new GUI.
- Log through `tools.dscode.engine.EngineLog`. Capabilities `ingest`, `match`, `watchdog`. Off unless `pkb_engine_log` names them.

## P1 — Ingest translation and the native matcher

### Functionality

Today `tools.dscode.engine.match.StepRegistry` imports `io.cucumber.cucumberexpressions.ExpressionFactory`, Cucumber hook annotations, and `TagExpressionParser`, and it walks jars with `JarFile` and `java.nio.file`. The native path must stop doing that.

New class `tools.dscode.engine.match.PatternMatcher`. It matches a step text against the stored `step_definitions.pattern` regex. It does not import `io.cucumber`.

New class `tools.dscode.engine.adapter.cucumber.CucumberIngest`. Called from `StoreHydrator` after a feature or glue file is written. It translates:

- Feature path membership into `files.enabled` (R1 already sets the default; ingest must not invent a second flag).
- A Cucumber expression (`{string}`, `{int}`, `{float}`, `{word}`, `{bigdecimal}`, `{biginteger}`, `{double}`, `{byte}`, `{short}`, `{long}`) into a regex plus `step_definitions.parameter_types` JSON. A pattern that is already a regex (starts with `^` or contains an unescaped `(` that is not a Cucumber parameter) is stored as that regex and `parameter_types` is `[]`.
- Glue-package membership into `step_definitions.enabled`.
- Tags into `scenario_tags` (R1 writes them; this class is the single writer, called by the hydrator, so `StoreGherkin` does not grow a second parser).

`CucumberModelConverter` moves from `tools.dscode.engine.convert` to `tools.dscode.engine.adapter.cucumber`. Update its callers. The native model types in `tools.dscode.engine.model` stay.

`StepRegistry` splits. The method the native engine calls (`matchNative`) uses `PatternMatcher` and the rows from `StoreStepCatalog`. The method the cucumber adapter calls stays on `ExpressionFactory` and keeps the imports, in `tools.dscode.engine.adapter.cucumber.CucumberStepRegistry` (move the Cucumber-specific methods there). `StepRegistry.java` after the split has no `io.cucumber` import.

Ambiguous matches still fail. The message stays the one `AmbiguousMatchException` already uses. Parameter types that `CucumberIngest` cannot translate fail that definition at hydrate with `error:` and `fix: store glue show <path>`, and the previous row is kept. They do not silently match differently.

`pkb_callpath` and `pkb_componentpath` are copied into `run_variables` by ingest as opaque strings. Their lookup rules are not modified.

CLI: `store hydrate` prints `translated=<n> failed=<n>` when `pkb_store_log=hydrate` is on, and also prints those two counts on the normal hydrate summary line so a human sees them without the log. `engine step` is unchanged in this phase except that a native match uses `PatternMatcher`.

### Docs

Rewrite `docs/cucumber-compatibility.md` so the native engine is described as row-matching and Cucumber is the adapter. Update `AGENTS.md` execution-engine paragraphs that say `StepRegistry` uses `ExpressionFactory` for the native path. Release notes.

### Tests

Create `src/test/java/tools/dscode/engine/match/PatternMatcherParityTest.java`. Fixture file `src/test/resources/engine/pattern-matcher-fixtures.txt` lists pattern and step text pairs taken from `FlagSteps`, `GeneralSteps`, and `ObjectRegistrationSteps` (the annotation strings, copied as text). For each pair, `PatternMatcher` and a cucumber `ExpressionFactory` in this test only (the test may import Cucumber; the main `PatternMatcher` class may not) return the same match and the same captured groups. A pattern the ingest rejects is in the fixture as a negative line and both sides agree it is rejected.

Create `src/test/java/tools/dscode/engine/adapter/cucumber/CucumberIngestTest.java`. A `{string}` pattern becomes a regex that matches a quoted string and stores one parameter type. An unknown `{nope}` fails the definition and leaves the old row.

Create `src/test/java/tools/dscode/engine/SpineCucumberImportBanTest.java`. Walk `build/classes` under `tools/dscode/engine`, skip `adapter/cucumber`, and fail if a class file's constant pool contains `io/cucumber/` or `com/microsoft/playwright/`. Run this test after the compile that the Gradle test task already performs. Do not shell out.

```
.\gradlew.bat :test --tests tools.dscode.engine.match.PatternMatcherParityTest --tests tools.dscode.engine.adapter.cucumber.CucumberIngestTest --tests tools.dscode.engine.SpineCucumberImportBanTest --console=plain
```

## P2 — Native runs read rows only

### Functionality

`tools.dscode.engine.PickleballEngine.run` and `NativeScenarioRun` take the scenario from `StoreScenarioSource` when Store is on, which is the default. They do not call `FeaturePathLoadCache`, `ResourceLocations`, or `PathLists`. `FileScenarioSource` remains for an explicit in-memory test and for `pkb_store=false`. When Store is on and the file is deleted after a successful hydrate, the native run still executes the stored scenario.

`CucumberAdapterRun` receives `EngineFeature` objects produced from those rows. It does not scan a feature directory. It still dispatches Java glue with Cucumber. `pkb_engine=cucumber` therefore hydrates (existing auto-hydrate) and then executes the rows. A disabled scenario (`enabled=0` or `@pkb-ignore`) is not passed to either engine.

`FeaturePathLoadCache` stays compiled for the file-only path. A comment in that class names `pkb_store=false` as the only production caller. Do not delete it.

`pkb_engine_log=ingest` logs one line per scenario skipped because `enabled=0`.

### Docs

`AGENTS.md` sentences that say feature loading falls back to the file when the path is not in the database: when Store is on, a missing row is a hard `error:` and `fix: store hydrate`, not a silent file read. When Store is off, the file path remains. Update `docs/cucumber-compatibility.md` and `docs/configuration.md` (`pkb_engine`). Release notes. This is a behavior change for a Store-on run of a file that was never hydrated.

### Tests

Create `tools.dscode.engine.NativeRunUsesStoreOnlyTest`. Hydrate a one-step feature into a temp store, delete the feature file, run `PickleballEngine` native, assert the step ran. A second case never hydrates and asserts the error names `store hydrate`.

Extend `tools.dscode.engine.parity.ParityHarness` coverage only if an existing test class already calls it. Do not weaken `DualRun`. Add one method on the existing parity test class if one exists in `src/test/java/tools/dscode/engine/parity/`; otherwise create `NativeCucumberRowParityTest` that runs one feature from rows on both engines and compares status and step text.

`SpineCucumberImportBanTest` still passes. Re-run it in this phase's command.

```
.\gradlew.bat :test --tests tools.dscode.engine.NativeRunUsesStoreOnlyTest --tests tools.dscode.engine.NativeCucumberRowParityTest --tests tools.dscode.engine.SpineCucumberImportBanTest --console=plain
```

If the parity class already has another name, extend that class and put its real name in the scoreboard notes. Do not keep both.

## P3 — Ceilings, watchdog, flag steps, inline until

### Functionality

This phase closes four leftovers: unused step ceiling fields, the hung step watchdog, accepted steps that do nothing, and inline until.

Columns on `steps`, added in R1's style if R1 did not already add them (R1's roadmap adds them; if they are missing, add them here the same `ALTER TABLE` way): `max_iterations`, `timeout_seconds`, `retry`, `ignore_failures`, `log_failures_continue`, `run_if_finished`, `always_run`, `run_if_passing`, `run_if_failed`, `run_if_hard_failed`, `run_if_soft_failed`, `until_mode`.

`CucumberIngest` sets those columns from the step text:

- A leading `RETRY:` sets `retry=1` and the remainder is the step text that actually runs. The retry count is `max_iterations` when set, otherwise `pkb_stepRepeatMaxCount`.
- `IGNORE FAILURES` as its own step sets `ignore_failures=1` on the following step in that scenario, which is how the phrase reads. A suffix `AND IGNORE FAILURES` on `ALWAYS RUN` or `RUN IF SCENARIO PASSING` sets the flag on that same step.
- `LOG FAILURES BUT CONTINUE SCENARIO` sets `log_failures_continue=1` on the following step.
- `RUN IF SCENARIO FINISHED` sets `run_if_finished=1`.
- `AND SCENARIO FINISHED` on `RUN IF SCENARIO PASSING` sets both `run_if_passing=1` and `run_if_finished=1` on that step.
- The existing flag phrases set `always_run`, `run_if_passing`, `run_if_failed`, `run_if_hard_failed`, `run_if_soft_failed` the way `GlobalConstants` already names them.

`tools.dscode.coredefinitions.FlagSteps` methods stay, still one string literal each, and their bodies call one package-visible method `tools.dscode.engine.runtime.StepFlags.applyFromPhrase(String)` so a native run and a cucumber run share the writer. `GeneralSteps.retryStep` calls the same writer. The methods are no longer empty.

`NativeScenarioRun` reads the columns and skips or runs the step. `io.cucumber.core.runner.StepBase` assigns `stepMaxIterations` and `stepTimeoutSeconds` from those columns when the cucumber adapter runs, so the fields are no longer dead on that path either.

`tools.dscode.engine.runtime.StepWatchdog` is a daemon started by `NativeScenarioRun` for the scenario and stopped in a `finally`. Every 60 seconds it looks at the current non-repeating step. If that step's `timeout_seconds` (or `pkb_stepMaxTime` when the column is null) has been exceeded, it marks the step failed with `error: step exceeded its time cap` and `fix:`. It does not interrupt the step thread. The release note says a hung driver call may still be running. A repeating `until` keeps using the existing cap inside the loop and is not double-failed by the watchdog.

Inline until: `tools.dscode.common.treeparsing.parsedComponents.PhraseData.runUntilOperation` loops while the condition is true, bounded by `max_iterations` and the timeout. A mid-line `until`, a comma `until`, and a period-ending `until` call that method. A block `until` ending in `:` or `?` keeps its current nested-step loop and also honors the per-step ceiling when the columns are set. Default when the columns are null stays `pkb_stepRepeatMaxCount` and `pkb_stepMaxTime`.

`pkb_engine_log=watchdog` logs each sample and each fire. Entity is the scenario name.

### Docs

Rewrite the four paragraphs in `docs/tech-debt.md` (unused ceilings, hung watchdog, accepted steps, inline until) so they describe the new behavior, or delete those paragraphs if the behavior is no longer debt. Do not leave them saying the fields are never assigned. Update `docs/configuration.md` where it says there is no per-step ceiling: a step row may set one, and the run-wide values remain the default. Update `AGENTS.md` only where it repeats the old debt. Add consumer-visible sentences to the guide that already documents `ALWAYS RUN` (search `docs/` for that phrase and update that guide in place). Release notes.

### Tests

Extend `src/test/java/tools/dscode/common/treeparsing/PhraseSyntaxTest.java` with a mid-line `until` that loops twice and stops, and a period-ending `until` that loops. Do not delete the existing illegal-argument case.

Extend `maven-consumer-project/src/test/java/com/example/pickleball/AreaBControlFlowChecks.java` and `maven-consumer-project/src/test/resources/features/area-b-control-flow.feature`. `RETRY:` runs the following action twice when the first fails and the second passes. `IGNORE FAILURES` on the following step does not fail the scenario. `AND IGNORE FAILURES` applies. `RUN IF SCENARIO FINISHED` runs after the scenario outcome is known. Keep the existing passing-flag cases.

Create `src/test/java/tools/dscode/engine/runtime/StepWatchdogTest.java`. A step whose `timeout_seconds` is 1 and whose body blocks on a latch is marked failed. The latch is released by the test after the assertion so the thread exits. No sleep longer than the ceiling plus one sample.

Create `src/test/java/tools/dscode/engine/runtime/StepCeilingTest.java`. `max_iterations=2` stops a block `until`. A null column still stops at `pkb_stepRepeatMaxCount`.

The consumer feature is not part of `@all` execution in this phase. Compile and run the Java check with the framework test task only if `AreaBControlFlowChecks` is already on the framework test classpath. It lives in the consumer project, so run it as a consumer test with the tag already on `area-b-control-flow.feature`. Read that feature's tags and pass that tag. Do not use `@all`.

```
.\gradlew.bat :test --tests tools.dscode.common.treeparsing.PhraseSyntaxTest --tests tools.dscode.engine.runtime.StepWatchdogTest --tests tools.dscode.engine.runtime.StepCeilingTest --console=plain
```

Publish to maven local is required before the consumer module can see the change:

```
.\gradlew.bat publishToMavenLocal
```

Then, with the feature's real tag (read the file; the command below uses `@area-b` only if that tag is on the feature, otherwise substitute the tag that is there):

```
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test -Dpkb_runvars.pkb_browser=CHROME_HEADLESS -Dpkb_runvars.pkb_tags=@area-b
```

If the feature tag is different, use that tag. Record the tag you ran.

## P4 — Context words, exists, any parent, phrase-local context

### Functionality

This phase closes the other four step-engine leftovers.

Ingest in `CucumberIngest` writes `steps.context_mode` and `steps.context_scope` from the phrase text. `tools.dscode.common.treeparsing.parsedComponents.PhraseData` is the runtime reader. `AllContextsScan` does not grow its own syntax.

Mappings, all case-insensitive, applied only when the word is a context word and not part of a quoted string:

| Phrase | Stored query relation | Notes |
|---|---|---|
| `inside` | `inside` | Existing `Relations.inside` |
| `within` | `inside` | Alias |
| `near` | `near` | Existing |
| `next to` | `near` | Alias |
| `following` | `after` | Existing document order |
| `preceding` | `before` | Existing |
| `on` | context `in` | Same as today's `in` context, not a geometry relation |
| `of` | context `in` | Same |
| `between A and B` | new relation `between` | Both anchors required |

`between` is implemented in `dom-engine/src/main/java/dev/domengine/query/Relations.java` and recognized in `Expressions.java` and the help list in `dev.domengine.cli.DomEngineMain`. A candidate is between the two anchors when its center lies inside the axis-aligned box that has the two anchor centers as opposite corners. Displayed-only, same as the other geometry relations. `Query.ENGINES` includes `between`. `dev.domengine.query.ControlEngineCapabilitiesTest` currently asserts the eight-relation list. Update that expected list to nine. That is a behavior change of the capability list, not a deleted assertion.

`exists`: in `PhraseData`, an element operand is `is present`. A value operand is non-null and not an empty reference. Document both. Do not make `exists` a synonym for `is displayed`.

Any parent: the phrase `in any parent` sets `context_scope=any-parent`. `PhraseData.getPhraseContextList` expands that to every ancestor context the element walk already knows. `AllContextsScan` is not given a new branch.

Phrase-local: trailing `with local context` sets `context_mode=local` (do not search all contexts; pin `Loading` to the inherited `in` / `from` / `for`). Trailing `with all contexts` sets `context_mode=all`. Default `inherit` keeps today's `ElementMatch.searchesAllContexts` result. `ElementMatch.searchesAllContexts` reads `context_mode` before its current ALL_CONTEXTS check.

`pkb_engine_log=match` logs the relation chosen for a translated context word.

### Docs

Remove or rewrite the four tech-debt paragraphs (unsupported context words, `exists`, any parent, phrase-local context). Update the guide that documents element context (the feature-map row points at `docs/custom-element-definitions.md` and `docs/dynamic-steps.md`; update the one that actually describes `in` / `from` / `for`). Update `DomEngineMain` help. Release notes name `between` and the aliases. `docs/agent/feature-map.md` relation list gains `between`.

### Tests

Create `dom-engine/src/test/java/dev/domengine/query/BetweenRelationTest.java`. One candidate inside the box is kept. One outside is dropped. A missing second anchor is an error, not zero hits.

Update `ControlEngineCapabilitiesTest` and `RelativeLocatorTest` expectations so the relation list includes `between`.

Create `src/test/java/tools/dscode/common/treeparsing/PhraseContextIngestTest.java` for the alias table, including a quoted `"near"` that is not treated as a relation.

Create `ExistsConditionTest.java` in the same package. Element `exists` matches `is present` and does not match a hidden element that is present in the DOM only if `is present` would not. Follow the existing `is present` semantics rather than inventing a new one. Value `exists` is true for a saved non-null value and false for a missing one.

Create `AnyParentContextTest.java`. `in any parent` sets the scope and finds an element in an ancestor. `AllContextsScan` source is unchanged aside from being called by `PhraseData` as it is today. Assert with a direct call, not by adding a switch inside `AllContextsScan`.

Create `PhraseLocalContextTest.java`. `with local context` makes `searchesAllContexts` return false for a `Loading` phrase that would otherwise scan. `with all contexts` returns true.

Add one scenario to the existing consumer feature `maven-consumer-project/src/test/resources/features/catalog-context.feature` for `inside` and `in any parent`, and extend `LoadingContextChecks` (consumer) or the framework `LoadingCategoryDefinitionTest` if that is where the assertion lives. Read the feature-map row and extend the test it names. Do not add a new feature file.

```
.\gradlew.bat :dom-engine:test --tests dev.domengine.query.BetweenRelationTest --tests dev.domengine.query.ControlEngineCapabilitiesTest --tests dev.domengine.query.RelativeLocatorTest --console=plain
.\gradlew.bat :test --tests tools.dscode.common.treeparsing.PhraseContextIngestTest --tests tools.dscode.common.treeparsing.ExistsConditionTest --tests tools.dscode.common.treeparsing.AnyParentContextTest --tests tools.dscode.common.treeparsing.PhraseLocalContextTest --console=plain
```

Publish and run the consumer tag that `catalog-context.feature` already has. Record that tag. Do not use `@all`.

## Out of R2

Do not spawn Node. Do not change the Swing window. Do not add snapshot tables. Do not change browser download behavior.
