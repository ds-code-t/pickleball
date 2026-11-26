# Grok Build prompt: pickleball 3.0.0 spine R2

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R2 only, in full. R1 is already on this branch. Do not redo it and do not start R3.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch, never `3.0.0`. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R2-cucumber-as-adapter-decoupling-ingest.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`
- **Index:** `docs/roadmaps/3.0.0-spine/README.md`
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R2-scoreboard.md`
- **Toolchain:** JDK 21. `.\gradlew.bat` and `.\maven-consumer-project\mvnw.cmd`. Never `.\gradlew.bat --stop`.

## Read first

1. `AGENTS.md` (execution engine, public contracts, test policy, `pkb_callpath` maintainer rule).
2. The design doc sections on the Cucumber import ban, flag steps, context words, and `exists`.
3. The R2 roadmap, every phase.
4. `StepRegistry.java`, `PickleballEngine.java`, `NativeScenarioRun.java`, `FlagSteps.java`, `GeneralSteps.retryStep`, `PhraseData.java`, `SqlDialect.java`.

Use the class names, column names, and test names in the roadmap. Do not invent a second matcher.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred.
- Targeted tests only, the commands in the roadmap phase. The full regression is the last phase of R8. Do not run `@all`.
- Windows only. Append new Windows-only classes to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section.
- Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Updating `area-b-control-flow.feature` from "this phrase does nothing" to the real behavior is required. That is not a weakened test.
- The native engine, outside `tools.dscode.engine.adapter.cucumber`, must not import `io.cucumber` or `com.microsoft.playwright`. `PatternMatcher` has no Cucumber import. The parity test may import Cucumber.
- Do not change `pkb_callpath` or `pkb_componentpath` grammar. `store_schema` stays 1.
- When a phase removes a tech-debt paragraph, the code must already do what the new paragraph says. Rewrite `docs/tech-debt.md` in place for the four leftovers in P3 and the four in P4.
- Docs rewritten in place. History in `docs/release-notes.md`.
- Before every push, each command alone: `python scripts/sync_consumer_guidance.py` then `python scripts/refresh_agent_index.py`. Update `tools.dscode.browser.SurfaceAssetsTest` only if surface-asset bytes change.
- Scoreboard `docs/maintainer/3.0.0-spine/R2-scoreboard.md`: SHA, command, pass count, after each phase, before the push.
- Git, each command alone, no `&&`, `;`, or pipe. Fetch, rebase, then push. Never force. Never `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation.

## CLI-first and logging

`store hydrate` prints `translated=` and `failed=`. `engine run` and `engine step` are the execution verbs. No new GUI. `EngineLog` capabilities `ingest`, `match`, `watchdog`. Off unless named.

## Phases

- **P1.** `CucumberIngest`, `PatternMatcher`, move `CucumberModelConverter`, split Cucumber matching into `CucumberStepRegistry`. `SpineCucumberImportBanTest`.
- **P2.** `PickleballEngine` and `NativeScenarioRun` read `StoreScenarioSource` when Store is on. A missing row is `error:` and `fix: store hydrate`, not a silent file read. Cucumber adapter executes the same rows.
- **P3.** Ceilings, `StepWatchdog`, `FlagSteps` and `RETRY:` write real flags, `PhraseData.runUntilOperation` loops for inline `until`. Consumer feature `area-b-control-flow.feature` and `AreaBControlFlowChecks`. Publish to maven local before the consumer tag. Use the feature's real tag, not `@all`.
- **P4.** Context-word table, new relation `between` in `dev.domengine.query.Relations`, `exists`, `in any parent`, `with local context` / `with all contexts`. Update the capability-list tests. Do not special-case `AllContextsScan`.

## Finish

P4 green, scoreboard filled, branch pushed. Reply with the scoreboard and the tip SHA.
