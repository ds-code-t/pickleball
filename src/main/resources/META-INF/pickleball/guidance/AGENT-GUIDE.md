# Pickleball Consumer Agent Guide

First-read only. Commands, the run loop, and the query loop. Each rule is stated once. Detail is `docs/workbench-agent-detail.md`, `docs/pickleball-workbench.md`, and `docs/diagnostic-query.md`.

## Tool chooser

Pickleball Workbench is the one front door. Do not open the GUI for your own testing. While testing for yourself, stay headless. When a window is already open, only one agent drives it. One window has one driver. Other agents stay headless on their own run id. Agents are not daemons. `run-catalog.json`. Do not start a live worker. `--retention`. **Live debug**. Never supply `pkb_run_profile` as input.

Run the project wrapper (`mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`). Do not require a machine-wide Maven or Gradle install.

```text
mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover"
```

only change `exec.args`: `-Dexec.args=discover`, `-Dexec.args=isolate`, `execute-step`, `status`, `events`, `confirm`, `hint`, `plan`, `query`, `workspace-show`. After `discover` or `workspace-seed`, one-shots are `java -cp @classpath.cache tools.dscode.launcher.PickleballWorkbenchLauncher <args>` and do not start the wrapper. `discover`, `confirm`, and `isolate` still use the wrapper when they build or launch a worker.

Read the short log `.pickleball/agent-log` before, after, and during a run. Board: `presence --touch`, `post --write`, `history --append`. Paths: `.pickleball/investigations/`, `.pickleball/presence/`, `.pickleball/posts/`, `.pickleball/history.log`. `gc-runs` deletes old run payloads, not investigations.

### Live isolation loop

1. `-Dexec.args=discover`, then `confirm` with narrow tags, name, and example. `open-scenario` drives a window that is already open.
2. Nobody watching: `-Dexec.args=isolate`, then `-Dexec.args=execute-step --text='...'`, `status`, `events`, `stop`.
3. No `--run-id` uses the only live session. Several live sessions must pass `--run-id`.
4. A failed `execute-step` is JSON `text`, `phrase`, `message`, `stackHead`. The ACK echoes the text. `events` prints step and phrase lines. `--hooks` is raw. `elapsed-step-ms=idle` when no step is running. `sleep-gap=` is a machine sleep, not a Gherkin sleep step.
5. Emit with `workbench_investigation_emit`. `workbench_diagnostic_catalog` is a deprecated alias of `wb_runs`.

A step that contains quotes (bash, PowerShell, and cmd). Put the step in `step.txt` (`Then the "Save" button is visible`) and pass `--text-file=step.txt`. Or `--text-b64` of that UTF-8 text.

```bash
printf '%s' 'Then the "Save" button is visible' | base64 -w0
```

```powershell
[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('Then the "Save" button is visible'))
```

```bat
certutil -encodehex step.txt step.b64 0x40000001
```

Worked loop and the line to expect:

```text
discover --run-id=row-1
summary status=FAILED
workspace-edit --run-id=row-1 --expect=<fingerprint> --runvar=pkb_browser=EDGE
discover --run-id=row-1
summary status=PASSED
confirm --run-id=row-1
summary status=PASSED
```

`record.json` `environment` is the resolved value, or `unset` when `pkb_environment` was not set. `workspace-show` prints `""` for a blank value and ` computed` when the key is not the project-file value. Do not treat resolve-runvars as the environment Discover will use. `pkb_overriderunvars` is not how confirm launches. The Workbench GUI is only a lightweight head over state and controls that already exist under the hood.

## Query loop

`query runs`, `query get --run-id=<id>`, `query search --text=<words>`, `query errors --run-id=<id>`, `query tail --run-id=<id>` (newest first). Default page is 20. Read `…+N more, use cursor`. Bad UTF-8 is named in `decodeWarnings` and does not fail the search. use the shallowest evidence layer. keep terminal logging minimal. `pkb_reportretention=failed` keeps failing scenarios.

## First-read

1. This page, then the consumer project.
2. `discover`, then `confirm`. Live debug is `isolate` then `execute-step`.
3. Open `docs/workbench-agent-detail.md` only when this page is not enough.

## Generated guidance lifecycle

`export-guidance` writes version-matched managed guidance files and `GUIDANCE-MANIFEST.json`. It ignores `.pickleball` via `.gitignore` or `.git/info/exclude`. If export fails, treat any existing `.pickleball` contents as potentially stale. Stale does not mean an old run.

An older Pickleball release whose exporter predates the manifest lifecycle may leave files behind. Prefer the resolved dependency.

## Generated Maven consumer reference

`.pickleball/maven-consumer-project/` is a read-only reference snapshot. Do not copy, modify, or execute files under it as the project under test.

## When the core Pickleball repository is also present

Core `AGENTS.md` is additive. A normal consumer does not have those files.

## Maintainer-only: pointer-eval harness

Not first-read. `@agent-pointer-eval` is not `@all`. Do not fix those canned failures.
