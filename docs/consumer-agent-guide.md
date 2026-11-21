# Pickleball Consumer Agent Guide

First-read only. Longer notes: `docs/workbench-agent-detail.md`.

## Tool chooser

Workbench is the one front door. Do not open the GUI for your own testing. While testing for yourself, stay headless. When a window is already open, only one agent is the one driver on their own run id. Agents are not daemons. Read short log `.pickleball/agent-log`. `mvnw` or `mvnw.cmd` (`gradlew`/`gradlew.bat`). Do not require a machine-wide Maven or Gradle. Pure CLI. IDE MCP is not required.

```text
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover"
```

only change `exec.args`: `-Dexec.args=discover`, `-Dexec.args=isolate`, `execute-step`, `confirm`.

`start --url-key=URL.app --browser=chrome --headed --panel` opens Chrome (live-start is start; also `--url`, `--hold`, `--size`). Then `chat-turn --text=hi --until=human --timeout=120`, `act --query=Save --pick=1`, `ui-verify`, `doctor`. `call chat-send --args-file=args.json` uses `.pickleball/agent-controller.json`. `execute-steps --file=steps.json --stop-on-fail`. `watch --ref=s1.h1 --until=visible`. `chat_wait` ignores own messages unless `--from=any`. Else `error: no live browser`. `live-stop` leaves a worker. `export-guidance --warm`. `events --follow --until=text=`.

### Live isolation loop

1. `-Dexec.args=discover`, then `confirm`. `open-scenario` drives a window that is already open.
2. Nobody watching: `-Dexec.args=isolate`, then `execute-step`, `status`, `events`, `stop`.
3. No `--run-id` uses the only live session. Several live sessions must pass `--run-id`.
4. `status=idle` or `status=running`. `elapsed-step-ms=idle` when idle. `sleep-gap=` is machine sleep, not a Gherkin sleep step.

`...` is plugin, mainClass, classpathScope.

```bash
b64=$(printf '%s' 'Then the "Save" button is visible' | base64 -w0)
./mvnw -q ... "-Dexec.args=execute-step --text-b64=$b64"
```

```powershell
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('Then the "Save" button is visible'))
.\mvnw.cmd -q ... "-Dexec.args=execute-step --text-b64=$b64"
```

```bat
>step.txt echo Then the "Save" button is visible
mvnw.cmd -q ... "-Dexec.args=execute-step --text-file=step.txt"
```

The fingerprint comes from `workspace-show`. `workspace-edit` prints `workspace=` and `fingerprint=`.

```text
discover --run-id=row-1
summary status=FAILED
workspace-show --run-id=row-1
fingerprint=<from workspace-show>
workspace-edit --run-id=row-1 --expect=<fingerprint> --runvar=pkb_browser=EDGE
workspace=.pickleball/runs/row-1/workspace
discover --run-id=row-1
summary status=PASSED
confirm --run-id=row-1
summary status=PASSED
```

`record.json` `environment` is resolved, or `unset`. Never supply `pkb_run_profile` as input. Do not treat resolve-runvars as the environment Discover will use. `pkb_overriderunvars` does not launch confirm. The Workbench GUI is only a lightweight head over state and controls that already exist under the hood. Finished run id is not reused. `ui` chat writes a popup-ledger row. `inspect-page` uses a published live run session. `presence --touch`, `post --write`, `history --append`. `.pickleball/investigations/`, `.pickleball/presence/`, `.pickleball/posts/`, `.pickleball/history.log`. `gc-runs --dry-run`. `session-start` timeout is `error:`/`fix:`. `query` reads the store. A skipped step stays `SKIPPED`. `<entity:Type#id>` resolves from `store-entities` (Store on), including a dotted id.

Headless Workbench is not a headless browser. `isolate` and `execute-step` skip the GUI. The project browser is kept. `CHROME_HEADLESS` falls back only when the project names no browser.

`store entity use` records a read, write, or mention. Omit `--step` for the active tracked step. Step id: `runId|featurePath|scenarioLine|examplesRow|stepOrd|sha256` (UTF-8).

Disposable FTS5 `reports/diagnostic-runs/diagnostics.db` (table `record_fts`). JSON stays authoritative. DiagnosticCli rebuild on `reports/diagnostic-runs` rewrites `run-index.json` and `run-catalog.json`. Delete `diagnostics.db`, `-wal`, `-shm` to drop it. Missing file: `query search` reads the JSON. Present file rebuilds only when a source is newer.

## Controller health

A down controller runs in-process (no 30s wait, Windows too). `controller-start` is one JVM. `export-guidance --warm` pings under 1 s.

Replies are one envelope (v, ok, op, ms, state, sum, items, next, trunc, err). Short id s1.h42; `ref` expands it. format=lines is the compact view (200 lines, 16 KB). `validate` lists empty glue.

## Generated guidance lifecycle

`export-guidance` writes version-matched managed guidance files and `GUIDANCE-MANIFEST.json`. It ignores `.pickleball` via `.gitignore` or `.git/info/exclude`. If export fails, existing files are potentially stale. Stale does not mean an old run.

## Generated Maven consumer reference

`.pickleball/maven-consumer-project/` is a read-only snapshot. Do not copy, modify, or run it.

## First-read

1. `discover`, then `confirm`. `isolate` then `execute-step`.
