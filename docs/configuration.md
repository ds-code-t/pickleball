# Execution Configuration

> **Runnable examples:** [`configuration-system-properties.feature`](../maven-consumer-project/src/test/resources/features/configuration-system-properties.feature) covers normal source precedence, JVM configuration, and controlled-run behavior. The consumer [`profiles.yaml`](../maven-consumer-project/src/test/resources/profiles.yaml) and [`profiles_local.yaml`](../maven-consumer-project/src/test/resources/profiles_local.yaml) demonstrate shared and local named-profile configuration.

Pickleball execution properties use canonical lowercase `pkb_*` names. JVM property names beginning with `pkb_` are normalized case-insensitively.

```properties
pkb_tags=@smoke
pkb_browser=chrome
pkb_loglevel=debug
```

## Normal configuration sources

From stronger to weaker, the public normal configuration precedence is:

1. JVM system properties;
2. `globalTestProperties()`;
3. `pickleball_local.properties`;
4. `pickleball.properties`;
5. `globalTestDefaults()`.

Each stronger source overwrites the same key from weaker sources. The naming is intentional:

- `globalTestDefaults()` supplies the runner's lowest-level fallback values;
- `pickleball.properties` supplies shared project configuration and overrides runner defaults;
- `pickleball_local.properties` supplies local project overrides;
- `globalTestProperties()` supplies runner-enforced project values that outrank property files;
- JVM `-D` properties are invocation-specific overrides and have the highest normal precedence.

The fully resolved normal RunVars become the in-memory `default_profile`. If neither `pkb_profile` nor `pkb_runvars` is supplied, that normal configuration becomes the effective RunVar set and is serialized into `pkb_run_profile`.

`pickleball_local2.properties` is a third property file. It overwrites `pickleball_local.properties`, which overwrites `pickleball.properties`. `ProjectPropertySources` is the only reader. A normal launch reads classpath resources only, as ISO-8859-1 (`Properties.load(InputStream)`), which is the pre-2.1.18 behavior. UTF-8 bytes for é (`C3 A9`) in a classpath file are not the character é. Project-root and `src/test/resources` copies are read only for a Workbench seed, dry-run, or protected-reference lookup, as UTF-8, and they overwrite the classpath value of the same key. Those same bytes in a Workbench project file are é. A bad `\u` escape throws instead of being skipped. A normal launch and Workbench `hint` / `resolve-runvars` both construct the runner and call `resolveShared`, so `globalTestDefaults()` and `globalTestProperties()` are in the preview when the consumer runner is visible. The dry-run prints the Workbench launch default `pkb_reportingmode=diagnostic` for a blank saved copy. That default is written into the copy once; a later prepare does not rewrite an unchanged copy. A Workbench launch then reads the saved workspace copy with `Workspace.loadForLaunch` and does not apply hooks or property files again. See [One resolution path](ai-run-configuration.md#one-resolution-path).

A hydrate of sandbox `main` copies `pkb_*` run-variable keys from `pickleball.properties`, `pickleball_local.properties`, `pickleball_local2.properties`, and the matching `profiles.yaml` files into `run_variables`. Each name is read from the project root and then from `src/test/resources`. A later file wins. `store var list` prints the stored rows. `store var set` on main writes that value back to the properties file. A sandbox `store var set` updates the sandbox database only. `store var enable <name> --on|--off` turns a stored name on or off. A cleartext secret is rejected with `error:` and `fix:`. Values are not written to the store log. See [Store entities](store-entities.md).

```java
@Override
public void globalTestDefaults() {
    PKB_props.glue("com.example.pickleball");
    PKB_props.features("classpath:features");
    PKB_props.configPath("configs");
    PKB_props.plugins("pretty");
    PKB_props.tags("@all");
    PKB_props.browser("chrome");
}
```

`PKB_props.plugins` stores `pkb_plugins`. That value is copied to Cucumber's `cucumber.plugin` only when `pkb_run_purpose` starts with `workbench-` and the value is not blank. A normal `mvn test` does not copy it. An unset or blank `pkb_plugins` leaves Cucumber's own plugin list unchanged on every run.

Use `globalTestProperties()` only for project values that should intentionally outrank the shared and local property files:

```java
@Override
public void globalTestProperties() {
    PKB_props.environment("QA");
}
```

## Local overrides and JVM values

Shared `pickleball.properties` and local `pickleball_local.properties` contain ordinary property names. A local file can override only the values that need to differ from the shared project configuration:

```properties
pkb_environment=QA
pkb_browser=chrome
pkb_parallel=4
```

Use `-D` only when supplying JVM properties:

```bash
mvn test -Dpkb_tags="@forms and @state-assertions" -Dpkb_loglevel=debug
```

For JVM properties whose names begin with `pkb_`, Pickleball removes one matching outer pair of single or double quotes from the value. Embedded quotes remain literal. Non-Pickleball JVM properties and values authored in resource property files keep normal Java semantics.

## RunVars, controls, and metadata

Execution RunVars are the effective `pkb_*` settings that affect test execution or evidence behavior. Profile selectors, direct-input controls, derived summaries, and diagnostic lineage are not RunVars.

Important controls:

```text
pkb_profile
pkb_profile_<name>
pkb_runvars
pkb_runvars.<pkb_var>
pkb_overriderunvars
pkb_overriderunvars.<pkb_var>
pkb_run_profile
pkb_options
```

`pkb_run_profile` is the canonical resolved output and is reserved for Pickleball. External `pkb_run_profile` / `pkb_run_profile.<pkb_var>` input is rejected; use `pkb_runvars` / `pkb_runvars.<pkb_var>` for controlled runs, or `pkb_overriderunvars` / `pkb_overriderunvars.<pkb_var>` for a sealed complete map.

## DOM engine

`pkb_composite` is `auto` (default), `on`, or `off`. Unset, `auto`, and `off` use the library adapter (`SeleniumAdapter`). They do not start the Java engine. `on` (also `true`, `required`, `composite`) forces the Java engine and logs `pkb_composite=on`. A missing engine on that path prints `error:` and `fix:` and stays on the library. Direct CDP/BiDi is `AgentProtocolController`, not `ElementOperations`.

`tools.dscode.browser.BrowserFacade` is the browser wire other groups call. `ProtocolSelector.forDriver(WebDriver)` returns `CdpAdapter` for Chrome, Chromium, and Edge when `se:cdp`, `webSocketDebuggerUrl`, or `goog:chromeOptions.debuggerAddress` / `ms:edgeOptions.debuggerAddress` resolves to a websocket (`host:port` is read from `http://host:port/json/version`, 2 second cap). It returns `BidiAdapter` for Firefox when `webSocketUrl` or `se:bidi` is a websocket. Otherwise it returns `SeleniumAdapter`. A missing endpoint or a socket that fails to open logs one `WARN browser.fallback` per run and reason (`cdp-missing`, `bidi-missing`, `cdp-connect`, `bidi-connect`). `ProtocolSelector.forDriver(driver, LIBRARY)` is the scripted path and always returns `SeleniumAdapter`. `ElementOperations` on the default path calls `ProtocolSelector.forDriver(driver, LIBRARY)`, which is `SeleniumAdapter`, and does not call `send`. `DriverConstruction.ready` makes that same `LIBRARY` selection after a local or remote driver exists. Construction does not open the published `se:cdp` or BiDi websocket. Direct CDP/BiDi is `AgentProtocolController`. `PlaywrightAdapter` implements the same interface over `com.microsoft.playwright:playwright` 1.65.1 (a normal dependency, range `[1.40.0, 2.0.0)`). It is selected with `pkb_protocol=playwright` or `Preference.PLAYWRIGHT` and attaches over CDP only (see Playwright backend below). `SeleniumAdapter` does not call `executeCdpCommand`. `evaluateIsolated`, `addPreload`, `addBinding`, and `send` on that adapter log one WARN (`selenium-executeScript`, `selenium-preload`, `selenium-binding`, `selenium-send`) and then use `JavascriptExecutor` or refuse the direct method.

`pkb_browser_log` is `off` (default), `all`, or a comma list of `facade`, `selector`, `selenium`, `cdp`, `bidi`, `playwright`, `fallback`. Read order is the system property then env `PKB_BROWSER_LOG`. The file is `browser-YYYY-MM-DD.log`, date-stamped, and rolls at 1 MiB. `pkb_browser_log_dir` (env `PKB_BROWSER_LOG_DIR`) sets the directory. The default is the temp directory `pickleball-browser-logs`. `pkb_browser_fallback_log` is `warn` (default), `info`, or `off` (property, then env `PKB_BROWSER_FALLBACK_LOG`). `off` prints none of the fallback lines. The fallback line is one per process and reason even when `pkb_browser_log` is off.

`pkb_composite_fallback_log` is `warn` (default), `info`, or `off`. Read order matches `pkb_composite`: system property, `pkb_runvars.pkb_composite_fallback_log`, env `PKB_COMPOSITE_FALLBACK_LOG`, then the run variable. `pkb_composite=on` prints `error:` and `fix:` once per run and reason (missing, client, join) and records a `composite.fallback` diagnostic event (`run-events.jsonl` and `compositeFallbacks` on `run-index.json`). That path does not consult this key. Unset, `auto`, and `off` do not look for an engine and do not log a fallback. An explicit executable that is not the Java launcher logs one `INFO composite.engine override` line naming the source key and path, even when `pkb_composite_log` is off. `pkb_composite_engine` unset does nothing. `java` warns once that the key is no longer used. Any other value fails at engine start.

Env `COMPOSITE_BIN` is working-directory relative. Property `pkb_composite_bin`, `pkb_runvars.pkb_composite_bin`, and env `pkb_composite_bin` resolve against the project. Direct control and element lookup use that same rule.

Diagnostic lineage is separate metadata:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
pkb_run_id
pkb_agent_id
pkb_run_group
pkb_run_sequence
pkb_run_who
pkb_run_why
```

Lineage and coordination metadata survive controlled execution but are excluded from `pkb_run_profile` and `runProfileFingerprint`. Do not put `pkb_run_id`, the run directory, `pkb_diagnostic_output`, or a browser profile path inside `pkb_runvars`. A confirm replay must not reuse another run's directory or browser profile.

### Linux desktop control

Linux desktop control is AT-SPI plus `xdotool` on X11 or Xvfb only. Wayland is unsupported: a Wayland-only session (`WAYLAND_DISPLAY` set and `DISPLAY` unset, or `XDG_SESSION_TYPE=wayland` without `DISPLAY`) fails `desktop attach --live` with `error: Wayland session detected; desktop control needs X11 or Xvfb (set DISPLAY)` and a `fix:` line. Xwayland with `DISPLAY` set is allowed. `desktop launch --exe=<path> --name=<window>` starts that process and attaches on Windows. Any other OS exits 2 with `error: desktop launch is implemented for Windows` and `fix: run this verb on Windows`. `desktop screenshot --name=<window> [--out=<file>]` reads the window rectangle from Windows UI Automation. `grim` screenshots are not a supported desktop-control path. When `pkb_warm=on`, `DesktopTree` keeps one tree per attached window. A structure-changed event drops that window. A tree that would cross the heap refuse line is not stored. `pkb_warm=off` reads through.


### Desktop drivers (opt-in)

`pkb_desktop_driver=native|auto|flaui|novawindows|winappdriver|mac2` routes desktop capture, click, and type through an opt-in WebDriver desktop driver. The default is `native`, which probes nothing. `pkb_desktop_driver_url` is the driver or Grid URL, and defaults to `http://127.0.0.1:4723`. With `auto` and no driver running, the native backend is used after one warning. `setup desktop-driver --driver=<name> --confirm` installs a pinned, checksum-verified driver. `doctor --desktop` reports each driver. See [Desktop drivers](desktop-drivers.md).

### Playwright backend

Playwright for Java 1.65.1 (Apache-2.0) and its `driver-bundle` (Apache-2.0, with a bundled Node.js under the Node.js license) are normal dependencies in the published POM. They are not inside pickleball's jar. The default is still no browser download: Playwright is created with `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` and attaches with `chromium().connectOverCDP(...)`. `browser.source` of `cdn` or `any` may download through Playwright's installer. `local`, a blank source, and a missing browser block do not. Set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` yourself too if your own code uses Playwright outside those two sources.

- `pkb_protocol=playwright` (property, then env `PKB_PROTOCOL`) makes the protocol selector attach Playwright to the browser pickleball launched when the config has no `browser.channel`. Unset keeps the existing CDP, BiDi, and Selenium choice. When Playwright cannot attach and no `browser.channel` selected Playwright, the selector logs one WARN per reason and falls back. When `browser.channel` resolved to Playwright, that failure is `error:` and `fix:` and does not open Selenium.
- `pkb_cdp_endpoint` (property, then env `PKB_CDP_ENDPOINT`) is an existing CDP endpoint (`http://127.0.0.1:<port>` or a `ws://` browser URL). It wins over the launched driver's own endpoint.
- With no endpoint, the Playwright connection fails with `error: no CDP endpoint for Playwright ...` and a `fix:` line. No browser is launched.
- Playwright attaches to Chromium-family browsers (Chrome, Edge, Chromium, Electron) only.
- Attaching to a browser you already run (channel, Playwright Extension, endpoints, discovery, relaunch) and the Selenium/Playwright hand-off are in [Playwright](playwright.md).

Consumer download size: the three Playwright artifacts on the runtime classpath are 232,943,839 bytes (about 233 MB: `playwright` 687,401, `driver` 3,309,493, `driver-bundle` 228,946,945). The `driver-bundle` jar carries Node for every platform. Pickleball's own jar did not grow from the dependency.

### Browser block

A browser config may keep `browser` as a string (`browser: chrome`). That string is the Selenium browser name. It does not select Playwright. The shared `CHROME_HEADLESS` files keep that string and set `source: local`, which is the local Selenium Chrome adapter those scenarios already use and which never downloads.

Set `browser` to a map when you want the version ladder or Playwright. `browser.channel` of `chrome`, `msedge`, `chromium`, `firefox`, or `webkit` selects Playwright. `safari` selects Selenium `safaridriver`. `pkb_protocol=selenium` forces Selenium even when a channel is set. A Playwright connect failure then fails with `error:` and `fix:` and does not open Selenium. Configs with no `browser.channel` keep today's `pkb_protocol` behavior, including the quiet fallback.

```
browser:
  channel: chrome
  version: ""
  source: local
  headless: true
  viewport: { width: 1280, height: 720 }
  userAgent: ""
  arguments: []
  framePolicy: top
  selenium:
    gridUrl: ""
    bidi: off
  playwright:
    connectOverCdp: true
```

`source` is `local`, `cdn`, or `any`. Blank source and a missing browser block keep today's no-download path. `local` uses a registered install (on Windows, the Chrome and Edge locations already probed for the driver) and does not read the Playwright browser cache. A local version is read from the version directory beside that executable, or from the executable's file version, without starting the browser. A miss fails with `error:` and `fix:` and does not download. `cdn` reads the Playwright cache first, then Playwright's installer. The cache root is `PLAYWRIGHT_BROWSERS_PATH` when that JVM property or environment variable is a path. `0` means the default location: `%LOCALAPPDATA%\ms-playwright` on Windows, `~/Library/Caches/ms-playwright` on macOS, and `~/.cache/ms-playwright` on Linux. A cached version comes from `browsers.json` in that cache, then a version directory, then the browser binary's file version. The browser is not started. If that install cannot be reached and a cached build exists, the cache is used and the resolve log says `resolve cache-fallback`. `any` tries `local`, then `cdn`. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` stays set unless source is `cdn` or `any`. `pkb_browser_cdn_host` sets `PLAYWRIGHT_DOWNLOAD_HOST` for that download. `BrowserLog` entity `resolve` logs the requested version, the source, and the resolved version. It does not log a download URL.

`version` precision: `131` is any 131 build, `131.0` is the newest patch of that minor, `131.0.6778.69` is that build, and `latest` is the highest the source allows. `local` with no version is any local browser of that channel. `local` with a version is that version or a failure.

`framePolicy` is `top` (the default), `iframe`, or `all`. `top` does not change finds. Ingest stores the value as `frame_policy` in `run_variables`. `browser.selenium.bidi: on` enables the `bidi` verbs. `browser ports` lists debug-port owners. A second attach to the same port fails.

### Browser protocols

Pickleball reaches a browser through four backends and picks one per capability: Playwright (when the session is Playwright-attached), WebDriver BiDi, the Chrome DevTools Protocol, then Selenium classic. See [Browser protocols](browser-protocols.md) for the fallback order, `--via`, and what each browser supports.

| Key | Default | Meaning |
|-----|---------|---------|
| `pkb_bidi` | on | Selenium Chrome, Edge, and Firefox sessions ask for `webSocketUrl=true`, so a BiDi socket is open beside CDP. `false`, `off`, `no`, or `0` turns it off. A driver that rejects the capability is retried once without it (one WARN per run). A project `driver.capabilities.webSocketUrl` value wins. |
| `pkb_bidi_endpoint` | unset | An existing BiDi WebSocket (`ws://.../session/<id>`) for `doctor --protocols` and protocol verbs. |
| `pkb_cdp_endpoint` | unset | An existing CDP endpoint (see the Playwright backend). |
| `pkb_protocol` | unset | `playwright` puts Playwright first when the config has no `browser.channel`. `selenium` forces Selenium when a channel is set. A channel of `chrome`, `msedge`, `chromium`, `firefox`, or `webkit` resolves to Playwright and does not fall back. `safari` uses Selenium. |
| `pkb_browser_events` | on | `off` keeps no browser event rows and writes no spill file. See [Browser events](browser-events.md). |
| `pkb_browser_events_kinds` | all | Comma list of event kinds the protocol host keeps. |
| `pkb_browser_events_max_kb` | 8192 | Spill cap per file; the full file rotates once to `.1`. |
| `pkb_browser_events_ring` | 5000 | Rows kept in memory for `--since`. |
| `pkb_proto_log` | on | `.pickleball/logs/proto-YYYY-MM-DD.log`; `off` turns it off. |
| `pkb_proto_host_idle_min` | 240 | The protocol host stops itself after this many idle minutes. |
| `pkb_push_wake` | on | Collaboration waits wake on pushed panel and chat events. `off` restores their fixed poll sleeps. |
| `pkb_fail_on_js_error` | off | `warn` logs, and `fail` fails, the step during which an uncaught page error or unhandled rejection happened (collected over WebDriver BiDi). The message names the error and its stack. |
| `pkb_js_error_ignore` | unset | Regex; page errors whose message matches are allowed under `pkb_fail_on_js_error`. Example: `pkb_js_error_ignore=ResizeObserver loop\|third-party-widget`. |
| `pkb_scenario_events` | off | `on` streams a scenario's own Selenium session console, page-error and network rows into browser-events through Selenium's BiDi modules (`transport=selenium`). See [Browser events](browser-events.md). |
| `pkb_optional_dir` | user cache dir | Where `optional-install` puts axe, Lighthouse and chrome-devtools-mcp. See [Optional installs](optional-installs.md). |
| `pkb_optional_npm` | unset | npm (or `npm-cli.js`) for add-ons with npm dependencies (Lighthouse); Playwright's bundle has none. |

Each key is read as a system property, then `pkb_runvars.<key>`, then the upper-case environment variable, then the environment variable as written. `doctor` prints `protocols=<browser>:<backends>`; `doctor --protocols [--cdp-endpoint=] [--bidi-endpoint=]` prints one `protocol.backend.*` line per backend and one `protocol.capability.*` line per capability.

## Agent coordination

Different consumer agents can run different tests at the same time. The shared files under the consumer `.pickleball` directory are a bulletin board. Run data stays private to each run.

A private `.pickleball/runs/<run-id>/` directory is only for an agent run or a Workbench run (`-Dpkb_run_id`, `-Dpkb_agent_id`, or a Workbench worker). A normal `mvn test` does not open that folder, does not set Chrome or Edge `user-data-dir`, and writes the composite HTML report to `reports/cucumber-report.html`. Diagnostic packs and scratch stay on their 2.1.13 paths for a normal test. The short log and inbox stay where they are.

When the worker or test JVM opens that private run, after `pkb_configpath` is resolved, Pickleball copies the consumer project's configs into `.pickleball/runs/<run-id>/config` and reads only that copy. That copy is a faithful copy of the project configs (real values, valid YAML) so the worker can load them. The project config is unchanged. Redaction for logs and reports stays a separate path and does not rewrite the run copy. The copy uses the same path rule as a normal run. A root `configs/` directory is chosen when `wd-file:` points at it. `session-start`, `discover`, and `confirm` book the run before that JVM exists and do not copy. A `classpath:` path does not resolve in the launcher JVM. Browser yaml and the other files under the resolved `pkb_configpath` are in the copy, including a bundled `CHROME_HEADLESS.yaml` when the project does not already have one. A destination that contains only that bundled file is not a finished copy, so the worker still copies the project configs. The copy is taken once a real project file is present. Starting the same run again does not overwrite it, so an edit made for that run stays. A second run gets its own copy from the project files and does not see the first run's edits. Neither run writes the project configs. `pkb_configpath` in the run profile stays the project path. The run directory is not stored there. A normal `mvn test` does not copy anything and keeps reading the project configs.

An agent or Workbench run that does not pass a run id gets one (`--run-id` or `-Dpkb_run_id`). An agent may pass its own id. An omitted agent id is generated and printed (`--agent` / `--agent-id` or `-Dpkb_agent_id`). Optional group, sequence, who, and why are `-Dpkb_run_group`, `-Dpkb_run_sequence`, `-Dpkb_run_who`, and `-Dpkb_run_why`.

Reports, diagnostic packs, scratch, and a headless Workbench session file for that private run live under `.pickleball/runs/<run-id>/`. Local Chrome and Edge in one run do not share one profile. Each parallel worker gets `browser-profile/<worker>`. Remote drivers are unchanged. Options that already contain `user-data-dir` are left alone. Two runs never write the same output folder or the same live session file.

`pkb_compositeReport=false` suppresses the composite `reports/cucumber-report.html`. `pkb_scenarioReport=false` suppresses the per-scenario workbook HTML that `Log.closeAll` writes. A normal test leaves both unset and still writes both reports. An agent run, and a Workbench run including a person collaborating with an agent, default both to false. Agents keep diagnostic logs. An explicit `true` writes that report again. `pkb_reportingmode=diagnostic` still bypasses automatic HTML, ReportPortal, and XLSX unless one of those HTML run vars is explicitly true.

The run record is `.pickleball/runs/<run-id>/record.json`: run id, agent id, optional group, sequence, who, why, what was learned, start time, stop time, status, and the path to the run data. The short log only points at that record.

The short log is `.pickleball/agent-log`. Read it before a run, after a run, and during a long session, instead of the dense diagnostic log. Each line is a timestamp, agent id, run id, `start` or `stop` or `note`, a one-line purpose, and the path to the run record. Appending does not take a file lock. A dead agent must not block the others. On each write, lines older than 3 days are dropped. Newer lines stay. A dropped run whose directory remains keeps a one-line stub so the index does not vanish. Do not put fix history in the short log.

`inbox/<agent-id>/` is directed mail for that agent. `take` deletes that note. `list` does not. `inbox/any` is not first-taker-wins. A note to every agent is a post, not a consumed inbox file. The inbox is not a second log and not an assignment framework.

The board is three files, not more inbox folders. `.pickleball/presence/<agent-id>.json` is a heartbeat (`agent id`, `lastSeen`, current run ids). An active agent may drop a row older than 15 minutes. That drop removes the row only. `.pickleball/posts/<id>.json` is a hint (`id`, `from`, `to` an agent id or `all`, optional run id, one line, `createdAt`, `updatedAt`, `expiresAt`, optional `ackedBy`). Default expiry is 24 hours from the last update. One hour is appropriate for "I have the window" and is not the default. List does not delete. Ack is optional. `.pickleball/history.log` is an append-only summation (version, files, fix, branch, commit, merge, run id), trimmed from the front above 10 MB. It is not a mailbox. Agents are not daemons. Launcher commands are one-shot: `presence --touch|--list|--sweep`, `post --write|--list|--renew|--sweep`, `history --append|--tail`, `gc-runs [--dry-run]`, `gc-versions`, and `use-version --version=<version>`. `gc-runs --dry-run` prints the paths it would remove and deletes nothing. Any other `gc-runs` flag is rejected and deletes nothing.

`browser-profile/<worker>` under a run is deleted only by `gc-runs`, and only when that run has a stop line, nothing has the profile open, and no agent holds the run. `--dry-run` prints those paths and deletes nothing. Chrome `SingletonLock` and an Edge `lockfile` both mean the profile is open. Not when Cucumber returns, and not on finish alone. Remote drivers and a consumer-supplied `user-data-dir` are not this directory. Dense evidence stays for the run open in Workbench and for the last failed run. The last failed run is the latest stopped run whose status is FAILED, or a STOPPED record with a failure count, a failed-scenario flag, or a non-empty failure summary. `finish` writes FAILED when that run had failures and STOPPED only when it did not. Other stopped payloads may go 24 hours after `stoppedAt`. Always leave `record.json`, the sparse index, and `pkb_run_profile`. Never delete a run with no stop line. `finish` and `stop` only write `stoppedAt` and a stop line. `current.json` is a version pointer (`pickleballVersion`, `complete`, `updatedAt`), not a path to the dependency jar. `export-guidance` of version X writes `v/X`, records `v/X/.last-used`, and points `current.json` at X. It leaves every other `v/<other-version>/` tree on disk. `use-version --version=<version>` does the same pointer move when that tree is already a complete export, and refreshes the root aliases and `open/` from that tree. It does not invent a missing export and does not delete the tree being left. Session, attach, and last-discover stay under `v/<version>/workbench` for the version that owns them. A run records the version it started with. Moving the pointer does not retarget a RUNNING run. `gc-versions` is explicit. It is not run from export, use-version, or finish. It deletes a version tree only when it is not the usable current pointer, last use is at least 3 days ago, no RUNNING run started with it, and its workbench has no live session, attach, or worker. A missing `.last-used` counts as last used now. An unused version tree is expired, not stale. Stale still means an untrusted export.

When a Workbench window is already open, only one agent drives it. Other agents stay headless on their own run ids. Two headless isolates each get their own session file under the run directory. Commands without `--run-id` still target the open window, or the legacy shared CLI session when no window is open. Commands with `--run-id` look only at that run's session file. The window is a view of `.pickleball/runs/`. Its run dropdown and `show-run --run-id` read that same directory. `open-window --run-id` opens the one window on a run the agent already has and does not start a test. `close-window` closes the window and does not delete the run directory, the profile, the session file, or `.pickleball/agent-log`. A dedicated window process exits when its last window closes, so the next `open-window` does not leave that process running with no window. A window opened inside a live session controller stays in that process and does not exit the headless run. Play, step, and stop apply only to the one live run. Another agent may view a different run read-only. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test.

Same launcher as the other session commands, project wrapper only: `short-log`, `note --text=<one line>`, `inbox --write|--list|--take`, `presence`, `post`, `history`, `gc-runs`, `gc-versions`, `use-version --version=<version>`, and `finish --run-id=<id> [--learned=<one line>]`. `discover`, `confirm`, and `isolate` write the start line themselves. `finish` and `stop --run-id=` write the stop line and the learned field when the caller supplies it. They do not delete the run.

## Named profiles

Profiles are publicly configured from classpath-root resources in this order:

```text
profiles.yaml
profiles_local.yaml
```

Matching profile definitions are deep-merged at property level, so the local file can override selected properties from the shared profile definition. `pkb_profile` selects one or more names and composes them left-to-right; later selected profiles win.

```yaml
qa:
  pkb_tags: "<default_profile.pkb_tags> and @qa"
  pkb_environment: QA
  pkb_browser: CHROME_HEADLESS

browser_firefox:
  pkb_browser: firefox
```

```properties
pkb_profile=qa,browser_firefox
```

Profiles do not automatically inherit every optional value from `default_profile`. They may explicitly reference `default_profile` or any named profile through normal `<...>` profile templates.

A profile can also be defined inline:

```properties
pkb_profile_smoke=pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS
pkb_profile=smoke
```

`default_profile` and `run_profile` are reserved profile names.

### JVM RunVar overrides with profiles

JVM system properties that are themselves Pickleball RunVars remain explicit runtime overrides even when a named profile is selected. For example:

```text
-Dpkb_profile=qa -Dpkb_browser=firefox
```

uses the `qa` profile and then overrides its browser with `firefox`. Other RunVars supplied by the profile remain active. This applies to optional RunVars as well as execution-context RunVars because the JVM value was explicitly supplied for this invocation.

When controlled `pkb_runvars` is active, explicit JVM RunVar overrides are also retained, but `pkb_runvars` has the higher precedence for any conflicting key:

```text
pkb_runvars > explicit JVM pkb_* RunVar > inherited execution context
```

Ordinary optional values that exist only in project defaults/property files still do not leak into a controlled run.

### Execution-context inheritance

A selected named profile and a controlled `pkb_runvars` input automatically inherit only missing project wiring RunVars:

```text
pkb_glue
pkb_features
pkb_datapath
pkb_callpath
pkb_componentpath
pkb_configpath
```

Optional choices such as browser, tags, logging/reporting controls, and ReportPortal settings are not inherited merely because they exist in normal configuration.

For the six execution-context keys:

```text
missing      -> inherit the normal project value when available
nonblank     -> use the supplied value
blank/null   -> suppress inheritance; downstream Java fallback may apply
```

Blank is meaningful during inheritance. It suppresses the inherited project value and remains blank in the final canonical run profile so replay preserves the existing subsystem fallback semantics rather than converting fallback behavior into an explicit path.

## Controlled execution with `pkb_runvars`

`pkb_runvars` is the preferred direct input for deterministic automation and AI-controlled reruns.

Compact form:

```bash
mvn test "-Dpkb_runvars=pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS"
```

Expanded form:

```text
pkb_runvars.pkb_tags=@smoke
pkb_runvars.pkb_browser=CHROME_HEADLESS
pkb_runvars.pkb_rp_description=Bob's "QA, phase 2; retry" = green
```

Each expanded member is already one RunVar value and is not reparsed as a nested assignment string. Do not combine compact and expanded `pkb_runvars` forms in one resolved configuration.

A selected YAML/inline profile may itself provide controlled RunVars:

```yaml
agent_direct:
  pkb_runvars: "pkb_tags=<default_profile.pkb_tags> and @agent; pkb_browser=firefox"

agent_direct_map:
  pkb_runvars:
    pkb_tags: "<default_profile.pkb_tags> and @agent-map"
    pkb_browser: chrome
    pkb_rp_description: "Map controlled RunVars, phase 2; ready"
```

When a profile supplies `pkb_runvars`, the remaining profile fields remain available as reference context, while the `pkb_runvars` value supplies the controlled RunVars. Missing execution-context keys are inherited separately.

### Compact assignment grammar

- comma or semicolon separates assignments;
- the first `=` separates key and value;
- a single/double quote is syntactic only when it begins the value;
- quoted values may contain commas/semicolons and escaped matching quotes/backslashes;
- after the closing quote only whitespace and the next separator/end are valid;
- `<...>` template spans are kept intact while assignment boundaries are parsed, including template selectors containing commas;
- template resolution occurs after parsing.

Examples:

```text
pkb_tags="@a, @b"; pkb_browser=chrome
pkb_name=<orders #1,3>, pkb_browser=chrome
pkb_features=, pkb_browser=firefox
```

The last form intentionally suppresses inherited `pkb_features`. The literal word `null` is ordinary text; it is not a compact-syntax null marker.

To pass verb flags without shell quoting, put a JSON object of flags in `@<file>.json` or `--args-file=<file>`. An explicit flag on the command wins. An unknown key fails with `error:` and `fix:`, and the message does not print the value. The shell quoting table stays in [Iframe-heavy apps](iframe-apps.md).

## Sealed execution with `pkb_overriderunvars`

`pkb_overriderunvars` is optional sealed input. It is **opt-in**. When compact input is missing/null/blank **and** there are no expanded `pkb_overriderunvars.*` members, sealed is OFF and current resolution is unchanged.

When sealed is ON for that execution only, Pickleball ignores the normal sources for execution RunVars (JVM `-D pkb_*`, runner properties/defaults, property files, `default_profile`, `pkb_profile`, `pkb_runvars`, execution-context inheritance, and templates). Maven/JVM/env still exist as the process; they must not change the Pickleball RunVar set. This is ignore-sources, not erase-process.

Fail-closed rules:

- missing any of `pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, `pkb_configpath` is an error (blank is an allowed tombstone);
- no templates in the sealed bag (`<default_profile.x>`, named-profile templates, `<config:...>` / `<configs...>`);
- sealed + compact/expanded `pkb_runvars` is an error;
- sealed + a `pkb_profile` selection meant to compose this run is an error;
- compact + expanded `pkb_overriderunvars` together is an error;
- omitted optional RunVars stay absent;
- secrets stay `${protected:...}`;
- Cucumber CLI tag/name/glue projection must not mutate the sealed set;
- `pkb_parallel=auto` is the only allowed derived mutation (stamped as an integer into `pkb_run_profile`).

Reuse the same compact/expanded assignment grammar as `pkb_runvars`. `pkb_overriderunvars` is INPUT. `pkb_run_profile` remains derived OUTPUT only.

Dry-run without starting tests:

```java
PKB_props.ResolvedRunVars preview = PKB_props.resolveRunVars(values);
```

`DiagnosticCli resolve-runvars` and Workbench `hint` print that preview, including per-key provenance (`override`, `runvars`, `profile`, `jvm`, `inherited-context`, `default`, `properties`). The preview is the launcher JVM, not the Discover worker. Do not treat its `pkb_environment` as the environment Discover will use. Trust the `environment` field on `record.json` after Discover, and the `environment=` token on the summary line. A launcher `-Dpkb_runvars` is not the controlled run. Pass `--runvar pkb_environment=DEV` on `discover`, `confirm`, or `isolate`. Agents resolve → inspect → complete map → `pkb_overriderunvars` → compare fingerprint. Sealed input is `pkb_overriderunvars` only.

### Templates and runtime configs

Controlled RunVars can reference profiles:

```text
pkb_configpath=<qa.pkb_configpath>
pkb_tags=<default_profile.pkb_tags>
```

Runtime `<configs...>` mappings are deliberately unavailable while resolving `default_profile`, named profiles, `pkb_runvars`, or `pkb_run_profile`. For example, `pkb_configpath=<configs.otherPath>` is invalid. Run configuration must resolve before Pickleball can load the runtime config mapping.

## Canonical `pkb_run_profile`

After all profile/direct resolution and execution-context inheritance, Pickleball serializes the final RunVars into `pkb_run_profile` in deterministic key order.

```text
pkb_browser=firefox, pkb_configpath=configs, pkb_features=classpath:features, pkb_glue=com.example.pickleball, pkb_tags=@smoke
```

The serializer:

- includes only execution RunVars;
- preserves explicit blank execution-context tombstones after inheritance suppression so replay uses the same historical subsystem fallback behavior;
- can preserve blank values for other RunVars that remain genuinely blank;
- excludes profile/direct controls and diagnostic lineage;
- quotes only when required by compact syntax;
- replaces nonblank sensitive values with `${protected:<pkb-key>}`;
- leaves an intentionally blank sensitive value blank rather than converting it into a protected reference.

Diagnostics retain the sanitized final `runProfile` plus `runProfileFingerprint`. The compatibility diagnostic field `directRunProfile` remains the indicator that controlled direct RunVars were used.

For controlled reruns, copy the retained `runProfile` back through `pkb_runvars`, make only the intended changes, and keep lineage metadata separate. See [AI Run Configuration](ai-run-configuration.md).

### `pkb_run_profile` is read-only input-wise

Do not supply either of these forms:

```text
pkb_run_profile=...
pkb_run_profile.<pkb_var>=...
```

Pickleball rejects them with a configuration error because `pkb_run_profile` is reserved for the final derived RunVar serialization. Use `pkb_runvars` instead:

```java
PKB_props.runVars(Map.of(
        "pkb_tags", "@smoke",
        "pkb_browser", "firefox"
));

String finalProfile = PKB_props.runProfile();
```

`PKB_props.runProfile()` is a getter only. For a complete frozen map, use `PKB_props.overrideRunVars(...)` and preview with `PKB_props.resolveRunVars(map)` without starting tests.

## `pkb_configpath` and the stable `configs` mapping

`pkb_configpath` controls the source loaded beneath the stable `configs` mapping namespace.

```text
pkb_configpath=configs
pkb_configpath=file:configs
pkb_configpath=classpath:environment/qa/configs
pkb_configpath=qa/configs
pkb_configpath=wd-file:configs
pkb_configpath=wd-file:/opt/project/configs
```

Changed in 2.1.15. A path with no prefix is a classpath resource, including a path that contains a slash. `qa/configs` is the resources folder. `wd-file:src/test/resources/qa/configs` is that folder under the working directory. `wd-file:/opt/...` is an absolute directory. `file:/opt/...` is the classpath resource `opt/...`. There is no project-root prefix. A `file:/...` or unprefixed `/...` path that is not on the classpath fails with a hint: use `wd-file:/...` for a filesystem path. A relative miss and a missing `wd-file:` path stay empty.

| Prefix | Meaning |
|---|---|
| (none) | resources folder (classpath) |
| `file:` | resources folder (classpath), same as no prefix |
| `classpath:` | resources folder (classpath) |
| `wd-file:X` | `<working directory>/X` |
| `wd-file:/X` | absolute path `/X` |

Regardless of source location, prefer the source-qualified syntax:

```text
<config:application.baseUrl>
<config:users.admin.name>
```

Legacy references remain valid:

```text
<configs.application.baseUrl>
<configs.users.admin.name>
```

Missing or blank `pkb_configpath` uses the classpath resource root `configs`. Pickleball first completes RunVar/profile resolution and only then reloads the global `configs` mapping from the final path. This preserves a one-way initialization dependency and prevents runtime config data from selecting its own source.

`pkb_features` and `pkb_datapath` use the same prefix table. `pkb_features=features`, `file:features`, and `classpath:features` are the resources folder. `wd-file:features` is passed to Cucumber as `file:features`. The default `pkb_datapath` is `data`. `pkb_callpath` and `pkb_componentpath` keep their previous path rules. The examples below use the resources-folder names `component` and `calls`. When those properties are unset, the defaults remain `src/test/resources/component` and `src/test/resources/calls`. See [Config Files and Resource Mapping](config-files-and-resource-mapping.md).

## Common properties

| Property | Example | Purpose |
|---|---|---|
| `pkb_glue` | `com.example.tests` | Cucumber glue packages |
| `pkb_features` | `classpath:features` | top-level feature location(s) |
| `pkb_componentpath` | `component` | component-scenario location |
| `pkb_callpath` | `calls` | reusable service-call location |
| `pkb_datapath` | `data` | scenario-data / rooted `data:/` lookup on the resources folder |
| `pkb_configpath` | `configs` | source behind the stable `configs` mapping |
| `pkb_tags` | `@smoke and not @slow` | Cucumber tag expression |
| `pkb_name` | `Checkout.*` | scenario-name expression |
| `pkb_example` | `1 2 5 3.4 7-11` | Examples-row filter applied after tags and name. Not a tag |
| `pkb_environment` | `QA` | project environment label. Workbench `discover`, `confirm`, and `isolate` accept repeatable `--runvar key=value` (a missing `pkb_` prefix is added). That value is merged into the planned run vars. A conflict warns and the command-line value wins. The run record `environment` and `browser` fields, and the summary line, come from the merged run vars |
| `pkb_browser` | `chrome` | browser configuration name looked up under the `configs` mapping (`CHROME_HEADLESS` uses the consumer yaml when present, otherwise Pickleball's bundled headless Chrome) |
| `pkb_driver_download_native` | `true`, `false` | `true` or unset tries Selenium Manager first; `false` keeps that launch off the network |
| `pkb_driver_download_proxy` | empty, `false`, or `http://user:pass@host:port` | empty discovers a driver-download proxy; `false` disables the fallback; a URL is tried first and is redacted |
| `pkb_driver_download_ca` | PEM file path | optional corporate CA for the driver downloader only |
| `pkb_profile` | `qa,browser_firefox` | selected named profile(s) |
| `pkb_runvars` | `pkb_tags=@smoke, pkb_browser=chrome` | compact controlled RunVar input |
| `pkb_runvars.<pkb_var>` | `pkb_runvars.pkb_browser=chrome` | expanded controlled RunVar member |
| `pkb_overriderunvars` | complete compact map including the six context keys | optional sealed RunVar input; ignores other RunVar sources for that run |
| `pkb_overriderunvars.<pkb_var>` | `pkb_overriderunvars.pkb_browser=chrome` | expanded sealed RunVar member; do not mix with compact |
| `pkb_run_profile` | generated assignment string | canonical resolved RunVar output; external input rejected |
| `pkb_parallel` | `4`, `auto` | parallel scenario count; `auto` resolves at run start to a conservative JVM estimate and stamps the integer into `pkb_run_profile` |
| `stepRepeatMaxCount` | `100` | run-wide block-until pass limit; element waits do not use it; see [Repeat ceilings](#repeat-ceilings) |
| `stepMaxTime` | `60` | run-wide hard-fail cap for block `until` and element `wait`, in minutes, or a unit such as `90s`; see [Repeat ceilings](#repeat-ceilings) |
| `stepRepeatMaxTime` | deprecated | seconds alias of `stepMaxTime`. A warning is logged. Ignored when `stepMaxTime` is set |
| `pkb_loglevel` | `debug` | console log level |
| `pkb_reportingmode` | `diagnostic` | diagnostic evidence pipeline |
| `pkb_compositeReport` | `true`, `false` | composite `reports/cucumber-report.html`; unset writes it on a normal test and suppresses it on an agent or Workbench run |
| `pkb_scenarioReport` | `true`, `false` | per-scenario workbook HTML; same default as `pkb_compositeReport` |
| `pkb_reportretention` | `all`, `failed`, `none` | automatic evidence/report retention |
| `pkb_diagnostic_output` | `reports/diagnostic-runs` | optional diagnostic output root |
| `pkb_platformlog` | `default`, `default+git`, `none`, etc. | platform/caller log stamps |
| `pkb_gitsnapshot` | `metadata`, `diff`, `none` | diagnostic Git/source provenance |
| `pkb_composite` | `auto`, `on`, `off` | element path. Unset, `auto` (default), and `off` (also `false`, `legacy`, `selenium`) use `SeleniumAdapter` and do not look for an engine. `on` (also `true`, `required`, `composite`) forces the Java engine, logs `pkb_composite=on`, and prints `error:` / `fix:` when that engine cannot start. Direct CDP/BiDi is `AgentProtocolController`. `DriverConstruction.ready` selects `LIBRARY` and does not open a socket |
| `pkb_browser_log` | `off`, `all`, or `selector,cdp` | facade log. Unset is off. Entities: `facade`, `selector`, `selenium`, `cdp`, `bidi`, `playwright`, `fallback`. Property then env `PKB_BROWSER_LOG`. File `browser-YYYY-MM-DD.log`, 1 MiB cap |
| `pkb_browser_log_dir` | directory | browser log directory. Default temp `pickleball-browser-logs`. Env `PKB_BROWSER_LOG_DIR` |
| `pkb_browser_fallback_log` | `warn`, `info`, `off` | one protocol-fallback line per process and reason. Default `warn`. Property then env `PKB_BROWSER_FALLBACK_LOG`. Printed even when `pkb_browser_log` is off |
| `pkb_mcp_transport` | `direct`, `cli` | Workbench MCP hot path. Unset, blank, and any value other than `cli` post the verb and flag map to `POST /v1/verb` (`direct`). `cli` (any case) is the debug shell-out. Property then env `PKB_MCP_TRANSPORT`. A missing live worker on `direct` returns `error:` and `fix:` and does not shell out |
| `pkb_engine_log` | `dispatch` or a comma list | engine log. Unset is off. `dispatch` writes the verb name and `file=yes\|no` to `.pickleball/engine/engine.log`. Flag values are not written. `gate` logs heap samples and, for entity `desktop`, `cache hit`, `cache miss`, or `cache refuse`. Property then env `PKB_ENGINE_LOG` |
| `pkb_ui_page_state` | `off`, `session` | agent page UI state. Default off: placements, guides and the panel come back after a reload only through the protocol preload (CDP or BiDi). `session` also keeps a copy without typed values in the tab's `sessionStorage['pkb-ui']`, so a Selenium-only session restores it the next time the page runtime is installed. Property then env `PKB_UI_PAGE_STATE` |
| `pkb_composite_bin` | path | explicit engine path. Wins over the Java engine. Checked before `COMPOSITE_BIN` |
| `pkb_composite_engine` | unset, `java`, or any other value | property, then `pkb_runvars.pkb_composite_engine`, then env `PKB_COMPOSITE_ENGINE`, then env `pkb_composite_engine`. Unset does nothing. `java` warns once and stays on the Java engine. Any other value fails at engine start. It is not a selector |
| `pkb_engine` | `native`, `cucumber` | env `PKB_ENGINE`, then `engine.properties` `mode=`. `native` is the default. `cucumber` keeps the adapter and, when Store is on, executes the hydrated rows after the existing auto-hydrate. A missing row is `error:` and `fix: store hydrate`. See [Cucumber compatibility](cucumber-compatibility.md) |
| `pkb_warm` | `off` | Warm spine class loader. Unset, blank, `off`, `false`, `no`, and `0` stay off. `on`, `true`, `yes`, and `1` are on. Property then env `PKB_WARM`. CI and `mvn test` stay cold. `spine start` is opt-in and is not part of CI. When it is `on` and `ResourceGate` returns `allow`, the hold JVM keeps one Playwright CDP connection. `refuse` does not open it. `spine stop` closes it. `store run` drops `-Dpkb_warm` and `-Dpkb_warm_packages` unless `.pickleball/spine/warm.json` names a live process. That file is the spine JVM. The store worker warm file stays `.pickleball/store/sandboxes/<name>/worker/warm.txt` |
| `pkb_warm_packages` | ingested Java packages | Comma-separated parent-last packages for `WarmClassLoader`. Property then env `PKB_WARM_PACKAGES`. When unset, and the store is ready, the packages come from Java `step_definitions` source paths. Otherwise every class stays parent-first. `tools.dscode`, `dev.domengine`, and `io.cucumber` are always parent-first |
| `pkb_heap_subprocess_percent` | `70` | Heap percent where a new snapshot context leaves the warm JVM. Below this line the context stays in-process. At or above it, a free `pkb_worker_pool` slot starts a cold subprocess. Property then env `PKB_HEAP_SUBPROCESS_PERCENT`. A value that is not an integer stays the default |
| `pkb_heap_refuse_percent` | `85` | Heap percent where a full pool refuses the context. The warm spine does not take that work in-process. The error is `error: heap gate refused a new context` and `fix: spine sample`. A free pool slot still starts a cold subprocess. Property then env `PKB_HEAP_REFUSE_PERCENT` |
| `pkb_worker_pool` | `1` | How many cold subprocess JVMs may be alive. They use the project wrapper classpath and do not inherit the warm loader. `spine stop` returns the pool to zero. Property then env `PKB_WORKER_POOL` |
| `pkb_selenium_bidi` | off | `on` enables `bidi script`, `bidi intercept`, and `bidi grid-attach`. The same switch is `browser.selenium.bidi: on` in the browser config. Unset stays off. Property then env `PKB_SELENIUM_BIDI`. Scenario events stay the five names gated by `pkb_scenario_events` |
| `pkb_browser_cdn_host` | unset | Host for a `cdn` or `any` browser download. Sets `PLAYWRIGHT_DOWNLOAD_HOST` for that path only. The resolve log does not print the host. Property then env `PKB_BROWSER_CDN_HOST` |
| `pkb_direct_control_provider` | blank, `composite`, or a path | blank is off. `composite` attaches the DOM engine using the provider order in [Direct-control handoff](direct-control-handoff.md). Any other value is a path |
| `pkb_composite_log` | `off`, `all`, or `location,adapter,session,element,fallback,batch,state,position,snapshot` | CLI-first adapter log. Unset is off. `batch` or `all` writes one TRACE line per describe and no per-element line: `TRACE batch mode=<composite, selenium-batch, or selenium-legacy> selector=<by> nodes=<count> durationMs=<n>`. `state` or `all` writes one `TRACE state snapshot miss fallback` line only when a state check has no snapshot. `adapter` or `all` writes the verb, snapshot, scan, wait, nest, and live lines. `element` or `all` writes `INFO element find selenium unattached` when a primary engine has not joined that WebDriver and the find uses Selenium. `position` or `all` writes `TRACE position apply size=<n> position=<p> selection=<s> kept=<k> durationMs=<ms>` when a find keeps a 1-based index, `last`, or `every`. `snapshot` or `all` writes `INFO snapshot` for a page snapshot. A Selenium page fallback always prints one `WARN snapshot fallback selenium reason=no-adapter` or `WARN snapshot fallback selenium reason=adapter-error:...` line per run and reason, even when this log is off. One date-stamped file, 1 MiB cap |
| `pkb_composite_log_dir` | directory | log directory. Default is the temp directory `pickleball-composite-logs` |
| `pkb_premap_box_max_area` | `0.6` or `900px` | Engine flag `--box-max-area`. A ratio of the viewport, or pixels when the value ends in `px`. Default ratio 0.60. Env `DOMENGINE_BOX_CAPS` accepts `area=` |
| `pkb_premap_box_max_width` | `0.98` or `900px` | Engine flag `--box-max-width`. Default ratio 0.98 |
| `pkb_premap_box_max_height` | `0.98` or `900px` | Engine flag `--box-max-height`. Default ratio 0.98 |
| `pkb_premap_one_match_margin` | `0.15` | Engine flag `--one-match-margin`. The top score must lead the second by at least this much or the match is ambiguous |
| `pkb_premap_affinity_weights` | `proximity=0.3,alignment=0.2,role=0.15,text=0.1,section=0.15` | Engine flag `--affinity-weights` and env `DOMENGINE_AFFINITY_WEIGHTS`. Unknown keys are a usage error. Weights are re-normalised to sum to 1 |

`domengine snapshot` takes `--full-page` and `--out-dir DIR`. `--full-page` is the only extra screenshot parameter: `Page.captureScreenshot` is called with `captureBeyondViewport` true. The default captures the layout viewport (`fromSurface` true, format png). `--out-dir` writes `snapshot.json` and `screenshot.png` in that directory. Without `--out-dir` the PNG is inlined as `screenshot.base64`.

## Example row filter

`pkb_example` keeps specific Examples rows from scenarios that `pkb_tags`, `pkb_name`, and, on a component or `listPickles` scan, `pkb_featurename` have already selected. Where those filters select scenarios, `pkb_example` runs after them. It is not a tag and it does not extend Cucumber tag expressions (`and` / `or` / `not`). `xor` is not a Cucumber operator. Workbench rewrites an unescaped `xor` only on the Maven `pkb_tags` string and warns with the escaped form `--tags=@smoke\ xor\ @wip`. It has no `cucumber.filter` alias. `pkb_name` is a search of the Scenario or Scenario Outline title, the same rule Cucumber uses when it discovers scenarios. `Partial name` selects `Partial name example rows`. It still selects every row of that scenario until `pkb_example` runs. The Maven run keeps a scenario that this search selected. A normal Maven run applies the same filter after Cucumber's tag and name filters. It does not start honoring `pkb_featurename` on that engine path.

Absent or blank `pkb_example` adds no filter.

A classpath feature URI is decoded before the lookup, including `%20` for a space. A Windows separator `\` is treated as `/`. A feature that still cannot be read is skipped with `WARNING: pkb_example skipped unreadable feature` and does not abort the run. Other features in that scan are still filtered.

Tokens are separated by whitespace:

- An integer `N` is overall value row `N` of that scenario, counted straight through every Examples table. The header is not a value row. `5` with two tables of 3 value rows is table 2, row 2.
- `A.B` is table `A`, value row `B` inside that table. `3.5` is the fifth value row of the third Examples table.
- `Low-High` is an inclusive range of overall indexes only. `7-11` is overall rows 7 through 11. A range is never a table range.

A list mixes these. `1 2 5 3.4 7-11` keeps overall rows 1, 2, and 5, plus table 3 row 4, plus overall rows 7 through 11. Matching rows are emitted in source order. A row named more than once runs once.

Each selected scenario is filtered on its own. A normal Scenario is one implicit Examples row, so `1` and `1.1` keep it and any other integer drops it. An outline with no matching row contributes nothing. The run does not wrap, borrow a row from another table, or fail only because one outline was shorter than the index. If the filter leaves zero pickles, the run fails the same way an empty tag or name selection fails. Invalid syntax rejects the run. That includes `0`, negatives, leading zeros, `3.`, `.5`, `2.0`, `1.2.3`, `5-3`, `5-`, `-5`, and a range used as a table selector.

`pkb_order` and `pkb_limit` still apply after this filter.

Other existing `pkb_*` RunVars retain their previous behavior unless specifically documented otherwise.

## Conservative `pkb_parallel`

`pkb_parallel` is an explicit positive integer unless the value is `auto`.

`auto` is resolved at run start from JVM-visible resources only (`Runtime.availableProcessors()` and `Runtime.maxMemory()`). No OS-specific native calls. The conservative estimate is:

```text
max(2, min(availableProcessors, floor(maxMemoryMB / 512), 24))
```

Chrome workers are RAM-heavy, so a 32-core / 64GiB box does not blindly pick 32 workers; the hard cap is 24, and heap can cap lower. Tiny heaps resolve to 2. An explicit numeric `pkb_parallel` is never overwritten. Omitting `pkb_parallel` does not enable parallel execution.

The resolved integer is stamped into the final RunVars and `pkb_run_profile`. The workspace file keeps `pkb_parallel=auto`; launch resolves it. Workbench `hint` does not print a recommended `pkb_runvars` string.

## Bundled `CHROME_HEADLESS`

`pkb_browser` names a configuration object under the loaded `configs` mapping. Resolution for `CHROME_HEADLESS`:

1. If the consumer `pkb_configpath` / configs mapping already contains `CHROME_HEADLESS` (or a case-insensitive named browser yaml such as `CHROME_HEADLESS.yaml`), that local override wins, including headed chrome.yaml-style configs.
2. Otherwise Pickleball injects a framework-bundled `CHROME_HEADLESS` resource from `META-INF/pickleball/configs/CHROME_HEADLESS.yaml` inside the Pickleball JAR.

The bundled headless config uses `--headless=new`, a fixed `--window-size=1920,1080`, no `MAXIMIZE`, and `QUIT_LOCAL_DRIVER`. Consumer `CHROME`, `EDGE`, `GRID`, and `SAUCE` yaml files are unchanged. Agents can set `pkb_browser=CHROME_HEADLESS` without copying yaml into the project.

`pkb_browser` is that configuration name, not a path to a browser binary. A normal `mvn test` uses the project value. Workbench keeps that project browser in the run workspace. `CHROME_HEADLESS` is used only when the project names none.

Local Chrome and Edge also set `unhandledPromptBehavior` to `ignore` before `driver.capabilities` is applied. A project value in that map replaces it. The dialog then stays up until the scenario accepts or dismisses it. See `docs/dynamic-steps.md`.

## Local driver download

Local Chrome and Edge start through Selenium. Remote WebDriver is unchanged. Bundled and consumer `CHROME_HEADLESS` configs set `driver.service.port` and `driver.service.verbose` and do not set `driver.service.driverExecutable`. Selenium Manager then resolves the driver. Behind a corporate proxy that child often cannot log in, trust a re-signed certificate, or reach `storage.googleapis.com`.

Pickleball keeps a separate best-effort download for that case. It does not add a proxy block under `configs`, and it does not copy the download proxy onto `http.proxyHost`, `https.proxyHost`, `HTTPS_PROXY`, or `SE_PROXY`. Browser `--proxy-server` and `pkb_rp_http_proxy_*` stay on their own channels.

| Property | Values | Meaning |
|---|---|---|
| `pkb_driver_download_native` | `true` or unset | Try Selenium Manager first |
| `pkb_driver_download_native` | `false` | Do not let Selenium Manager use the network for this launch. Use a cached or fallback executable. `SE_OFFLINE` is set only around that launch |
| `pkb_driver_download_proxy` | empty or unset | Discover a proxy, then try a direct connection |
| `pkb_driver_download_proxy` | `false` | Do not run the fallback. `false` is not a hostname |
| `pkb_driver_download_proxy` | one `http://user:pass@host:port` line | Try this proxy first. Percent-encode a password that contains `@` or `:` |
| `pkb_driver_download_ca` | optional PEM file | Corporate CA used only by the downloader. It is not installed into the JVM trust store |

`pkb_driver_download_proxy` is redacted everywhere a run profile, diagnostic, or log can show it, the same way `pkb_rp_http_proxy_password` is redacted.

Native then fallback is the default. Fallback only, native only, and neither are valid. Neither requires `driver.service.driverExecutable`. If both are disabled and no executable is set, startup fails and the message names those two properties. An executable that is already set skips the download.

Discovery runs once per JVM. The order is the `pkb_driver_download_proxy` value, then `HTTPS_PROXY` and `SE_PROXY`, then the OS system proxy, then the lower-level OS setting, then a direct connection. A connection, login, or certificate failure tries the next proxy. HTTP 403 or a block page after a successful handshake keeps that proxy and tries the next hostname. Direct is the last proxy configuration, not another hostname. Windows may use WinHTTP or a short PowerShell `-UseDefaultCredentials` helper for current-user proxy login. macOS reads `scutil --proxy`. Linux uses the environment, and a GNOME `gsettings` read is optional. A missing tool is skipped.

Chrome metadata comes from `googlechromelabs.github.io/chrome-for-testing` (`LATEST_RELEASE_STABLE`, `LATEST_RELEASE_<major>`, the known-good and milestone JSON files). Chrome zips are tried as `commondatastorage.googleapis.com`, then `storage.googleapis.com`, then `edgedl.me.gvt1.com`. Edge metadata is UTF-16 LE. The version URL is `https://msedgedriver.microsoft.com/LATEST_RELEASE_<major>_WINDOWS` (or `_LINUX` / `_MACOS`), and the zip is `edgedriver_win64.zip`, `edgedriver_mac64.zip`, `edgedriver_mac64_m1.zip`, or `edgedriver_linux64.zip`. Pickleball does not use `chromedriver.storage.googleapis.com`, `msedgedriver.azureedge.net`, or `msedgewebdriverstorage.blob.core.windows.net`.

The installed browser major wins: Windows file version or registry, and `--version` elsewhere. A Selenium Manager failure that already names a versioned zip is retried on the other hosts. `SessionNotCreatedException` text `Current browser version is ...` is parsed and retried once for that major. If the session starts and the driver only logs that it has not been tested with this browser, the test keeps that driver and caches the matching major for the next launch. It does not replace the open executable. With no version at all, only the current Stable driver is used, and only when no driver is already running. The last 8 stable majors are a cache fill for that browser, counted separately for Chrome and Edge. A major already in the cache is skipped. The lock is per browser, platform, and version. The launch never points `driverExecutable` at one of the other seven.

Files go in Selenium Manager's cache: the `SE_CACHE_PATH` system property, otherwise the `SE_CACHE_PATH` environment variable, otherwise `cache-path` in `~/.cache/selenium/se-config.toml`, otherwise `~/.cache/selenium`, under `chromedriver/<platform>/<version>/` or `msedgedriver/<platform>/<version>/`. The launch also sets `driverExecutable` to the unzipped binary. Each transfer is capped at 20 seconds. A blocked transfer, unzip error, quarantine flag, antivirus lock, or certificate failure is a log line. It does not fail the test.

## Workbench browser

Workbench does not replace the project browser with `CHROME_HEADLESS`:

1. If the project names a browser, including a remote farm name (`SAUCE_*`, `GRID_*`, `REMOTE_*`), keep it.
2. If the project names none, use `CHROME_HEADLESS` (consumer yaml if present, else the JAR-bundled config above).
3. Do not pick Sauce or Grid merely because unused yaml files exist in `configs/`.

Isolate selects one scenario. It does not force `pkb_parallel=1`.

`pkb_overriderunvars` is deprecated for Workbench. It still works for a plain `mvn test` that does not set `pkb_workspace`.

## Workbench start timeout

`pkb_workbench_worker_start_timeout` is Workbench only. A normal `mvn test` does not read it. The same value limits the consumer worker start and the `isolate` / `session-start` health wait. The default is 300 seconds. A blank, non-numeric, or non-positive value stays 300 seconds.

Resolution order, first match wins:

1. System property `-Dpkb_workbench_worker_start_timeout=N`
2. Environment variable `PKB_WORKBENCH_WORKER_START_TIMEOUT`
3. System property `-Dpkb_runvars.pkb_workbench_worker_start_timeout=N`
4. Member `pkb_workbench_worker_start_timeout` inside compact `pkb_runvars` (system property or the worker property map)

`isolate` / `session-start` passes the resolved number to the forked controller as `-Dpkb_workbench_worker_start_timeout`, because that process does not inherit launcher `-D` properties. `open-window` still waits 60 seconds. It does not use this setting.

While `isolate` / `session-start` waits, stdout prints a progress line about every 12 seconds:

```text
session-start progress elapsed=12000ms stage=compile
```

The stage is the latest `progress stage=` line appended during this start in the session log or in a worker `*.err.log` under the Workbench `logs` directory (`index`, `index-end`, `compile`, `sync`, `materialize`, `copy-base`, `copy-live`, `digest`, `hash`, `classpath-cache`, `classpath`, `worker-launch`, `jvm`, `worker-spawn`, `jvm-main`, `classpath-scan`, `anchor-parse`, `paused`, `worker-ready`). Bytes already in those logs, and the previous `startup-progress.json`, are not this start. Before any marker the stage is `starting / Maven resolve or feature index`. The controller prints `index`, then `index-end`. A cache hit that still materializes prints `sync`, `materialize`, `copy-base files=<n> bytes=<n> ms=<n>`, `copy-live files=<n> bytes=<n> ms=<n> links=<n> copies=<n>`, `digest files=<n> bytes=<n> ms=<n>`, and `hash jars=<n> reused=<n> hashed=<n> bytes=<n>`. Jar identity is path, size, mtime, and file identity. A jar first seen while its mtime is still within 2 seconds of now is recorded with a content hash, which stays that jar's token. Bytes are read again when a later start is still inside that window, or when inode or ctime changed under the same size and mtime; once it is outside that window the same token is kept and its recorded time is refreshed, and `hashed` is that count. On Windows, fileKey() and ctime are not available, so the key is effectively path, size, and mtime plus the 2 s rule. `maven skipped=unchanged` means the pom hash still matches. Every stage line in that read is kept, including `index-end`. A later start whose classes and classpath are unchanged prints `classpath-cache hashed=false` and does not hash those jars. A start that is healthy before the first interval prints no progress line.

A session-start timeout names the current stage, the elapsed time, per-stage durations, the last 20 lines of the relevant log, and this hint: raise the limit with `-Dpkb_workbench_worker_start_timeout=N`, `PKB_WORKBENCH_WORKER_START_TIMEOUT`, or a `pkb_runvars` member `pkb_workbench_worker_start_timeout`. Stage enter times are written to `.pickleball/runs/<run-id>/session/startup-progress.json`. `copy-base`, `copy-live`, and `digest` print `files=`, `bytes=`, and `ms=` between `materialize` and `hash`. `copy-live` hardlinks each file in `base/classes` into `live/classes` on the same thread pool as the copy path, and copies that file when the link fails. That line also prints `links=<n> copies=<n>`. A provider that refuses links copies the rest of that tree without trying another link. Any other link error copies that file only and later files still try a link. After `digest`, the controller writes `progress stage=worker-spawn` on the worker stderr log immediately after the consumer process starts. The worker writes `progress stage=jvm-main` as the first line of main, then `progress stage=classpath-scan` before classpath and glue initialization. The first successful CLI `GET /health` writes `progress stage=worker-ready` on the session log. `paused` still also records `worker-ready` on the worker log. `base/classes` remains the owned snapshot. Do not edit a linked file under `live/classes`; writes belong in `generated-classes`. The class-file fingerprint is the relative path, size, and mtime. It does not read class bytes. Jars still use the racy-window content hash. A later isolate of the same run id moves `session.log` and `startup-progress.json` to `runs/<id>/session/attempts/<n>/` before it truncates them. `n` uses the same next-attempt count as a discover diagnostic archive, in this session directory. `isolate` and `session-start` with a new `--run-id` and no workspace file seed that run from the project, print `seeded run-id=<id>`, and then start the session. A seed that fails still prints `error: no workspace for run id <id>` and `fix: Run workspace-seed or discover for that run id.` and does not start the controller. If the process still exits early, the parent prints the last error line from the new session log as `error:` and `fix:`. `validate` rejects a flag it does not use, including `--quiet`, with `error:` and `fix:`. `discover` and `confirm` still accept `--quiet`. A worker startup timeout still names the elapsed time, the stage, and the last worker output.

The warm JVM used by a classpath-cache hit stays up across separate CLI commands. Each command runs in a fresh class loader. Consumer `@Before` and `@After` hooks, including a `Scenario` argument, run on the same glue instance as the steps, and `@DataTableType` is registered before the first step. `stop`, a session-start timeout, and a session that exits before it is healthy kill that JVM and its process tree. Deleting `warm-jvm.v1` makes the JVM exit on its own. `stop` still kills the pid recorded in `warm-jvm.pid` when the handle file is already gone. A one-shot CLI process is not an owner. Owner death applies only to a long-lived owner: the Workbench UI, the MCP host, or the session process. It also exits when it has been idle. Idle does not fire during a run. `pkb_workbench_warm_idle_timeout` uses the same four places as the worker start timeout: system property, `PKB_WORKBENCH_WARM_IDLE_TIMEOUT`, dotted `pkb_runvars.pkb_workbench_warm_idle_timeout`, and a compact `pkb_runvars` member. The default is 900 seconds. Blank, non-numeric, or non-positive stays 900. The number is read when the warm JVM starts. `status` stays `idle` until a `BEFORE_STEP` hook is open or a session command is in flight. `/health` reports `running` only for that in-flight command.

## Repeat ceilings

`pkb_stepMaxTime` (default 60 minutes) is the hard-fail time cap for every block `until`, every inline `until`, and every element `wait`. `pkb_stepRepeatMaxCount` (default 100) is the repeat-count cap for a block `until`, an inline `until`, and `RETRY:`. An element `wait` does not use the count. They are run-wide run vars, read once when the step runtime loads. Resolved names are `pkb_stepmaxtime` and `pkb_steprepeatmaxcount`. A stored step row may set `max_iterations` and `timeout_seconds`. Hydrate does not fill those two columns from the step text and does not clear a value already stored. A null column keeps the run-wide default. When the row's cap is the one that fires, the message names it: `Until loop exhausted max_iterations N` or `Step exceeded timeout_seconds (Ns)`. When the run-wide cap fires, the message still names `pkb_stepRepeatMaxCount` or `pkb_stepMaxTime`. Workbench `--step-timeout` and `--run-timeout` are not these settings. They apply only to a live Workbench command and record `TIMEOUT`. See [Pickleball Workbench](pickleball-workbench.md).

A bare `pkb_stepMaxTime` integer is minutes. A unit is accepted, such as `90s` or `2 minutes`. The failure names the setting, for example `Step exceeded pkb_stepMaxTime (5m)`.

`pkb_stepRepeatMaxTime` still works as a deprecated alias. A bare integer is seconds. Using it logs a warning: `pkb_stepRepeatMaxTime is deprecated. Use pkb_stepMaxTime (minutes, or a unit such as 90s).` If both are set, `pkb_stepMaxTime` wins and the warning is still logged. When neither is set, the cap is 60 minutes.

A positive count N allows exactly N passes of a block `until` and of an inline `until`. The check before the next pass sees how many passes have already finished. An inline `until` loops while the condition is true. A falsy condition does not run the phrases that follow it on that line.

```gherkin
* , until the Step Repetition is 3:
  : * , save "tick" as "passes"
* , until the Step Repetition is greater than 3:
  : * , save "tick" as "passes"
```

`is 3` runs the child steps 2 times. It stops at the start of run 3. `is greater than 3` runs them 3 times. It stops when the check reads 4.

The run-var limit always hard-fails; a longer Gherkin time can't extend it.

A negative count is unlimited. Zero is already at the limit, so the loop makes no pass and fails with `pkb_stepRepeatMaxCount`.

A negative duration is unlimited. Zero is not unlimited. After the step's start time is set, the limit is exceeded as soon as elapsed time is greater than zero. The failure names `pkb_stepMaxTime`.

An element `wait` can also stop early without failing. `, or 2 minutes` on that wait is a soft limit, in either order. See [Dynamic Steps](dynamic-steps.md). That soft limit does not raise `pkb_stepMaxTime`.

## Cucumber aliases

Pickleball synchronizes its main selection aliases with Cucumber properties, including:

- `pkb_glue` ↔ Cucumber glue;
- `pkb_features` ↔ Cucumber feature locations;
- `pkb_tags` ↔ Cucumber tag filter;
- `pkb_name` ↔ Cucumber name filter.

`pkb_example` is not one of those aliases. Put it in `pkb_runvars` (or pass `--example` to Discover, Confirm, and isolate). It is not a Cucumber CLI selector.

Normal command-line Cucumber projection remains supported. When controlled direct RunVars are active, projected Cucumber CLI selection values do not mutate the controlled RunVar set; put intended values in `pkb_runvars`.

## ReportPortal aliases and protected values

Native `rp.*` properties map generically to `pkb_rp_*` aliases. Profiles and controlled RunVars use the Pickleball aliases; the ReportPortal bridge receives resolved native properties after alias synchronization.

Keep credentials in secure JVM/environment/property sources. Sensitive serialized values become protected references rather than plaintext. Do not commit actual secrets to profiles, feature files, diagnostic lineage, or AI instructions.

## Diagnostic configuration

Diagnostic mode is enabled with:

```properties
pkb_reportingmode=diagnostic
```

Evidence/logging controls such as `pkb_reportingmode`, `pkb_reportretention`, `pkb_diagnostic_output`, `pkb_platformlog`, `pkb_gitsnapshot`, and `pkb_loglevel` are execution RunVars, even though they primarily affect evidence. If an agent intentionally changes one during a controlled rerun, its canonical name belongs in `pkb_changed_variables`.

See [Diagnostic Reporting](diagnostic-reporting.md) and [Diagnostic Lineage Metadata](diagnostic-lineage-metadata.md).

## Embedded store

`install` and `server` are unknown commands. Opening or using the store starts no process, opens no socket, and downloads nothing. The only native code is inside `sqlite-jdbc`. A `noexec` temporary directory fails that native load. Point `-Dorg.sqlite.tmpdir` at an executable directory. Settings `pkb_store_bin`, `pkb_store_auto_install`, `pkb_store_port`, `pkb_store_server_start_ms`, `pkb_store_server_idle_minutes`, `pkb_store_server_log_max_bytes`, and `pkb_store_tool_cache` are not read.
## Store

The working copy is an embedded SQLite database and is on unless you turn it off. The CLI first argument is `store`. Precedence is an explicit `pkb_store` system property, then `PKB_STORE`, then `enabled=` or `pkb_store=` in a project-root `store.properties` when that key is present. Otherwise the source is `default` and the store is on. Accepted values are `true|false|yes|no|on|off|1|0`. Anything else fails at first use with `IllegalArgumentException` naming the key. `false`, `no`, `off`, and `0` are the opt-out. `StoreConfig.source` reports `property`, `env`, `file`, or `default`. `store status` prints `enabled=<bool> source=<source>`; `--json` adds `enabled` and `enabledSource`. With it off, a run does not open `.pickleball/store/main.db` and feature loading stays on the files. An explicit `store hydrate` still opens the store. The cache key stays the file size and modification time. The store is gitignored. Presence is `.pickleball/store/main.db`. Run history is `.pickleball/store/runstate.db`. Opening the store starts no process and downloads nothing. `store hydrate` copies features, configs, calls, components, resource maps, and templates. A hydrate that changes rows increments `hydrate_generation` and stores `hydrate_label` as `<git sha>` or `<git sha> dirty`. A second hydrate that changes nothing leaves that generation. `store status` prints `store=embedded` and `hydrate generation <n> <label>`. A `.feature` file also stores scenarios, steps, tags, docstrings, and data tables. The original bytes remain on the file row and are what execution parses. A second hydrate skips a file whose bytes still hash the same. `--path` limits the copy. `--force` rewrites database rows from the files. `--dry-run` prints the counts and does not create a missing database. `store status` also reports the schema, file count, scenario count, Git provenance, and `binaryRefs`. The store driver is `org.xerial:sqlite-jdbc:3.47.2.0`, a published runtime dependency (Maven POM scope `runtime`). It is not shaded into the consumer shadow JAR. When the store is enabled (including the default) and that driver is missing, `store status`, `store hydrate`, `store extract`, and the auto-hydrate hook fail and do not fall back to the files. The message says the store is on by default and names `org.xerial:sqlite-jdbc`. With the store off, a run still does not look for the driver. A missing native library can be pointed at an executable directory with `-Dorg.sqlite.tmpdir`. `sqlite-jdbc` is also the driver for `pickleball store migrate` and the diagnostic-report index. Gradle `store*` tasks put that driver on their classpath. Gradle `storeHydrate` only forwards to `store hydrate`.

When Store is on, feature loading reads the stored rows instead of the file. The JUnit feature parser a consumer `mvn test` uses and the CLI feature supplier both do this. The first load of a run hydrates when the database is not already current. A path that is not in the database is `error:` and `fix: store hydrate`. The native engine parses those rows by default. `pkb_engine=cucumber` gives the same rows to the Cucumber adapter. A scenario with `enabled=0` or `@pkb-ignore` is not passed to either engine. Gherkin phrases and step behavior do not change. A database edit is what the scenario executes on the next run, even when the file was not touched and its modification time did not change. Turn the flag off to execute the file again. That off run does not create a database. `FeaturePathLoadCache` is the loader only for that off path.

Entities live in `store-entities/<Type>/<id>.entity`. The additive reference is `<entity:Type#id.field>`. It is resolved only when Store is on. Secret fields hold `<password:...>` or `~MASK;` references, never cleartext. `store entity` lists, gets, puts, removes, and exports those files. See [Store entities](store-entities.md).

`store extract` writes database edits back to the files. `hydrate-out` is the same command. `--path` limits the stored paths, and a Windows `\` is stored as `/`. A missing database is not created. Gradle `storeExtract` only forwards to this command. Workbench and MCP forward the same argument list they forward for hydrate.

Extract does not rebuild a file from scenario rows. It writes the stored file bytes, or it writes nothing. Those bytes are the original file until something edits the database row. Comments, tags, docstrings, data tables, ordering, whitespace, and non-UTF-8 bytes therefore survive a round trip. A database edit is that stored copy with the edited flag set. The next extract copies those bytes exactly and then clears the flag.

If only the file changed, extract leaves it. If only the database changed, extract writes the stored bytes. If both changed since the last sync, extract prints `conflict: <path>` on standard error, leaves the file, and exits 1. `--force` is required to overwrite the file from the stored bytes. A deleted file is not recreated unless that row was edited or `--force` is set. `store entity export` is this same write, limited to `store-entities/`.

`store history` stores run summaries in the same database. It does not change `reports/diagnostic-runs` or `pkb_reportretention`. `store history record --from=<run-index.json>` copies the outcome, start time, and passed/failed counts out of an existing diagnostic index and leaves that file alone. `store history retain` and `store retain` drop runs older than `--days` (default 14, `pkb_store_retain_days`; `pkb_store_history_days` still applies when that property is unset) and then drop the oldest runs until the note, scenario, and message bytes fit `--max-bytes` (default 2147483648, `pkb_store_retain_bytes`; `pkb_store_history_max_bytes` still applies when that property is unset). These defaults are distinct from `pkb_store_changes_days`. Deleting a run cascades snapshot, find-index, and attribute rows. A `heads` row that a retained run's `head_id` still names stays. Deltas past the window of 100 are copied to `delta_archive` in `runstate.db` and removed from the source `deltas` table. `store head rebuild --from-archive=<seq>` replays one archived delta and refuses a seq that is still in the live window. There is no background deleter. A missing database is not created by list or retain. Notes and messages may hold `<password:...>` or `~MASK;` references, not cleartext secrets.

The cross-cutting file is separate from the project store. On Windows it is `%USERPROFILE%\.pickleball\spine\cross.db`. Elsewhere it is `~/.pickleball/spine/cross.db`. `pkb_cross_store`, then `PKB_CROSS_STORE`, overrides that path. It is created on the first `cross` command or journal write. Opening pickleball does not create it, and it is not written under the project `.pickleball` directory. `cross note add --text=<text>` stores the text. A body that matches the secret assignment check is rejected. `cross note list` prints the notes. `cross journal list` prints the verb and the args hash. `cross journal retain --days=<n>` drops journal rows whose time is not after now minus that many days. `cross link add --commit=<sha> --head=<id> --run=<id>` stores those three ids with the project hash. `pkb_journal` defaults on (`PKB_JOURNAL` is the environment name). `off`, `false`, `0`, and `no` write no journal row and do not create the file for the journal alone. Any other non-blank value leaves the journal on. A failed journal write prints `error:` once and the verb still returns its own status. `cross_schema` is 1. The raw project path and the raw arguments are not stored.

`store feature put <relative-path> --text=<gherkin>` or `--file=<path>` writes that feature into the database only. It sets `db_edited`, leaves `last_synced_hash`, and replaces the stored scenarios, steps, tags, docstrings, and data tables in the same transaction. The file stays as it was until `store extract`. Gherkin that does not parse is rejected and the previous rows stay. Use `--file` for more than one line. `store scenario put` and `store step put` are rejected, because those rows are a projection of the feature text. `store entity put` uses the same write, so the entity file row and the entity table stay together. Gradle `storeFeature` only forwards to this command. Workbench and MCP forward the same argument list.

If `content_hash` is not `last_synced_hash` and `db_edited` is still 0, hydrate prints `untracked DB edit on <path>`, prints `conflict: <path>`, and does not overwrite that row. `--force` replaces it from the file. A row with `db_edited` set, whose file still matches `last_synced_hash`, is left in the database. Hydrate does not put the older file back.

`store files list`, `store files show <path>`, `store features list`, `store features show <path>`, `store scenarios list` (`--feature=`), `store steps list` (`--feature=`, `--scenario=` as an ordinal or a name), and `store conflicts` read the database. They do not create one. `--json` prints one JSON object. Text mode prints labels such as `edited=yes` and `scenarios=2`. `store conflicts` prints `untracked DB edit on <path>` or `conflict: <path>` and exits 1 when either is present. Gradle `storeQuery` forwards those arguments (`-PstoreArgs=files,list`, `-PstoreJson`). Workbench and MCP forward the same list.

Discovery stays on the fixed directories unless `store.properties` sets `include=` to one or more globs (`features/**`, repeat the key or separate with commas). `exclude=` adds globs. It does not remove the defaults `**/.git/**`, `**/target/**`, `**/build/**`, `**/.pickleball/**`, or the common binary extensions (`png`, `jpg`, `jar`, `class`, `zip`, and the rest of that list). `max-file-size=` defaults to 1048576 (also `K`, `M`, or `MiB`). `binary-policy=` is `include` (default) or `skip`. `hash-only` is not a policy. `large-file-bytes=` defaults to 262144 (also `pkb_store_large_file_bytes`; `0` means every binary). A binary is an extension on that list, a null byte, or a control-byte ratio above 0.30 in a sample of at least 32 bytes. Bytes above 127 are not treated as binary, so UTF-8 stays. A two-byte file is not skipped for that ratio. `include` stores a large binary whose bytes equal the Git blob at HEAD as a reference: empty `raw_content`, `binary_ref=1`, `location` set to the path, `content_hash` as the sha256 of the real bytes, `git_blob`, `git_commit`, `byte_size`, and `source_type=binary-ref`. A large binary that is dirty or untracked is stored with its bytes (`binary_ref=0`, `location=db`, `git_commit` empty) when it is within `max-file-size`. A smaller binary is stored the same way. `max-file-size` does not apply to a reference. Reading a reference checks the file at `location` and, when the hash differs or the file is missing, reads `git cat-file blob`. If both fail, the command prints `error: large file <path> unavailable (hash <h>, commit <c>)` and a `fix:` line. A store write that changes a referenced file copies the new bytes into that branch only. Extract never writes an unchanged binary-ref row back. It checks the file on disk still matches `content_hash` and reports `binary_ref_changed <path>` when it does not. The unset default is `include`. An explicit `skip` still drops binaries, so those discovery counts stay the same only when `binary-policy=skip` is set. The same keys as system properties are `pkb_store_include`, `pkb_store_exclude`, `pkb_store_max_file_size`, `pkb_store_binary_policy`, and `pkb_store_large_file_bytes`, and those win over the file. `store status` prints `skipped` and up to five `sample <path> <reason>` lines, plus `refs=<n> owned-large=<n>`, and the same fields in `--json`, and it does not create a database. Hydrate prints `ignored=` for files discovery did not copy. `--path` still applies the size and binary limits. `--path --force` is the only bypass. A hydrate `--force` without `--path` still ignores files an explicit `skip` policy rejects.

`store run` is the fast edit-run path. It starts or reuses a warm worker JVM (`.pickleball/store/sandboxes/<name>/worker/warm.txt`, not the spine process at `.pickleball/spine/warm.json`) and executes with `pkb_store=true` and the same `pickleball-test-engine` a normal run uses, so scenario bytes come from the database. It prints one line per scenario (`scenario PASSED|FAILED <name>`) and one line per pickle step (`step PASSED|FAILED <text>`). `--tags`, `--name`, and `--glue` become `pkb_tags`, `pkb_name`, and `pkb_glue`. Java glue is fingerprinted by path, size, and modification time under `src/main/java`, `src/test/java`, and the Maven and Gradle class directories. A change runs one `mvnw -q test-compile` or `gradlew testClasses --no-daemon`, then restarts that JVM. The worker classpath is `pkb_store_run_classpath` or `.pickleball/store/sandboxes/<name>/worker/classpath.cache` (`main` uses `sandboxes/main/worker/`). A DB-edited `.java` row does not run the wrapper compile. `glue put` of a `.java` file reports `glue compile pending`, and the next `store run` or `store glue compile` compiles it with `javax.tools` into `.pickleball/store/sandboxes/<name>/classes/`, which is first on the worker classpath. Deleting a `.java` glue file while Store is on is refused; the message says to use a Git worktree. No JDK fails with `glue compile needs a JDK`. JS and TS `defineStep` calls execute through `NodeStepHost`. There is no Node install step. `--dry-run` prints `fingerprint`, `compile`, and `restart` and writes nothing. The CLI rewrites `classpath.cache` when the cached text or the sandbox classes directory changes, and it restarts the worker so the new classes are loaded. Gradle `storeRun` only forwards to this command (`-PstoreTags`, `-PstoreName`, `-PstoreGlue`, `-PstoreDryRun`, `-PstoreSandbox`). It sets `pkb_store_run_classpath` and does not write `classpath.cache`. Workbench and MCP forward the same argument list. Full Gradle or Maven stays the CI build. `scripts/time_store_run.py --project <dir>` times one cold wrapper against two warm runs and requires the same exit code and the same scenario and step lines. `--tags`, `--name`, `--glue`, and repeated `--define KEY=VALUE` are passed to both sides. The second warm run must print `warm2Reused=true`. `--self-check` prints that contract and does not launch a suite. On 2026-11-11 a Linux x86_64 sandbox (Temurin 21.0.13) measured a one-scenario fixture: coldMs=3962 coldExit=0, warm1Ms=4479 warm1Exit=0 warm1Reused=false, warm2Ms=1368 warm2Exit=0 warm2Reused=true.

Store logging is off unless `pkb_store_log` is `true`, `all`, or a comma-separated capability list (`status`, `hydrate`, `extract`, `history`, `entity`, `feature`, `content`, `query`, `discover`, `run`, `parse`, `execute`, `git`, `binary`, `refs`, `track`, `sql`, `sandbox`, `merge`, `changes`, `glue`, `migrate`). `pkb_store_log_entity` limits lines to those entities. Lines start with an ISO-8601 timestamp. `pkb_store_log_max_bytes` caps `.pickleball/store/store.log` (default 1048576, minimum 64). Hydrate records Git provenance through the `git` CLI (5 second timeout): `git_commit`, `git_branch`, `git_remote` (userinfo stripped), `github_repo` (`owner/name` when the remote host is `github.com`), and `git_dirty`. Each file row stores `git_blob` (Git blob SHA-1). `store status` and `--json` show `git.commit`, `git.branch`, `git.remote`, `git.githubRepo`, `git.dirty`, and `git.changed` (rows whose live blob differs from `git ls-files -s`). No `git` binary, or not a repository, leaves those fields empty, prints one `store git unavailable: <reason>` warning, and hydrate still succeeds. Gradle `storeStatus`, `storeHydrate`, `storeExtract`, `storeFeature`, `storeQuery`, `storeHistory`, `storeRun`, `storeRunClasspath`, `storeEdit`, `storeSandbox`, and `storeMigrate` only forward to the CLI. Workbench `StoreCliForwarder` and the MCP tool `store` forward the same argument list. None of them implement Store themselves.

The database is not encrypted. A secret field must be a `<...>` reference or `~MASK;`, never cleartext. The built-in names are `password`, `passwd`, `secret`, `token`, `api_key`, `api-key`, `apikey`, `credential`, `private_key`, and `private-key`. `secret-fields=` in `store.properties` adds names (repeat the key or separate them with commas). `pkb_store_secret_fields` wins over that file. A value of 32 or more characters with no spaces on any other field prints `warn: high-entropy value on <field>` and is stored. The warning does not include the value. Rejection stays on the name list.

There is no sql-server. `pickleball store sandbox create <name>` (`--owner`, `--ttl`, default 24h, `--mode=cow|copy`) writes `.pickleball/store/sandboxes/<name>/source.db`. The default is `cow`: `schema_meta` `sandbox_mode=cow` and `_pkb_branch` key `parent` name the parent (`main` when created from main). The child does not copy parent file bytes. An edit is a delta in the child. A delete is a tombstone. `--mode=copy` is an independent database with no parent. `--from` other than `main` is refused for copy. A cow sandbox accepts `main` or an existing sandbox. Names match `[a-z0-9][a-z0-9-]{0,47}`. Copy mode records `_pkb_branch` (name, created time, Git commit, dirty flag, owner, ttl, schema) and `_pkb_base` (the hash of each file and entity at creation). It does not point at `main.db` or any other sandbox. `store sandbox promote <name> --to=parent|top --logs=clear|archive` requires `--logs`. `--to=parent` writes one expensive delta per changed head row on the parent. `--to=top` walks until `main`. Promote onto `main` syncs those files through extract and skips a both-sides conflict. `--logs=clear` deletes that sandbox's `run_logs` rows. `--logs=archive` prefixes `runs.note` with `archived`. `sandbox list` (`--json`) reads those sandbox files. There is no central registry. `sandbox use <name>` prints export lines for sh, PowerShell, and cmd and does not persist a selection. `sandbox diff <name>` (`--table=files|entities`, `--json`) compares that file with its own `_pkb_base` and prints `insert`, `update`, or `delete` plus hashes. `sandbox extract <name> --to=<dir>` writes owned bytes; writing the project root needs `--into-project`. An unchanged large-file reference is left in place and reported as `binary_ref_changed` when the file hash no longer matches. `sandbox extract` checks every stored path before it writes, and a failed write leaves no partial file and undoes the files and directories that call wrote. A path that is absolute or contains `..` is refused and the destination is left unchanged. `store sandbox drop <name>` deletes the sandbox directory. `--force` is required when this JVM has borrowed it or another process holds the OS lock at `sandboxes/<name>/lease/<pid>.lock`. A file that is still held after the 15 second release wait is marked `pending-delete` and a later `prune` retries it. `sandbox prune` (`--older-than`, `--owner`, `--dry-run`, `--vacuum`, `--with-history`) drops expired sandboxes. A sandbox another process has locked is skipped (`skipped <name> in use`). `--with-history` also deletes that sandbox's rows in `runstate.db`. `--vacuum`, and a `main.db` whose free pages exceed a quarter of the file, runs `VACUUM` on `main.db`. A command binds with `--sandbox`, then `pkb_store_sandbox`, then `PKB_STORE_SANDBOX`, else `main`. One sandbox per JVM. An unknown name fails with `fix: store sandbox create <name>`. Edits write the bound copy. Run-state rows stay in `runstate.db` and record the sandbox name (`sandbox='alpha'` for `store run --sandbox=alpha`, and those rows are not in the sandbox file). A transaction conflict retries 3 times (50–200 ms) and then fails with `error: concurrent update on <table>`. It does not fall back to the files. `sandbox merge <name>` (`--policy=fail|branch-wins|main-wins`, default `fail`, `--dry-run`, `--drop`, `--json`) compares `_pkb_base` with `main` and the sandbox by hash on `files` and `entities`. A key the sandbox did not change is skipped. A key only the sandbox changed is applied. The same change on both sides is `converged`. Anything else is a conflict. `fail` writes nothing to `main`, exits 1, and saves `sandboxes/<name>/merge-<time>.json` plus a text table of hashes and sizes. `branch-wins` takes the sandbox row. `main-wins` keeps `main`. One transaction rebuilds derived feature rows and refreshes step definitions for merged `.java`, `.js`, and `.ts` paths, then rewrites that sandbox's `_pkb_base`. A merged file keeps `main`'s `last_synced_hash` and sets `db_edited=1`. An owned large binary lands as owned bytes. `--dry-run` writes nothing. `--drop` drops the sandbox after success. `store changes` (`--sandbox`, `--since`, `--table`, `--limit`, `--json`) lists `_pkb_changes` without raw content. `pkb_store_changes_days` (default 14) is how long `store retain` and `sandbox prune` keep those rows. Gradle `storeSandbox` forwards `--policy` and `--drop` (`-PstorePolicy`, `-PstoreDrop`). Gradle `storeQuery` forwards `--since`, `--table`, and `--limit` (`-PstoreSince`, `-PstoreTable`, `-PstoreLimit`). The Workbench sandbox picker and MCP `store` forward the same arguments. `pkb_store_log=merge,changes` logs those commands. Paths are stored with `/` on Windows and Linux. `store run` uses `mvnw.cmd` or `gradlew.bat` on Windows and quotes a classpath that contains a space. The warm worker listens on loopback only. There is no file watcher. A large feature tree is copied by SHA-256, so an unchanged file is not parsed again.

## Agent surface popup

| Key | Default | Meaning |
|---|---|---|
| `pkb_agent_popup` | `auto` | `off` keeps today's page behaviour. `auto` uses the popup for a desktop session and the page when a browser page is open. `always` also mirrors chat, and sends handle-less HTML and overlays, to the popup during a browser session. `false`/`no`/`0` mean `off`. `true`/`on`/`1` mean `always`. |
| `pkb_agent_popup_browser` | unset | First browser probe. Then `PKB_AGENT_POPUP_BROWSER`, then `DOMENGINE_CHROME`, then the per-OS install list. |
| `pkb_agent_popup_headless` | false | Adds `--headless=new` for tests and CI. |
| `pkb_agent_popup_no_sandbox` | false | Adds `--no-sandbox` from the first launch. Unset, the host adds it only after a launch that exits with a sandbox error. |
| `pkb_agent_popup_on_top` | true | Tries the OS always-on-top call. `false` skips it. |
| `pkb_agent_popup_size` | `420x640` | `width,height` or `widthxheight`. |
| `pkb_agent_popup_position` | `right` | `right`, `left`, or `<x>,<y>` in screen pixels. |
| `pkb_agent_popup_idle_minutes` | 30 | Minutes with no new ledger row before the host exits. A decimal is allowed. |
| `pkb_agent_popup_log` | off | `true`, `all`, or `lifecycle,browser,ontop,bridge,route,highlight,ledger`. |
| `pkb_agent_popup_log_entity` | all | Limits log lines to a run id or handle. |
| `pkb_agent_popup_log_max_bytes` | 1048576 | Cap for `popup.log`, `host.out`, and `browser.out`. Minimum 64. |

Env names are the same keys in upper case (`PKB_AGENT_POPUP`, `PKB_AGENT_POPUP_HEADLESS`, and the rest). A system property wins over the environment variable.

