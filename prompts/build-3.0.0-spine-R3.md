# Grok Build prompt: pickleball 3.0.0 spine R3

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R3 only, in full. R1 and R2 are on this branch. Do not redo them and do not start R4.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R3-step-definition-language-adapters.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. Read **Distribution and licensing** before P2. Dan's approval of that section is required before you spawn the bundled Node for step definitions. If this prompt was started, that approval is the go-ahead for the packaging described there. Do not switch to `frontend-maven-plugin`, do not copy Node into the pickleball jar, and do not run npm in R3.
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R3-scoreboard.md`
- **Toolchain:** JDK 21. `.\gradlew.bat`. Never `.\gradlew.bat --stop`.

## Read first

1. `AGENTS.md` (execution engine, Playwright packaging, `verifyPlaywrightPackaging`).
2. Design sections on the node seam, fixtures, and distribution.
3. The R3 roadmap, every phase.
4. `PlaywrightNode.java`, `StoreStepCatalog.java`, `ForeignStepHost` does not exist yet: create it where the roadmap says.

Use the roadmap's class names, the seven `fn` names, and the test class names.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred.
- Targeted tests only. No `@all`. No full regression. That is R8's last phase.
- Windows only. Append new Windows-only classes to `docs/tech-debt.md`. Do not delete the Linux and macOS section.
- Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. `PlaywrightSpecIngestLiveTest` does not skip when Chrome is missing. It fails with the missing-binary message. This PC has Chrome.
- `tools.dscode.engine` does not reference `PlaywrightNode`, `step-host.mjs`, or `com.microsoft.playwright`. The host lives in `tools.dscode.launcher`.
- Do not download a browser. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` stays. Do not run npm. Do not add a Node distribution.
- `secret` and secret `entityGet` return `~MASK;` or a reference, never cleartext.
- Docs in place. History in `docs/release-notes.md`. `docs/playwright.md` gains step-definition and spec-ingest sections.
- Before every push, each command alone: `python scripts/sync_consumer_guidance.py` then `python scripts/refresh_agent_index.py`. Surface-asset hash in `tools.dscode.browser.SurfaceAssetsTest` only if those bytes change.
- Scoreboard `docs/maintainer/3.0.0-spine/R3-scoreboard.md` after each phase: SHA, command, counts.
- Git, each command alone. No `&&`, `;`, or pipe. Never force. Never `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation.

## CLI-first and logging

`store glue` and `engine step` are the verbs. No new GUI. `EngineLog` capability `host`. Off unless named. Do not log secret return values.

## Phases

- **P1.** `ForeignStepHost`, `JavaStepHost`, `GlueStaticScan` with `java.lang.classfile`. Warning text is exact. Hydrate still succeeds.
- **P2.** `NodeStepHost`, `step-host.mjs`, stdio JSON, the seven functions. Erasable TypeScript only. Bare npm imports fail as a rebuild. Extend `NoRuntimeDownloadTest`.
- **P3.** Execute `defineStep` and JS `Given(...)` from `glue/js`. One Node process per `PickleballEngine.run`, stopped in `finally`. Consumer scenario in an existing feature that does not need a browser. Publish to maven local. That feature's tag, not `@all`.
- **P4.** `PlaywrightTestIngest`, `fixtures` table, `PlaywrightSpecHost`. Definitions at ingest. Page handle at execution. Specs are not rewritten as Gherkin. Live test uses the Chrome lookup from `LiveChromeCdpTest`.

## Finish

P4 green, scoreboard filled, branch pushed. Reply with the scoreboard and the tip SHA.
