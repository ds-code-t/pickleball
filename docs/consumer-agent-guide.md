# Pickleball Consumer Agent Guide

First-read only. Longer notes: `docs/workbench-agent-detail.md` (`guidance --detail`).

## Tool chooser

Pickleball Workbench is the one front door. Do not open the GUI for your own testing. While testing for yourself, stay headless. When a window is already open, only one agent drives it. One window has one driver. Other agents stay headless on their own run id. Agents are not daemons: each command is a one-shot process. Read `run-catalog.json` after discover. Do not start a live worker; a live worker is only for isolate and execute-step debugging. Pass `--retention` on that discover. **Live debug** is isolate, then execute-step. Never supply `pkb_run_profile` as input.

Run `mvnw` or `mvnw.cmd` (`gradlew` or `gradlew.bat`). Do not require a machine-wide Maven or Gradle install.

```text
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover"
```

only change `exec.args`: `-Dexec.args=discover`, `-Dexec.args=isolate`, `execute-step`, `confirm`. After `discover` or `workspace-seed`, one-shots are `java -cp @classpath.cache tools.dscode.launcher.PickleballWorkbenchLauncher <args>`. Discover, confirm, and isolate still use the wrapper.

Read the short log `.pickleball/agent-log` before, after, and during a run. Board: `presence --touch`, `post --write`, `history --append`. Paths: `.pickleball/investigations/`, `.pickleball/presence/`, `.pickleball/posts/`, `.pickleball/history.log`. `gc-runs` deletes old run payloads, not investigations.

### Live isolation loop

1. `-Dexec.args=discover`, then `confirm` with narrow tags, name, and example. `open-scenario` drives a window that is already open.
2. Nobody watching: `-Dexec.args=isolate`, then `execute-step`, `status`, `events`, `stop`.
3. No `--run-id` uses the only live session. Several live sessions must pass `--run-id`.
4. `elapsed-step-ms=idle` when no step is running. `sleep-gap=` is a machine sleep, not a Gherkin sleep step.
5. Emit with `workbench_investigation_emit`. `workbench_diagnostic_catalog` is a deprecated alias of `wb_runs`.

```bash
b64=$(printf '%s' 'Then the "Save" button is visible' | base64 -w0)
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=execute-step --text-b64=$b64"
```

```powershell
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('Then the "Save" button is visible'))
.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=execute-step --text-b64=$b64"
```

```bat
>step.txt echo Then the "Save" button is visible
mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=execute-step --text-file=step.txt"
```

The fingerprint comes from `workspace-show`. `workspace-edit` prints `workspace=` and `fingerprint=`.

```text
discover --run-id=row-1
summary status=FAILED
workspace-show --run-id=row-1
fingerprint=<from workspace-show>
workspace-edit --run-id=row-1 --expect=<fingerprint> --runvar=pkb_browser=EDGE
workspace=.pickleball/runs/row-1/workspace
fingerprint=<after the edit>
discover --run-id=row-1
summary status=PASSED
confirm --run-id=row-1
summary status=PASSED
```

`record.json` `environment` is the resolved value, or `unset` when `pkb_environment` was not set. `workspace-show` prints `""` for a blank value and ` computed` when the key is not the project-file value. Do not treat resolve-runvars as the environment Discover will use. `pkb_overriderunvars` is not how confirm launches. The Workbench GUI is only a lightweight head over state and controls that already exist under the hood.

## Query loop

`query runs|get|search|errors|tail` (tail is newest first). Read `…+N more, use cursor`. Bad UTF-8 is named in `decodeWarnings` and does not fail the search. Agents use the shallowest evidence layer and keep terminal logging minimal. `pkb_reportretention=failed` keeps failing scenarios.

## First-read

1. This page, then the consumer project.
2. `discover`, then `confirm`. Live debug is `isolate` then `execute-step`.
3. Open `docs/workbench-agent-detail.md` only when this page is not enough.

## Generated guidance lifecycle

`export-guidance` writes version-matched managed guidance files and `GUIDANCE-MANIFEST.json`. It ignores `.pickleball` via `.gitignore` or `.git/info/exclude`. If export fails, treat any existing `.pickleball` contents as potentially stale. Stale does not mean an old run.

An older Pickleball release whose exporter predates the manifest lifecycle may leave files behind.

## Generated Maven consumer reference

`.pickleball/maven-consumer-project/` is a read-only reference snapshot. Do not copy, modify, or execute files under it as the project under test.
