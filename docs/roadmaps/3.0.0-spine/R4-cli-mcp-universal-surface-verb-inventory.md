# R4 — CLI and MCP universal surface, verb inventory

Branch `3.0.0-spine` only. Never `master` or any 2.x branch. No tags, releases, publishing, or force-push.

Requires R1, R2, and R3. Design: `docs/plans/2026-11-25-spine-design.md`. Scoreboard: `docs/maintainer/3.0.0-spine/R4-scoreboard.md`. Build prompt: `prompts/build-3.0.0-spine-R4.md`.

Every phase is mandatory and must be completed in full. Nothing in R4 is optional or deferred. Stop only on an honest test failure you cannot fix, or on a hard-rule violation.

Targeted tests only. Do not run consumer `@all`. Full regression waits until the end of R8. Windows only. Append new Windows-only class names to `docs/tech-debt.md`.

Never weaken, skip, or delete a test. Keep Gherkin. Step annotations stay single string literals. Never run `.\gradlew.bat --stop`.

Fetch and rebase before every push. Push after every green phase. Each git command stands alone. Before every push run `python scripts/sync_consumer_guidance.py` and `python scripts/refresh_agent_index.py`. This roadmap changes CLI help, so `GuidanceCommandGoldenTest` and `documented-help.txt` move with the help text. If surface-asset bytes change, update `tools.dscode.browser.SurfaceAssetsTest`. R4 should not need that unless P4 touches `DesktopScreenshot` resources. It does not change `SurfaceAssets`.

The Workbench jar does not gain a dependency on pickleball core, Cucumber, Selenium, or Playwright. Direct MCP means the pickleball JVM calls the Java method. The Workbench process forwards over the control protocol. `verifyStrictControllerIsolation` is not this phase's targeted test. Do not "fix" a compile error by adding `implementation project(':')` to the workbench.

## CLI-first and logging checklist

- Every verb in this roadmap is a CLI verb first.
- MCP registrations in `pickleball-workbench` forward. They do not reimplement.
- New log lines use `EngineLog` or `BrowserLog`. No new logger.

## P1 — One Java method, two transports

### Functionality

For each command family the launcher already dispatches (`store`, `engine`, `domengine`, `dashboard`, and the agent verbs in `WorkbenchAgentCommands` / `WorkbenchSessionCommands`), the method that does the work is callable without spawning a shell.

`tools.dscode.launcher.VerbDispatch` (new) holds `int run(String verb, Map<String, String> flags, PrintStream out, PrintStream err)`. `PickleballWorkbenchLauncher.dispatch` and the MCP handler both call it. CLI parsing stays in `WorkbenchCommandLine` and `StoreCli`. They produce the flag map. They do not copy business logic into the parser.

Workbench MCP (`WorkbenchMcpServer`, `WorkbenchMcpTools`) stops building a process command line for the hot path. It sends the verb and the flag map on the existing authenticated control protocol. The worker calls `VerbDispatch.run`. `pkb_mcp_transport=cli` keeps today's shell-out, for debugging a verb that disagrees. Default is `direct`. Document the property in `docs/configuration.md`.

There is no MCP-only verb. A tool registered in `WorkbenchMcpTools` whose name is not a CLI verb fails the test below.

Streams stay inside `VerbDispatch` and do not return until the stream ends or `--once` is set. The set, all of which already exist as CLI verbs, is: `browser-events` with `--follow`, `observe` with `--follow`, `log` tail with `--follow`, `domengine session log` with `--follow`, `chat` with `--follow`, `record` with `--follow`. Do not add a socket transport.

`pkb_engine_log=host` is the wrong log for this. Use the existing launcher log path if one exists for MCP. If none exists, log one line through `EngineLog` capability `match` only when `pkb_engine_log=dispatch` is set. Add `dispatch` as an `EngineLog` capability. Entity is the verb name. Off by default.

### Docs

`docs/direct-control-handoff.md` and `docs/dynamic-control-api.md`: MCP hot path is `VerbDispatch`, shell-out is `pkb_mcp_transport=cli`. `AGENTS.md` MCP paragraph. Release notes.

### Tests

Create `src/test/java/tools/dscode/launcher/VerbDispatchTest.java`. `store status --json` through `VerbDispatch.run` returns 0 and does not start a `Process`. A second case with `pkb_mcp_transport=cli` is not required to spawn in the unit test; assert the property is read and the direct path is the default.

Create `src/test/java/tools/dscode/launcher/McpDirectHotPathTest.java`. Invoke one workbench MCP tool through the in-process server test harness that `WorkbenchMcpServerTest` already uses (extend that class if the harness is private; do not copy it). Assert the recorded transport is `direct` and the output equals `VerbDispatch` for `store status`.

Extend `WorkbenchCliCoverageGateTest` only if the parser sets change. This phase should not add verbs.

```
.\gradlew.bat :test --tests tools.dscode.launcher.VerbDispatchTest --tests tools.dscode.launcher.McpDirectHotPathTest --console=plain
.\gradlew.bat :pickleball-workbench:test --tests tools.dscode.workbench.mcp.WorkbenchMcpServerTest --console=plain
```

If `WorkbenchMcpServerTest` is in another package, use the class the workbench module actually has. Search before writing a duplicate.

## P2 — Verb catalog

### Functionality

`tools.dscode.launcher.VerbCatalog` lists every CLI verb from the parser sets `WorkbenchCliCoverageGateTest` already reads (`docs/roadmaps/workbench-cli-inventory.json`), plus every `StoreCli` subcommand and every `DomEngineMain` verb. Each row: `name`, `source` (`workbench`, `store`, `domengine`, `engine`, `cross`), `kind` (`cli` or `stream`), `entry` (class and method). `kind=stream` is only the six verbs from P1. Every other row is `cli`. The `mcp-only` count is 0.

`engine inventory` prints the table. `--json` prints one `AgentEnvelope` whose `items` are the rows. Adding `engine inventory` updates `docs/roadmaps/workbench-cli-inventory.json` in the same commit so `WorkbenchCliCoverageGateTest` stays green, and updates `documented-help.txt` so `GuidanceCommandGoldenTest` stays green.

`docs/verb-inventory.md` is generated by `VerbCatalog.writeMarkdown` and committed. `VerbCatalogTest` fails if the file differs from a fresh generate. A Node page is not part of this phase. The test `VerbCatalogTest.privateRouteFails` scans `workbench-ui` only if that directory exists. It does not exist yet. The test asserts the directory is absent and passes. R5 changes the assertion to scan the directory.

### Docs

`docs/verb-inventory.md`, help text, `AGENTS.md` one paragraph pointing at the inventory, release notes.

### Tests

Create `src/test/java/tools/dscode/launcher/VerbCatalogTest.java`. Catalog contains `store`, `engine run`, `desktop` (the existing domengine desktop verb), and the six stream verbs. No row has a blank `entry`. Stream rows are exactly the six. Regenerating the markdown matches the committed file.

```
.\gradlew.bat :test --tests tools.dscode.launcher.VerbCatalogTest --tests tools.dscode.launcher.WorkbenchCliCoverageGateTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --console=plain
```

## P3 — One envelope, one element record, args files

### Functionality

This phase closes leftovers 6.8.1, 6.8.5, and 6.8.6. It is a breaking change for `store --json` and domengine JSON. Release notes say so.

`AgentEnvelope.apply` already stamps `v`, `ok`, `op`, `ms`, `state`, `sum`, `items`, `next`, `trunc`, `err`. `StoreCli` JSON and `DomEngineMain` JSON go through it. The previous JSON object becomes `items`. Top-level keys that used to be `command`, `count`, `total`, `rows` are not duplicated at the top level. Callers read `items`.

`tools.dscode.common.agentfast.ElementRecord` builds the only element object: `handle`, `identity` (`chain` array), `role`, `name`, `tag`, `bounds` (`x`, `y`, `width`, `height`), `backendNodeId`, `frame`. `find`, `snap`, `act`, and desktop query call it. Extra backend fields go under `extra` and are omitted unless `--fields` lists them.

Every verb accepts `@<file>` or `--args-file=<file>`. The file is a JSON object of flags. `WorkbenchCommandLine` merges it under the explicit flags (explicit flags win). Unknown keys fail with `error:` and `fix:`. MCP passes the object into the same merge. A secret value in the file is not echoed in the error.

`pkb_engine_log=dispatch` logs the verb and whether args came from a file. Not the file body.

### Docs

`docs/dynamic-control-api.md` envelope section: store and domengine JSON are envelopes. `docs/configuration.md` quoting section points at `@args.json` as the way to avoid shell quoting, and keeps the quoting table. Rewrite the tech-debt rows 6.8.1, 6.8.5, and 6.8.6 in place (remove them, because the behavior now exists). Update `docs/verb-inventory.md` by regenerating it. Release notes name the break.

### Tests

Extend `tools.dscode.common.agentfast.AgentDataFoundationTest` or create `AgentEnvelopeStoreTest` if that class does not cover store JSON. `store status --json` has `v=1` and the old status fields under `items`.

Create `src/test/java/tools/dscode/common/agentfast/ElementRecordTest.java`. A find hit and a desktop hit from a fixture JSON produce the same keys. An unknown extra key is under `extra` and disappears without `--fields`.

Create `src/test/java/tools/dscode/launcher/ArgsFileTest.java`. `@file` supplies `--name`. A CLI flag overrides the file. An unknown key exits non-zero. The error text does not contain a fixture secret that was in the file.

```
.\gradlew.bat :test --tests tools.dscode.common.agentfast.ElementRecordTest --tests tools.dscode.launcher.ArgsFileTest --tests tools.dscode.launcher.VerbCatalogTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --console=plain
```

Add the store-envelope test class to that command, whichever name you created.

## P4 — Route body, pw in execute-steps, desktop launch, desktop screenshot

### Functionality

**6.6.5.** In `tools.dscode.browser.proto.RouteCommands`, action `modify` sends `rule.body` as the continued request body on all three hooks: Playwright `Route.ResumeOptions.setPostData`, CDP `Fetch.continueRequest` field `postData`, BiDi `continueRequest` body field. Headers stay as they are. `fulfill` still sets the response body. A null body keeps today's header-only modify.

**6.6.6.** `WorkbenchSessionCommands.executeStep` and the `execute-steps` file runner: a line whose first token is `pw` is parsed by `PwCommand` and run through `VerbDispatch`. It is not parsed as Gherkin. A line that is not `pw` stays on the Gherkin path.

**6.7.1.** `dev.domengine.cli.DomEngineMain` gains `desktop launch`. Flags `--exe=<path>` and `--name=<window>`. On Windows, start the process and attach with the existing live attach in `WindowsUia`. On any other OS, exit 2 with `error: desktop launch is implemented for Windows` and `fix: run this verb on Windows`. Do not call `AtSpi` or `MacAccessibility` from launch.

**6.7.4.** `desktop screenshot --name=<window> [--out=<file>]` reads the window rectangle from `WindowsUia` and calls `DesktopScreenshot.capture`. Write the PNG to `--out` or to the run artifact path the browser screenshot already uses. Edit `DesktopScreenshot` so the failure `fix:` does not mention `grim`. Wayland remains `error: Wayland session detected` with the existing X11 `fix:`. Windows is the tested path.

Register all three verbs in `VerbCatalog`, `workbench-cli-inventory.json`, and the help files in the same commit.

`BrowserLog` entity `playwright` logs a modify that included a body, as `modify body-bytes=<n>`, not the body.

### Docs

`docs/playwright.md` route section: `modify` sends the request body. `docs/tech-debt.md` rows 6.6.5, 6.6.6, 6.7.1, 6.7.4 removed. Desktop guide in `AGENTS.md` control-engine paragraph: `desktop launch` and `desktop screenshot` exist; `grim` is not a supported path. Release notes. Regenerate `docs/verb-inventory.md`.

### Tests

Create `src/test/java/tools/dscode/browser/proto/RouteModifyBodyTest.java`. A fixture rule `--action=modify --body={"a":1}` produces a Playwright resume postData, a CDP `postData`, and a BiDi body equal to that JSON. Header merge still happens. Null body does not set postData.

Extend `tools.dscode.launcher.FieldReportRegressionTest` or create `ExecuteStepsPwTest`. A steps file with one `pw page title` line and one Gherkin line runs `PwCommand` for the first and the Gherkin runner for the second. Use the in-process path `FieldReportRegressionTest` already uses. Do not launch a browser if the existing `pw page title` test double can answer. If it cannot, point `pw` at a stub by the seam `PwCommand` already has. Do not add a new stub framework.

Create `dom-engine/src/test/java/dev/domengine/desktop/DesktopLaunchTest.java`. On Windows, launch a small executable that is not Notepad. Use `cmd.exe /c echo pkb-r4` only if attach can see a window; otherwise start the test JVM's own helper `main` that opens a `javax.swing.JFrame` titled `pkb-r4-launch` and attach to that title. Close the frame in `finally`. Assert the attach name. A non-Windows OS branch is not exercised here. A separate method asserts the non-Windows error by calling the OS check with a fake `os.name` if `DomEngineMain` exposes that seam. If it does not, extract the OS check into `DesktopLaunch.supportedOs()` and unit-test that method with an argument. Do not skip.

Create `dom-engine/src/test/java/dev/domengine/desktop/DesktopScreenshotVerbTest.java`. Capture the `pkb-r4-launch` frame, assert the PNG header bytes, assert the `grim` string is absent from `DesktopScreenshot` source (read the file as text in the test).

```
.\gradlew.bat :test --tests tools.dscode.browser.proto.RouteModifyBodyTest --tests tools.dscode.launcher.ExecuteStepsPwTest --tests tools.dscode.launcher.VerbCatalogTest --tests tools.dscode.launcher.GuidanceCommandGoldenTest --console=plain
.\gradlew.bat :dom-engine:test --tests dev.domengine.desktop.DesktopLaunchTest --tests dev.domengine.desktop.DesktopScreenshotVerbTest --console=plain
```

Use the real execute-steps test class name if you extended an existing one instead of creating `ExecuteStepsPwTest`.

## Out of R4

Do not build the Node window, dynamic mode, snapshot tables, the classloader, or the browser version ladder.
