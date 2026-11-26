# Grok Build prompt: pickleball 3.0.0 spine R4

You are a fresh headless Grok Build session on Dan's Windows PC. You have not seen the planning chat. Implement R4 only, in full. Do not start R5.

- **Repo:** https://github.com/ds-code-t/pickleball
- **Branch:** `3.0.0-spine` only. Never `master`, never a 2.x branch. No tags, releases, publishing, or force-push.
- **Roadmap (the spec):** `docs/roadmaps/3.0.0-spine/R4-cli-mcp-universal-surface-verb-inventory.md`
- **Design (decisions are closed):** `docs/plans/2026-11-25-spine-design.md`. The envelope break, the empty MCP-only set, and the stream list are closed.
- **Scoreboard:** `docs/maintainer/3.0.0-spine/R4-scoreboard.md`
- **Inventory the tests already guard:** `docs/roadmaps/workbench-cli-inventory.json` and `tools.dscode.launcher.WorkbenchCliCoverageGateTest`. `GuidanceCommandGoldenTest` guards help text.
- **Toolchain:** JDK 21. `.\gradlew.bat`. Never `.\gradlew.bat --stop`.

## Read first

1. `AGENTS.md` (Workbench isolation, CLI-first, agent envelope).
2. Design sections on MCP, the verb inventory, `ElementRecord`, and desktop screenshot.
3. The R4 roadmap, every phase.
4. `PickleballWorkbenchLauncher.java`, `WorkbenchMcpTools.java`, `AgentEnvelope.java`, `RouteCommands.java` modify arms, `DesktopScreenshot.java`, `DomEngineMain.java`.

Use the roadmap's class names. Do not add pickleball core to the workbench build. Do not add `implementation project(':')`.

## Hard rules (every phase)

- Every phase is mandatory and must be completed in full. Nothing is optional or deferred.
- Targeted tests only. No `@all`. Full regression is R8's last phase.
- Windows only. `desktop launch` is tested on Windows. Append classes to the Linux and macOS section of `docs/tech-debt.md`. Do not delete that section.
- Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals.
- Workbench MCP forwards over the control protocol to `VerbDispatch` in the pickleball JVM. Default transport is `direct`. `pkb_mcp_transport=cli` is the debug shell-out. There are no MCP-only verbs.
- Store and domengine JSON become `AgentEnvelope` with the old object under `items`. That break is in scope. Release notes say so.
- `DesktopScreenshot` fix text must not recommend `grim`. Do not launch Notepad. The roadmap names the frame title `pkb-r4-launch`.
- A new verb updates, in the same commit: the CLI help, `workbench-cli-inventory.json`, `docs/verb-inventory.md`, and `documented-help.txt`.
- Docs in place. Remove tech-debt rows only in the phase that implements them (6.8.1, 6.8.5, 6.8.6 in P3; 6.6.5, 6.6.6, 6.7.1, 6.7.4 in P4).
- Before every push, each command alone: `python scripts/sync_consumer_guidance.py` then `python scripts/refresh_agent_index.py`. Update `tools.dscode.browser.SurfaceAssetsTest` only if those bytes change.
- Scoreboard `docs/maintainer/3.0.0-spine/R4-scoreboard.md` after each phase.
- Git, each command alone. No `&&`, `;`, or pipe. Never force. Never `master`.

```
git fetch origin 3.0.0-spine
git rebase origin/3.0.0-spine
git push origin HEAD:3.0.0-spine
```

- Stop only on an honest test failure you cannot fix without weakening a test, or on a hard-rule violation.

## CLI-first and logging

Every capability is a CLI verb. MCP calls `VerbDispatch.run`. `EngineLog` capability `dispatch`. `BrowserLog` entity `playwright` logs modify body length, not the body.

## Phases

- **P1.** `VerbDispatch`. Workbench MCP hot path is direct. Stream verbs stay the six named in the roadmap. They are also CLI verbs.
- **P2.** `VerbCatalog`, `engine inventory`, committed `docs/verb-inventory.md`. MCP-only count is 0.
- **P3.** Envelope everywhere. `ElementRecord`. `@file` and `--args-file` on every verb. Unknown keys fail. Secrets are not echoed.
- **P4.** `RouteCommands` modify sends the request body. `execute-steps` sends `pw` lines to `PwCommand`. `desktop launch`. `desktop screenshot`.

## Finish

P4 green, scoreboard filled, branch pushed. Reply with the scoreboard and the tip SHA.
