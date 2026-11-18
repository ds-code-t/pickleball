# Pickleball Consumer Agent Guide

First-read only. Longer notes: `docs/workbench-agent-detail.md`.

## Tool chooser

Pickleball Workbench is the one front door. Do not open the GUI for your own testing. While testing for yourself, stay headless. When a window is already open, only one agent is the one driver on their own run id. Read the short log `.pickleball/agent-log`. Run `mvnw` or `mvnw.cmd` (`gradlew` or `gradlew.bat`). Do not require a machine-wide Maven or Gradle install. That route is pure CLI. An IDE MCP client is not required.

```text
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover"
```

only change `exec.args`: `-Dexec.args=discover`, `-Dexec.args=isolate`, `execute-step`, `confirm`.

`start --url= --browser= --env=` publishes a live session later page commands use when `--run-id` is omitted. `chat_wait` blocks until chat arrives. `history-summary` and `history-query` read runs. `entity-lookup` reads one entity. `open-run` opens Explorer or Tracking. `bridge-health` reads the bridge. Those verbs launch with `java -cp` on the consumer classpath, not a fat jar.

### Live isolation loop

1. `-Dexec.args=discover`, then `confirm`. `open-scenario` drives a window that is already open.
2. Nobody watching: `-Dexec.args=isolate`, then `execute-step`, `status`, `events`, `stop`.
3. No `--run-id` uses the only live session. Several live sessions must pass `--run-id`.
4. `status=idle` or `status=running`. `elapsed-step-ms=idle` when no step is running. `sleep-gap=` is a machine sleep, not a Gherkin sleep step.

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
discover --run-id=row-1
summary status=PASSED
confirm --run-id=row-1
summary status=PASSED
```

`record.json` `environment` is resolved, or `unset`. Never supply `pkb_run_profile` as input. Do not treat resolve-runvars as the environment Discover will use. `pkb_overriderunvars` is not how confirm launches. The Workbench GUI is only a lightweight head over state and controls that already exist under the hood. A finished run id is not reused. `ui` chat writes a popup-ledger row. `inspect-page` uses a live run session when one is published. Board: `presence`, `post`, `history`. `gc-runs`. `session-start` timeout is `error:`/`fix:`. `export-guidance` writes managed guidance. `query` reads the store. A skipped step stays `SKIPPED`. `<entity:Type#id>` resolves from `store-entities` when Store is on, including a dotted id.

Headless Workbench is not a headless browser. `isolate` and `execute-step` skip the GUI. The browser stays the one the project names. `CHROME_HEADLESS` is the fallback only when the project names no browser. A named project browser is kept.

`store entity use` records a read, write, or mention. Omit `--step` to use the active tracked step. The step id is `runId|featurePath|scenarioLine|examplesRow|stepOrd|sha256` of the step text (UTF-8). A plain step looks like `run|features/pay.feature|2||1|<64 hex>`.

The search index is disposable FTS5 at `reports/diagnostic-runs/diagnostics.db` (table `record_fts`). JSON stays authoritative. DiagnosticCli rebuild on `reports/diagnostic-runs` rewrites `run-index.json` and `run-catalog.json` and rebuilds that database. Delete `diagnostics.db` plus `-wal` and `-shm` to drop it. A missing file makes `query search` read the JSON. A present file is rebuilt only when a source is newer. Older notes that say dalton mean store.

`ui-catalog` once, `ui-apply` with a guide op, then `ui-poll`.

## First-read

1. This page, then `discover`, then `confirm`. `isolate` then `execute-step`.
2. Open `docs/workbench-agent-detail.md` only when this page is not enough.
