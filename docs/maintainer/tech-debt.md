# Pickleball Tech Debt

## CI hosts

Push and pull-request CI (`.github/workflows/agent-contract.yml`) runs jobs `windows`, `macos`, `dom-engine-live` (Linux, live Chrome), and `gate`, plus `.github/workflows/sqlite-index.yml`. The repository is public, so the `macos` job costs no Actions minutes. `macos` runs only targeted tests: Store server start (also under a path with spaces), the Store sandbox, run-state, and isolation suites, `CucumberBoundaryTest`, the agent-surface routing tests, the live agent-surface popup, `MacAccessibilityScriptTest` (osacompile), and the desktop live snapshot. It is not a full macOS regression. sqlite-jdbc parity tests still do not run on a macOS runner; that part stays tech debt. Do not treat a green Windows job as a macOS result.

The popup defects CI found in the gap closure are fixed (see the 3.0.0 release notes): Linux Chrome for Testing could not start its sandbox and rendered nothing, Windows close returned while the host JVM still held `host.out`, and the macOS AX AppleScript did not compile. The live popup step is required on Windows, macOS, and Linux, and the macOS desktop live snapshot must run.

Items deferred from consumer retests and earlier roadmaps. Each entry has a short description, origin report, and rough value/cost. Speed-related items (validate dry-run #4, isolate start #15, index/compile overlap 3-overlap, Maven daemon/persistent resolve, parallel stages, seed overlap) have been moved to the 2.1.18 speed roadmap and are no longer tech debt for 2.1.18. See `docs/maintainer/2.1.18-speed-roadmap.md`.

## From 2.1.18 Report 5 (consumer retest of 111dea5)

| # | Area | Description | Origin | Value / Cost |
|---|------|-------------|--------|--------------|
| 5 | Lint warnings | Lint warnings for silent-logic patterns: ELSE: with no IF: chain, and matches with a literal-looking pattern. | Report 5 | Medium value (catch silent bugs); low-medium cost (pattern matchers). |
| 6 | Discover against another version | Discover against another Pickleball version without editing the build file. | Report 5 | Low-medium value (version matrix testing); medium cost (version resolution, classpath). |
| 7 | --unset-runvar | --unset-runvar=<key> for a deliberate blank. | Report 5 | Low value (edge case); low cost (flag + resolution). |
| 8 | Values.softFail in hooks | Allow Values.softFail in @Before / @After hooks. Today it throws there; this is documented. | Report 5 | Low value (documented limitation); medium cost (hook lifecycle change). |
| 9 | Throttle variant | A throttle variant that also counts time queued for the lock. Today only long throttle(String, int) exists. | Report 5 | Low value (specialized); low cost (API addition). |
| 10 | Diagnostics path and build commit | The diagnostics path and the build commit in record.json. It has dataPath and pickleballVersion only. | Report 5 | Medium value (better provenance); low cost (extra fields). |

## Earlier deferred speed items

Moved out. These are required in 2.1.18. See `docs/maintainer/2.1.18-speed-roadmap.md`.

Dan's locked list for the next round (hook source tracking, Windows exit-code docs, comma lists for data and config path, short RUN aliases, path-list docs, session start timeout teardown, execute-step failure reason) is in `docs/maintainer/2.1.18-locked-list.md`.

Items #5–#10 stay deferred. Do not implement them in 2.1.18.

## Bug (reported 2026-11-02, Dan, consumer testing on the previous 2.1.18 build): slow live-session start leaves an orphan session
- **Moved to locked item 6** in `docs/maintainer/2.1.18-locked-list.md` (2026-11-02). Implemented on branch 2.1.18. Kept here for history only.
- Symptom: starting a Workbench/agent live session takes over 3 minutes and exceeds its own default startup timeout.
- The isolate process then exits with code 1, but the live session (browser/driver/JVM) is left running.
- Expected: startup well under the default timeout; on timeout, a clear `error:`/`fix:` message and full teardown of the session (no orphans).
- To reproduce: start a live session from a consumer project (e.g. myq-release-testing on Windows, mvnw.cmd), time the startup, then check for leftover processes after the exit.
- Fix direction: profile the slow startup phase, make timeout teardown kill the whole session tree through one shared shutdown path, and add a test that injects a startup timeout and asserts that no process survives.

## Wayland desktop control (unsupported)

- Linux desktop control is AT-SPI plus `xdotool` on X11 or Xvfb. A Wayland-only session is refused at live attach (`NativeDesktop.waylandRefusal`).
- Wayland capture is refused in `DesktopScreenshot`. The `fix:` does not name a Wayland capture tool. `grim` is not a supported path. Windows `desktop screenshot` reads the window rectangle from `WindowsUia`.
- Fix direction if Wayland support is ever wanted: a portal-based input path (libei or the RemoteDesktop portal), which needs user consent per session.
