# Pickleball Consumer Agent Guide

This is the canonical AI-agent contract for projects that consume Pickleball as a Maven dependency.

A consumer project may contain only a short `AGENTS.md` bridge. That bridge uses Pickleball Workbench (`tools.dscode.launcher.PickleballWorkbenchLauncher`) `export-guidance` to materialize the version-matched guidance embedded in the installed Pickleball dependency. When this file is materialized as `.pickleball/AGENT-GUIDE.md`, supporting documentation is under `.pickleball/docs/` and a curated reference snapshot of Pickleball's executable Maven consumer is under `.pickleball/maven-consumer-project/`. Full `docs/` and the snapshot stay exported for on-demand lookup. Do not dump them into first-read context.

## Tool chooser

Pickleball Workbench is the one front door. It is a Java program launched from the consumer project, not a GUI requirement. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI.

Run the project's own wrapper. Pick the script from the OS. Do not require a machine-wide Maven or Gradle install. On Windows use `mvnw.cmd` or `gradlew.bat`. Otherwise use `mvnw` or `gradlew`. If that script is absent and the wrapper jar is present, run `java -jar` on `.mvn/wrapper/maven-wrapper.jar` or `gradle/wrapper/gradle-wrapper.jar`. Discover, confirm, and project sync already invoke that wrapper. MCP and launcher tools stay Pickleball actions (`discover`, `confirm`, `isolate`, `execute-step`, `status`, `events`, `stop`, `open-scenario`, `example`, `play`, `pkb_example`, and the other session commands). There is no general Maven or Gradle tool.

The Workbench GUI is only a lightweight head over state and controls that already exist under the hood. Agents have direct access to that state and those controls, and direct control of the GUI controls, so they can collaborate with a person or present data and reports in the window. The GUI must not own behavior the agents cannot reach. Do not click JavaFX or WebView from tests. Do not embed Maven or Gradle. Agents run the project wrapper.

From the consumer project, with Pickleball on the test classpath (`classpathScope=test`), use that wrapper and the same exec invocation as the project pointer; only change `-Dexec.args`:

```text
mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover"
```

Windows is the same command with `mvnw.cmd`. A Gradle consumer that exposes the launcher task uses `gradlew` or `gradlew.bat`.

Same launcher for `hint`, `discover`, `confirm`, `plan`, `resolve-runvars`, `workspace-show`, `workspace-edit`, `workspace-seed`, `short-log`, `note`, `inbox`, `finish`, `presence`, `post`, `history`, `gc-runs`, `gc-versions`, `use-version`, `isolate`, `execute-step`, `status`, `events`, `stop`, `open-scenario`, `example`, `play`, `from-here`, `pause`, `insert-step`, `update-step`, `diagnostic-run`, `save`, `refresh`, `session-sync`, `worker-start`, `worker-restart`, `worker-stop`, `open-window`, `close-window`, `show-run`, `inspect-page`, `find-element`, `session list`, and `session info` — change `exec.args` only.

The Workbench window is a view of a run you already have. `open-window --run-id=<id>` opens the one window on that run. `show-run --run-id=<id>` loads that run's record, logs, reports, and config. The window's run dropdown reads the same `.pickleball/runs/` directory. Neither command starts a test. `close-window` closes the window and leaves the run directory, a headless run, and the short log in place. Play, step, and stop apply only to the one live run. Another agent may view a different run read-only and does not become a second driver of the live session. There is no Gherkin step that opens the window. An agent that can only run a scenario and cannot call the launcher cannot open it.

1. **Discover** — `-Dexec.args=discover` (optional `--tags` / `--name` / `--example` / `--retention` / `--select` / `--plan` / `--refresh` / `--agent` / `--runvar key=value`). Workbench seeds `.pickleball/runs/<run-id>/workspace/` once and launches with `-Dpkb_workspace` plus selection keys. The project browser is kept. `CHROME_HEADLESS` is used only when the project names no browser. `pkb_parallel=auto` stays `auto` in the workspace and is resolved at launch. Override retention with `--retention=all|failed|none` (`--retention <value>` also works). Repeatable `--runvar` writes a non-selection key into the workspace and a selection key onto that launch. `environment` is stored as `pkb_environment`. A conflict warns and the flag wins. That flag is Workbench discover, confirm, and isolate only. It does not change a normal `mvn test`. It runs the consumer test through the project wrapper. Do not start a live worker to run the whole suite. Then read `run-catalog.json` and the retained `pkb_run_profile`. Trust `environment` and `browser` on `record.json`, and the same tokens on the summary line. `plan` (or `discover --plan`) resolves the same filters, including `path/file.feature:line`, and does not build or open a browser.
2. **Confirm** — `-Dexec.args=confirm --tags=... --name=... --example=...` uses the Discover run's workspace. Narrow tags, name, and example are selection keys for that launch. Never supply `pkb_run_profile` as input. `pkb_overriderunvars` is deprecated for Workbench and is not how confirm launches. `pkb_example` is not a tag. A normal Scenario counts as row 1. `--example='1 2 5 3.4 7-11'` keeps those Examples rows in each selected scenario. A classpath path with `%20`, or a Windows `\`, is decoded before that filter reads the feature. One unreadable feature is skipped with a warning and does not abort the run.
3. **Live debug** — When nobody is watching, `-Dexec.args=isolate` (alias `session-start`) starts one long-lived headless Workbench session from the last Discover snapshot and prints `ACK SESSION`. When a Workbench window is already open, do not start another session. Drive that session with the same commands so the person sees the result: `open-scenario`, `example`, `play`, `from-here`, `execute-step`, `pause`, `stop`, `diagnostic-run`, and the worker, save, and refresh commands. `example` is the same Examples-row filter as `pkb_example`. Play shows the first source-order match. Headless `pkb_example` still runs every match. Then `-Dexec.args=execute-step --text='...'`, `status`, `events`, and `stop`. Each launcher exec exits; the session stays up. A live session writes `.pickleball/runs/<run-id>/session/descriptor.json`. `session list` and `session info <sessionId> [--json]` print that file. Use it to attach dom-composite 2.4.0 to the browser Pickleball already owns. Read [Direct-control handoff](direct-control-handoff.md) before attaching. Detach without closing the browser. A normal `mvn test` does not write the descriptor.
4. **Emit the human handoff, then edit real consumer source** — write `.pickleball/investigations/<id>/` then in chat print the six-line bottom-line block (Gherkin/business first) plus `.pickleball/investigations/<id>/report.html`. Do not dump MCP transcripts, TRACE, or PNG analysis.

`hint` (alias `discover-hint`) is `-Dexec.args=hint` and prints how to seed, show, and edit `.pickleball/runs/<run-id>/workspace/runvars.properties` and `workspace/configs`, then launch with `-Dpkb_workspace` plus selection keys. It does not print a recommended `pkb_runvars` string. It still prints a dry-run resolve preview that does not start tests, and `NEXT: run discover`. That preview is not the launch. Discover seeds a workspace. `pkb_overriderunvars` is deprecated for Workbench. It still works for a plain `mvn test` that does not set `pkb_workspace`. Do not mix that deprecated input with `pkb_runvars` or `pkb_profile` on a Workbench launch. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. If `workbench_*` tools already exist they are the same session, not a setup step.

Do not treat resolve-runvars as the environment Discover will use. That preview is the launcher JVM, not the Discover worker. Trust the run record after Discover.

### Workspace

Workbench resolves run vars once into `.pickleball/runs/<run-id>/workspace/runvars.properties` and copies configs to `workspace/configs`. Every launch mode passes only `-Dpkb_workspace` plus selection keys (`pkb_tags`, `pkb_name`, `pkb_example`, `pkb_features`, `pkb_parallel`, `pkb_run_id`, `pkb_agent_id`). Edit those files before the next start. The GUI Config tab and the run-var panel write the same files, atomically, and warn instead of overwriting a change already on disk.

- `workspace-show --run-id=<id>` prints `workspace=`, `fingerprint=`, the run vars, and each config path.
- `workspace-edit --run-id=<id> --runvar=key=value` writes a run var. `--config=<relative> --text=<body>` writes one config file. `--expect=<fingerprint>` from `workspace-show` refuses the write when the workspace changed.
- `workspace-seed --run-id=<id>` captures the consumer runner. `--from-run=<id>` copies an earlier workspace. `--reseed` replaces the existing one.

The project browser is kept. `CHROME_HEADLESS` is used only when the project names no browser. `pkb_parallel=auto` stays `auto` in the file and is resolved when the run starts. Isolate does not force `pkb_parallel=1`. One scenario is a selection, not a forced parallel count. The six context keys (`pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, `pkb_configpath`) are always written. A missing one is an error at launch. A blank value is allowed. Secrets in run vars are stored as `${protected:key}`, never the value. A literal secret in the configs copy is a warning and is not rewritten.

`pkb_overriderunvars` is deprecated. It still works for a plain `mvn test` or `gradlew test` that does not set `pkb_workspace`. Do not use it for Workbench.

Caveats of a `java -cp` capture:

- Surefire-only POM values (`systemPropertyVariables`, `argLine`) are not visible. Type them into the workspace.
- Hook-computed time, environment, and random values are frozen at capture. Delete the key or run `workspace-seed --reseed`.
- Hook side effects outside `pkb_*` system properties do not run again at launch.
- A protected reference reads the project property at launch.

A normal `mvn test` / `gradlew test` does not set `pkb_workspace`, so its resolution is unchanged.

Do not copy consumer features into `.pickleball` as a sandbox.

### Run coordination

Read the short log `.pickleball/agent-log` before a run, after a run, and during a long session, instead of the dense diagnostic log. Use your own run id. When a Workbench window is already open, only one agent drives it. Other agents stay headless on their own run ids. Agents are not daemons. Write start and stop lines. Put purpose and findings on the run record. Use the inbox only for a short note to one named agent. Taking that note deletes it. A note for every agent is a post (`post --write --to=all`), not an inbox file, and listing or taking does not delete it.

`discover`, `confirm`, and `isolate` accept optional `--run-id`, `--agent` (or `--agent-id`), `--group`, `--sequence`, `--who`, and `--why`. They write the start line and `.pickleball/runs/<run-id>/record.json`. The isolation unit is the run id. One agent may own many run ids. Uncoordinated agents each get their own id. Two runs never share a folder, a config copy, a session file, or a browser profile. A private runs directory is only for an agent run or a Workbench run. A normal `mvn test` does not create `.pickleball/runs`, does not set `user-data-dir`, and keeps `reports/cucumber-report.html`. Reports, diagnostic packs, scratch, and a headless session file for an agent or Workbench run stay under that run directory. Each parallel local Chrome or Edge worker gets its own `browser-profile/<worker>` directory. Remote drivers and a consumer-supplied `user-data-dir` are not that directory and are left alone. `finish` and `stop` only write `stoppedAt` and a stop line. They do not delete the run directory, the profile, the session file, or the log. `close-window` keeps that behavior. `gc-runs` is the explicit payload sweep. It is not run from finish. `--retention` does not call `gc-runs`. Delete `browser-profile/<worker>` only when that run has a stop line, nothing has the profile open, and no agent holds the run. Chrome `SingletonLock` and an Edge `lockfile` both mean the profile is open. Not when Cucumber returns, and not on finish alone. Keep dense evidence for the run open in Workbench and for the last failed run. The last failed run is the latest stopped run whose status is FAILED, or a STOPPED record with a failure count, a failed-scenario flag, or a non-empty failure summary. `finish` writes FAILED when that run had failures and STOPPED only when it did not. Other stopped run payloads may be removed 24 hours after `stoppedAt`. Always leave `record.json`, the sparse index, and `pkb_run_profile` (the resolved RunVar snapshot, not the Chrome directory). Never delete a run with no stop line, a `RUNNING` run, another agent's held run, a live session, a live worker, or the run the open window is showing. Two sweepers removing the same expired payload must be safe. A Workbench run stores run vars and a configs copy under `.pickleball/runs/<run-id>/workspace/`. `workspace-seed` writes it before the first launch. The test JVM does not copy configs into the run when it starts. The stored `pkb_configpath` stays the project path unless you edit the workspace. Change a run's configs in `workspace/configs`, with `workspace-edit`, or in the Workbench Config tab. Do not edit the project config files for one run. Saving in the tab writes the workspace copy and does not write the project. A normal `mvn test` does not set `pkb_workspace` and still reads the project files. Discover and Workbench, including a person collaborating with an agent, default `pkb_compositeReport=false` and `pkb_scenarioReport=false`. A normal test still writes both HTML reports. An explicit `true` writes that report again. Agents keep diagnostic logs. `finish --run-id=<id> --learned=<one line>` and `stop --run-id=<id>` write the stop line. `short-log` prints recent lines. `note --text=<one line>` appends a note. `inbox --write --to=<agent-id> --text=<one line>` leaves directed mail; `inbox --list` does not delete it; `inbox --take --agent=<id>` deletes that note. `inbox/any` is not a broadcast. Use `post` for that. Same launcher; only change `-Dexec.args`. Project wrapper only (`mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`, or `java -jar` on the wrapper jar). Do not require a machine-wide Maven or Gradle install.

One window has one driver. Each agent uses its own run id. Launcher commands are one-shot and exit. A 10-minute presence sweep is optional and cannot be enforced.

### Board

Three files. Do not merge them into the inbox.

1. Presence. `.pickleball/presence/<agent-id>.json` has the agent id, `lastSeen`, and current run ids. Update it when you want: `presence --touch --agent=<id> [--run-id=<id>]`, `presence --list`, `presence --sweep`. An active agent may drop a row whose `lastSeen` is older than 15 minutes. That drop removes the row only. It does not delete posts, runs, profiles, or `history.log`. A missing heartbeat means not on the board, not clear their work. Two sweepers are idempotent.
2. Posts. `.pickleball/posts/<id>.json` has id, from, to (an agent id or `all`), optional run id, one line, `createdAt`, `updatedAt`, `expiresAt`, and optional `ackedBy`. `post --write --to= --text= [--run-id] [--ttl=]`, `post --list`, `post --renew --id=`, `post --sweep`. Every agent may list them. Do not delete on read. Ack is optional and is not required to clear. Default expiry is 24 hours from the last update, not from the first write. The author may set a shorter expiry. One hour is appropriate for "I have the window." Short expiry is not the default. Renew by updating `updatedAt` and `expiresAt`. A missing or unreadable timestamp counts as expired. After expiry, any active agent may delete that post file. Two deletes of the same file are fine. Deleting a post does not delete the run, the profile, the presence row, or `history.log`. A post is a hint, not a lock. Readers treat it as possibly stale even if it is new.
3. Summation log. `.pickleball/history.log` is append-only. One short line: version, files, fix, branch, commit, merge, run id. `history --append --text=` and `history --tail`. A missing run directory is normal. Trim from the front when the file exceeds 10 MB. Not a mailbox. Do not put keep-alives here.

The short log `.pickleball/agent-log` stays the 3-day coordination tape. Appending does not take a file lock. On prune, a dropped run whose directory remains keeps a one-line stub so the index does not vanish. Do not put fix history in the short log. Do not add a lock a crashed agent can hold.

### .pickleball has two owners

Do not collapse `.pickleball` under `v/`.

Jar cache, regenerable from the resolved dependency:

- `.pickleball/v/<current>/` guidance, docs, the maven-consumer-project snapshot, `workbench/controller/<sha256>`, and `workbench/lib/<version>`
- `.pickleball/open/`
- root aliases `AGENT-GUIDE.md` and `GUIDANCE-MANIFEST.json`
- `current.json` written last: `pickleballVersion`, `complete`, `updatedAt`

Project history. It must survive `export-guidance` and a version bump. It stays at the `.pickleball` root:

- `runs/<run-id>/`
- `agent-log`
- `inbox/`
- `investigations/`
- `presence/`, `posts/`, `history.log`

`.pickleball/workbench/` is the legacy fallback when `current.json` is missing or not usable. Session, attach, and last-discover stay under `v/<version>/workbench` for the version that owns them. When `current.json` is complete, the next launch uses `v/<current>/workbench`. Switching the pointer does not destroy the other version's workbench tree. Do not describe both layouts as current.

Export cleanup is a manifest diff of generated files in that version folder. `export-guidance` and `use-version` do not delete any other `v/<other-version>/` tree. `gc-versions` is the explicit version sweep. It is not a daemon, and it is not run from `export-guidance`, `use-version`, or finish. It may delete `v/<version>/` only when that version is not named by a usable `current.json`, its last use is at least 3 days ago, no RUNNING run was started with it, and that version's workbench tree has no live session, attach, or worker. A missing or unreadable `v/<version>/.last-used` counts as last used now, so an old tree is not deleted on the first sweep. Deleting the tree removes that version's guidance, controller, libs, and workbench state only. It does not delete `runs/`, `investigations/` (including a legacy `v/<version>/investigations` tree), `presence/`, `posts/`, `inbox/`, `agent-log`, or `history.log`. Two sweepers deleting the same expired tree must be safe. Never delete `current.json`, `open/`, or the `v/<current>/` tree named by a usable pointer.

A version tree is last used when `export-guidance` completes it or `use-version` points `current.json` at it. That time is `v/<version>/.last-used`. A switch in the same session updates `.last-used`, so the tree cannot expire while it is being tested. An unused version tree is expired, not stale.

`use-version --version=<version>` switches the pointer to an already complete `v/<version>/` export. It rewrites `current.json`, refreshes root `AGENT-GUIDE.md` and `GUIDANCE-MANIFEST.json`, and refreshes `open/` from that tree. It does not re-copy a sibling version and does not delete the tree being left. If that version tree is missing or not a complete export, it prints that `export-guidance` from that dependency is required and leaves `current.json` unchanged. The last `use-version` or `export-guidance` wins the pointer. Switching the pointer does not stop a run, delete a run, delete a browser profile, or clear presence, posts, inbox, investigations, or `history.log`. A run records the version it started with and is not retargeted when the pointer moves. An agent switches versions in one session with `use-version`, or with `export-guidance` from the dependency it wants.

New investigation handoffs always go to `.pickleball/investigations/<id>/`. Do not delete an existing `v/<version>/investigations` tree. If the root path for an id is absent, read the legacy versioned path. `export-guidance` leaves both trees alone.

### Selection, snapshots, and status

`discover` and `confirm` echo the parsed tags and name. They use a hash-checked feature index and, when that index can narrow the selection, pass only the matching feature paths to top-level Cucumber discovery as one `-Dpickleball.workbench.topFeatures` argument. That argument is not written into `pkb_features`. The configured `pkb_features` stays in the worker runvars, so a nested `SCENARIO` / `COMPONENT` / `CALL` / `RUN` still calls `CucumberScanUtil.listPickles` against the full configured feature set. A scenario in another feature file is found, including a path that contains spaces and a Windows `wd-file:C:/...` path. `--no-narrow` and `--full-scan` skip the top-level list. An unreadable feature, or a tag expression the index does not compile (`xor` is one), also falls back to a full scan. Cucumber does not accept `xor`. The warning includes the working escaped form `--tags=@smoke\ xor\ @wip` (one literal tag). The tags string sent to Maven is rewritten to `(A or B) and not (A and B)`. An already escaped expression is left as written. `and` / `or` / `not` are unchanged. Full scan is not required for a nested scenario in another file; it remains for an unreadable feature or this kind of tag expression, and that path still walks the whole suite. Configured `pkb_glue` is not replaced by narrowing. A retained profile that looked like it had less glue than the runner was not confirmed as a failure. The index treats `Example:` as a scenario and `Scenarios:` as an examples table, ignores docstring bodies, and always includes a file it cannot model, including a `# language:` header other than `en` or a real Gherkin keyword from another dialect. A free-text description such as `Note:` does not force inclusion. The cache schema is `feature-index.v3`. A sidecar `feature-index.v3.stat` records size and last-modified time. A warm select skips the file bytes when both match. A size or time change still hashes, and a new hash still reparses. `--refresh` ignores the sidecar. That index is not the Cucumber runner. A normal `mvn test` still scans the way it always has and does not read `pickleball.workbench.topFeatures`. A select that changes nothing does not rewrite the index. Discover and confirm append `workbench launch` lines to `runs/<id>/launch-timing.txt` (launcher clock and test-JVM clock are separate). A narrowed list longer than 4,000 characters is stored in `top-features.txt` and passed as `@` that file so Windows can start Maven.

`--select src/test/resources/features/checkout.feature:42` is a Cucumber path and line. Repeat the flag for another line. `plan` prints each hit as `path:line scenario name`, and an Examples row adds `example` plus `header=value`. An empty plan warns and does not start Maven. `--refresh` reparses every feature into the index.

Tag expressions on this command line are `@tag` with `and`, `or`, and `not`, and parentheses. The name after `@` is case-sensitive. Include with `and`. Exclude with `not`. `@all` and the other suite-profile tags are not agent tags. Do not put `@all` on `@agent-pointer-eval`. An expression that contains spaces must be quoted (`--tags='@A and not @B'`). Maven `exec` and PowerShell split an unquoted `-Dexec.args` value on spaces. Workbench then exits non-zero before `plan`, `discover`, `confirm`, `isolate`, or any other command runs, and the error shows the reconstructed expression (`@A and not @B`). Quote it, or pass `--tags-file=<path>` or `--tags-b64=<base64 of the UTF-8 expression>`. A later option such as `--run-id=x` is not part of the expression. An unmatched quote or parenthesis fails immediately with `Tag quoting error`. An invalid `--name` regular expression fails with `Name quoting error`. This check is the Workbench command line only. It does not change how `pkb_tags` and `pkb_name` are parsed inside a test.

The shared `last-discover.json` is the latest discover. A later agent that omits `--agent` replaces it, and the next `confirm` or `isolate` without `--agent` replays those filters. Pass `--agent=<id>` so that agent keeps `last-discover-agent-<id>.json`. Each run also keeps `last-discover-run-<id>.json`. Confirm prefers the agent file, then that run’s file, then the shared file. Discover prints the profile for the run it just finished, not whichever catalog row was written last.

Maven `target/` and Gradle `build/` are generated. Workbench sync reads that output when it builds the worker classpath and `.pickleball` `live/classes`. Those trees are sync input, not the project. Do not hand-edit them. Change source and let the project wrapper rebuild.

A Workbench discover or confirm that selects nothing prints `status=EMPTY`, the effective tags, name, example, and browser, and exits non-zero. It does not print `status=PASSED`. The run record status is `EMPTY`. If you did not pass `--learned`, the record says which filters matched nothing. Diagnostic mode still reports `NO_TESTS`. A normal Cucumber run is unchanged. When scenarios did run, a wrapper exit of 0 is not enough: `maven.test.failure.ignore` stays 0 after a failure. The current run's `run-index.json` (`outcome`, `counts.failed`, `counts.errors`) sets `summary status=`, `record.json` (`failureCount`, `failedScenario`, `failureSummary`), and the launcher exit. An `ERROR` outcome counts. The index may be `diagnostic/<run-id>/run-index.json`, `diagnostic/run-index.json`, or `reports/diagnostic-runs/<run-id>/run-index.json`, including a Windows path. A worker record that already says `PASSED` is corrected from that index. If that index is missing, a Surefire or Gradle XML report from this run is used, including `errors`, then the wrapper exit code. A full scan with no scenario directory still reports `scenarios=` and `steps=` from the index, or from the console summary, instead of zero.

`--step-timeout=<seconds>` applies to `execute-step` and is measured from that call. `discover` and `confirm` reject it: use `--run-timeout`; per-step limits apply to `execute-step`. `--run-timeout=<seconds>` on `discover` or `confirm` records `TIMEOUT` for the whole Maven run. On `execute-step` it is measured from the run's `startedAt`. A timed-out step is still running; use `stop --force`. They are not `pkb_stepMaxTime`. A timed-out `discover` or `confirm` kills the wrapper and its child processes. `stop --force` kills the worker and browser process tree the same way and records `STOPPED`. `stop` without `--force` still waits. `--stop-after=<step>` is the live alias of the one-shot breakpoint. `status` and `events` print `sleep-gap=<milliseconds>ms at <instant>`, or `sleep-gap=none`. That line is a machine sleep or stall from the session heartbeat. It is not a Gherkin sleep step.

`No healthy Workbench session` names why: `never-started`, `still-starting`, `crashed`, or `missing-session-file`. Session start logs `start` only after the session is healthy. `pkb_workbench_worker_start_timeout` (system property, `PKB_WORKBENCH_WORKER_START_TIMEOUT`, dotted `pkb_runvars.pkb_workbench_worker_start_timeout`, or a compact `pkb_runvars` member; default 180; blank, non-numeric, or non-positive stays 180) limits both the worker start and the `isolate` / `session-start` health wait. The forked controller is given that same value. `open-window` still waits 60 seconds. While session start waits, stdout prints `session-start progress elapsed=<ms>ms stage=<stage>` about every 12 seconds. The stage is the latest `progress stage=` line in the session log or a worker stderr log. Before any marker the stage is `starting / Maven resolve or feature index`. A health timeout names the stage, the elapsed time, per-stage durations, the last 20 lines of the relevant log, and how to raise the limit. Stage enter times are written to `runs/<id>/session/startup-progress.json`. A worker startup timeout still names the elapsed time, the stage (`compile`, `classpath`, `worker-launch`, `anchor-parse`, or `paused`), and the last worker output. That setting is Workbench only.

After a Workbench run, read `runs/<id>/workbench/summary.json` for step, status, assertion result, and duration. `runs/<id>/workbench/trace-index.json` maps a step to its byte range in the trace. The same summary is also beside each scenario directory. A pass whose `events.jsonl` was removed by `--retention=failed` still has the scenario name, status, and duration from the core summary. `summary steps=` still counts that scenario from `steps.executed` when the step list is empty, for retention `all`, `failed`, and `none`. The run index fills `steps=` only when that sum is 0. Step lines are present when `events.jsonl` was kept. A core `summary.json` that already exists is left alone. Console text for that run is `runs/<id>/console/<scenario>.txt`, copied from the wrapper stdout and stderr. A `Scenario:` or `Scenario Outline:` line matches that scenario even when `# file:line` follows, including a Windows path or a path with spaces. Text that matches no scenario is `unassigned.txt`. A Workbench discover or confirm (`pkb_run_purpose` starting with `workbench-`) prefixes each console line with `# pkb-console ` and the URL-encoded scenario name, matched exactly, so `Pend` does not take a line from `Pend case`. That prefix covers `print`, `println`, `printf`, `print(char[])`, and `write(byte[])`, including a Logback or Log4j2 console appender that already captured the original stream, one print that contains several lines, and a thread started while the scenario is running. The saved console file keeps the text and does not keep the `# pkb-console` prefix. `unassigned.txt` is omitted when every line matched. A normal `mvn test` does not install that tap, so its output stays the same. The console file contains what the wrapper printed. Failure text can include `final XPath`, `raw match count`, `visible count`, `rejection`, and expected versus actual. The core event and trace files are not rewritten.

`inspect-page` lists interactive elements and the Pickleball phrase that would target each one. It takes no category. `find-element --category=Button --text=Save` prints the XPath the execution dictionary would build, the raw match count, and the visible count. `--html=<file>` is an offline fallback labelled `source=offline-html` (well-formed XML, HTML5 doctype allowed). Without `--html`, both commands use the live session (`source=live-session`) and do not click. No session prints `start a session first` and exits 2. The live query polls for 30 seconds. If it is still running, or the session reports `TIMEOUT`, the command prints `status=TIMEOUT` and exits 3. It does not exit 0 with no hits. Exit 1 is a failed query. A wrapped I/O failure on any of these commands includes the `IOException` cause.

The workspace configs copy is a plain copy of the project configs (real values, valid YAML) so the worker can load them. The project config is unchanged. A literal secret in that copy is a warning. Run vars store protected references instead. Redaction for logs and reports stays a separate path and does not rewrite that copy.

`pkb_browser` is a name in the `configs` mapping, not a browser binary. Workbench keeps the project browser. `CHROME_HEADLESS` is used only when the project names none. A normal `mvn test` uses the project value. A workspace edit of `pkb_browser` is what the next Workbench launch uses.

`gc-runs` deletes expired run payloads. It is not part of `finish`, and `--retention` does not invoke it. `--retention=all|failed|none` only changes what that Workbench discover or confirm keeps. Discover’s default is `failed`.

### Live isolation loop

After Discover has found the failing scenario, use the session that is already open when a person is watching. The same commands update that window. When nobody is watching, `isolate` starts a headless Workbench session if one is not already healthy. Later launcher commands are one-shot HTTP clients against that session (127.0.0.1). Default `--wait` on `execute-step` prints `STILL_WORKING` while a browser wait is in flight, then `DONE <id> SUCCESS|FAILED`. `--ack-only` prints ACK and exits. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI.

1. `-Dexec.args=isolate` (or `session-start`) — starts the session from the last Discover snapshot and prints `ACK SESSION ...`. The health wait uses `pkb_workbench_worker_start_timeout` (default 180 seconds). While it waits, stdout prints `session-start progress elapsed=<ms>ms stage=<stage>` about every 12 seconds. A timeout names the stage, the elapsed time, and the log.
2. `-Dexec.args=execute-step --text='...'` — queues Gherkin on the paused worker. `--ack-only` returns immediately; default `--wait` polls `status`.
3. `-Dexec.args=status` or `status <id>`; `-Dexec.args=events`.
4. `-Dexec.args=stop` (or `kill`) when finished.
5. Confirm with Workbench `confirm` (`-Dexec.args=confirm --tags=... --name=... --example=...`). Read the pack with `workbench_diagnostic_catalog`, `workbench_diagnostic_run`, and `workbench_diagnostic_summary` when those tools already exist.
6. Emit the human handoff with `workbench_investigation_emit` or `DiagnosticCli emit-investigation`. In chat print the six-line bottom-line block, then the `report.html` path. If a UI session is attached, at most two `wb://` links may follow. If no UI, omit `wb://`.

`execute-step` / `workbench_execute_step` returns a structured `SUCCESS` / `FAILED` / `UNAVAILABLE` result. A FAILED Gherkin hypothesis does not end the worker and does not fail the paused scenario. `workbench_step_resolve` maps one Gherkin step to its Java definition (or `DYNAMIC` / `OVERRIDE` / `UNMATCHED`) without executing it. Page events with `afterSequence` and a small `limit` (default 100, max 500). Live buffer edits do not require `workbench_sync` and do not write the original `.feature` until explicit Save (`workbench_request_save`). Worker restart without rebuild already exists (`workbench_worker_restart`). Step Overrides compile worker-side (`workbench_step_override_compile`).

### Generated trees are not the project

- `.pickleball/maven-consumer-project/` is a version-matched **read-only** reference snapshot of Pickleball's own example consumer. Do not copy, edit, or execute it as the project under test.
- `.pickleball/workbench/live/classes` is the compiled overlay for the worker classpath. Do not use it as an editor.
- `.pickleball/investigations/` is project history, not generated guidance. New handoffs always go there. A legacy `v/<version>/investigations/<id>` tree is still readable when the root path for that id is absent. `export-guidance` leaves both trees alone.
- `export-guidance` does **not** copy this consumer's own features into `.pickleball` for testing. It still materializes full `docs/` plus the example-consumer snapshot for on-demand/human use.

## First-read

Keep first-read small. After a successful export:

1. Follow the consumer project's own instructions first; they remain authoritative for project-specific behavior.
2. Stay in this guide's tool chooser: Workbench `discover` when the failing scenario is unknown, then `confirm` with narrow tags, name, and `pkb_example`. When a Workbench window is already open, drive it with `open-scenario`, `example`, `play`, `execute-step`, `stop`, and `diagnostic-run`. When nobody is watching, use `isolate` then `execute-step` / `status` / `events` / `stop`. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Run builds through the project wrapper (`mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`, or `java -jar` on the wrapper jar). Do not require a machine-wide Maven or Gradle install.
3. Inspect the **real** consumer `pom.xml`, Pickleball runner subclass, features, configuration, data, mappings, and test support before changing them.
4. Open a specific exported guide only when that topic is needed, for example `docs/dynamic-steps.md`, `docs/diagnostic-reporting.md`, `docs/configuration.md`, or `docs/ai-run-configuration.md`.
5. Do not assume the Pickleball core source repository is present. A normal consumer may only have the Maven dependency.

Do not read `docs/README.md`, the whole `maven-consumer-project/` snapshot, or Workbench GUI pages as first actions. Those remain available on demand.

The exported documentation and Maven consumer reference are version-matched to the Pickleball artifact on the consumer's test classpath. Prefer them over instructions or examples copied from another release.

## Generated guidance lifecycle

Treat `.pickleball` as generated dependency guidance, not as a durable source of truth by itself. Do not skip `export-guidance` merely because the directory already exists. The consumer bridge intentionally reruns the exporter before Pickleball work so the Maven dependency currently resolved on the test classpath remains authoritative.

A successful `export-guidance .pickleball` run:

- writes version-matched managed guidance files under `.pickleball/v/<pickleballVersion>/`, including documentation and the Maven consumer reference snapshot;
- copies root `AGENT-GUIDE.md` and `GUIDANCE-MANIFEST.json` as aliases of that version so the well-known agent pointer stays stable;
- writes relocatable Workbench openers under `.pickleball/open/`;
- writes `.pickleball/current.json` last (`pickleballVersion`, `complete`, `updatedAt`) — a version pointer, not a path to the dependency jar;
- records last use on `v/<version>/.last-used`;
- removes files managed by the previous manifest that are no longer shipped in that version folder, while leaving unrelated files alone, including `.pickleball/investigations/` and any other `v/<other-version>/` trees; and
- best-effort ensures `.pickleball` is ignored by Git, preferring an existing `.gitignore` and then repository-local `.git/info/exclude`.

Any Pickleball execution (`PickleballRunner`, `java -jar pickleball.jar`, Workbench launcher) lazily materializes the running jar's version folder if it is missing. It unpacks from the running jar; it never copies a sibling version folder. Direct `java -jar pickleball-X.jar` pins `current.json` to X. Open scripts set `PKB_OPEN_BOOTSTRAP=1` so Java hops to the pinned jar when `current.json` is complete.

The exporter does not create/commit a new `.gitignore`, alter the Git index, or untrack files that were already committed. If export fails, treat generated guidance as potentially stale. Stale means an untrusted export: `complete: false`, a missing `current.json`, or a failed export. It does not mean an old run. It does not mean an old version tree. An unused version tree is expired, not stale. An agent switches versions in one session with `use-version --version=<version>`, or with `export-guidance` from the dependency it wants. The manifest records the last completed export; it is not a substitute for rerunning the exporter.

Compatibility note: an older Pickleball release whose exporter predates the manifest lifecycle may leave newer files behind after a downgrade. Version folders keep each export isolated. Prefer the dependency actually resolved on the test classpath and files freshly exported by that dependency.

## Generated Maven consumer reference

`.pickleball/v/<version>/maven-consumer-project/` (and the root alias path `.pickleball/maven-consumer-project/` on flat exports) is a generated, read-only reference snapshot of the canonical Maven consumer used by Pickleball itself. It preserves repository-relative paths so links from the exported Markdown documentation continue to resolve locally. It is not the consumer project under test and is not a writable sandbox.

The snapshot intentionally includes the consumer `pom.xml`, Pickleball runner, local browser/service test server, executable feature files, service-call definitions, configuration/data fixtures, local test-site resources, and the committed shared/local profile and property examples. It intentionally excludes Maven wrappers, Git/IDE/generated artifacts, the consumer `AGENTS.md` bridge, internal Java verification classes, and maintainer-only `_local2` files.

Use the snapshot only to answer questions such as how a working feature, profile, property file, service call, configuration resource, browser fixture, or runner is structured. Do not copy, modify, or execute files under `.pickleball/maven-consumer-project/` as the project under test. Make requested changes in the consumer project's own source tree. A later `export-guidance` run may overwrite or remove every managed reference file. `export-guidance` does not copy the consumer's own features into `.pickleball` for testing.

## Scenario authoring and fixes

When changing a consumer scenario:

- Prefer existing documented Pickleball syntax and executable examples.
- Use the version-matched `maven-consumer-project/` reference when it provides a working example of the same syntax or configuration.
- Do not invent a new Gherkin phrase when a Pickleball step or supported dynamic-step form already expresses the behavior.
- Preserve standard Cucumber behavior and project-specific custom glue.
- Inspect related mappings, component scenarios, service-call definitions, configuration, and test-site support before assuming a failing line is self-contained.
- Make the smallest change supported by evidence.
- Rerun the narrowest useful scenario/tag selection first, then broaden validation when needed.

### Preferred reusable RUN authoring

When a scenario invokes reusable regular scenarios, component scenarios, or service calls, prefer one table-driven `RUN` step with one row per invocation. Rows may mix the runnable kinds in the same step:

```gherkin
When RUN
  | RunType            | RunKey | Run Tags         |
  | SCENARIO           | setup  | %setup           |
  | COMPONENT SCENARIO | login  | %login-component |
  | SERVICE CALL       | health | %health-full-url |
```

Treat `RunType` as the complete kind plus multiplicity. Valid values are `SCENARIO`, `SCENARIOS`, `COMPONENT SCENARIO`, `COMPONENT SCENARIOS`, `SERVICE CALL`, and `SERVICE CALLS`. Singular/plural validation is per row, not a property of the whole table. A nonblank table `RunType` overrides any inline type for that row.

Use parameterized step text as shorthand when it eliminates the table or moves a value common to every row out of the table. For example:

```gherkin
When RUN SCENARIO
  | Run Tags |
  | %tagA    |
```

and:

```gherkin
When RUN SCENARIO: %tagA
```

The same rule applies to a quoted inline `RunKey`: table `RunKey` wins when nonblank; otherwise the inline key is the shared fallback. Do not expand a concise table into several adjacent `RUN` steps merely because the rows use different `RunType` values.

A keyed deferred `RUN` stores its result only after the selected scenario subtree completes. Save explicit `RETURN` when present; otherwise save the completed default scenario root. Normal RunMap/NodeMap collection semantics apply, so repeating an ordinary top-level `RunKey` appends results and an unindexed read resolves the latest item. Do not assume a keyed `RUN` exposes a live pre-execution scenario-root reference.

Use supporting guides as appropriate, especially `docs/dynamic-steps.md`, `docs/component-scenarios.md`, `docs/service-call-scenarios.md`, `docs/mapping-and-templating.md`, `docs/data-values-and-elements.md`, `docs/configuration.md`, and `docs/cucumber-compatibility.md`.

## Configuration and controlled RunVars

Treat these as distinct concepts:

- `default_profile` — internal reference snapshot of normal resolved project RunVars;
- `pkb_runvars` — preferred controlled-run **input**;
- `pkb_run_profile` — canonical resolved RunVar **output** retained for diagnostics/replay.

Never supply `pkb_run_profile` or `pkb_run_profile.<pkb_var>` as input. They are reserved internal derived-output names; Pickleball rejects external use. Use `pkb_runvars` or `pkb_runvars.<pkb_var>` for controlled execution.

### Default AI test-launch rule

When you launch Pickleball tests and the intended execution settings are known, run the project's wrapper (`mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`, or `java -jar` on the wrapper jar) and use `pkb_runvars` as the authoritative input. Pick the script from the OS. Do not require a machine-wide Maven or Gradle install. Put intentional tag/name selection, browser, evidence/logging controls, and other non-secret RunVar changes inside `pkb_runvars`; do not default to ambient optional project settings or separate JVM `-Dpkb_*` RunVars. Use `pkb_profile` or ordinary JVM RunVar overrides only when the task specifically tests those configuration semantics or the user asks for them. Keep protected secrets and diagnostic lineage outside `pkb_runvars`.

For an agent's bounded confirmation (not the human runner defaults), include diagnostic evidence controls, keep the project browser (`CHROME_HEADLESS` only when the project names none), and high parallelism when more than one scenario will run. Edit those values in the run workspace. Documented keys:

```text
pkb_browser=<browser ladder>
pkb_parallel=<conservative JVM estimate or auto>
pkb_reportingmode=diagnostic
pkb_loglevel=warn
pkb_reportretention=failed
pkb_compositeReport=false
pkb_scenarioReport=false
```

Use the narrowest `pkb_tags` / `pkb_name` / `pkb_example` that isolate the failure. `pkb_example` selects Examples rows after tags and name. It is not a tag. A normal Scenario counts as row 1, and `--example='1 2 5 3.4 7-11'` is the list form. Do not add the `pretty` plugin; it is console noise for agents. Discover defaults to `pkb_reportretention=failed`, which keeps dense evidence for failing scenarios and does not retain it for passing ones. Override with Workbench `--retention=all|failed|none`. Workbench `hint` prints the estimated integer `pkb_parallel` and the selected browser for the current project/JVM. `pkb_parallel=auto` also resolves to that estimate at run start and stamps the integer into `pkb_run_profile`.

Local Chrome and Edge do not need a `driverExecutable`. Selenium Manager runs first (`pkb_driver_download_native=false` stops that network use for the launch). If the manager cannot fetch a driver, a best-effort download may cache one. Discovery, a 403, a certificate failure, or an antivirus lock is a log line, not a test error. Do not treat that log as a product failure, and do not add a proxy example under `configs`. `pkb_driver_download_proxy=false` turns the fallback off. A URL in that property is redacted in `pkb_run_profile` and diagnostics. Remote browsers are unchanged.

These are documented agent defaults, not `PickleballTests` human defaults (`pretty`, `@all`, often headed Chrome). Example confirmation after Discover:

```text
mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=confirm --tags=@the-failing-tag --name='The failing scenario'"
```

After any diagnostic run, read `pkb_run_profile` from the pack. That is the complete resolved RunVar list, including inherited execution-context paths and the integer parallel count. Do not treat omitted workspace keys as equal to project `pickleball.properties`. Confirm and isolate launch the workspace. They do not pass `-Dpkb_runvars` or `-Dpkb_overriderunvars`. Neither silently re-resolves from project defaults, and neither supplies `pkb_run_profile` as input. `pkb_overriderunvars` is deprecated and remains only for a plain run that does not set `pkb_workspace`.

A selected profile or partial `pkb_runvars` input inherits only missing project execution-context RunVars:

```text
pkb_glue
pkb_features
pkb_datapath
pkb_callpath
pkb_componentpath
pkb_configpath
```

Optional RunVars such as browser, tags, reporting, logging, and ReportPortal values do not leak into a controlled run merely because normal configuration contains them.

Explicit JVM `-Dpkb_*` RunVars are different from ambient project defaults: they are intentional runtime overrides and remain active with a selected profile or controlled run. With a selected profile they override the same profile key; with `pkb_runvars`, the controlled value wins any conflict.

For those six inherited keys, missing means inherit, nonblank means override, and blank/null means suppress inheritance. A blank remains a blank tombstone in the retained final `runProfile`, because replaying that blank is what tells the underlying Pickleball subsystem to use its historical fallback behavior instead of re-inheriting a project value.

`pkb_configpath` selects the source loaded beneath the configuration mapping. Prefer `<config:...>` references such as `<config:URL.forms>`; legacy `<configs...>` references remain supported. Missing/blank `pkb_configpath` uses the classpath resource `configs`. Runtime config data cannot resolve profiles, `pkb_runvars`, or the path that loads the configs themselves. A normal run and a private-run copy resolve the same value to the same folder. A root `configs/` directory is chosen when `wd-file:` points at it.

Changed in 2.1.15. `pkb_features`, `pkb_datapath`, `pkb_configpath`, and `<file:...>` use one prefix rule. A path with no prefix is a classpath resource, including a path that contains a slash. `qa/configs` is the resources folder. `wd-file:src/test/resources/qa/configs` is that folder under the working directory. `wd-file:/opt/...` is an absolute directory. `file:/opt/...` is the classpath resource `opt/...`. There is no project-root prefix. A `file:/...` or unprefixed `/...` path that is not on the classpath fails with a hint: use `wd-file:/...` for a filesystem path. A relative miss and a missing `wd-file:` path stay empty. The default `pkb_datapath` is `data`. `wd-file:features` is passed to Cucumber as `file:features`. `pkb_callpath` and `pkb_componentpath` keep their previous path rules.

| Prefix | Meaning |
|---|---|
| (none) | resources folder (classpath) |
| `file:` | resources folder (classpath), same as no prefix |
| `classpath:` | resources folder (classpath) |
| `wd-file:X` | `<working directory>/X` |
| `wd-file:/X` | absolute path `/X` |

## Diagnostic investigation protocol

When Pickleball diagnostic evidence exists, use the shallowest evidence layer that completely answers the question. Do not advance to denser evidence merely because it exists.

For AI-controlled diagnostic runs, keep terminal logging minimal. Diagnostic mode captures TRACE-through-ERROR evidence independently of console `pkb_loglevel`, so prefer `pkb_loglevel=warn` or `error` when appropriate. Use terminal output primarily for Maven, compilation, JVM, dependency, command-line, or other startup failures; use structured diagnostic artifacts after the run begins successfully.

Use this escalation order:

1. `run-catalog.json` to choose relevant runs. Each catalog entry includes the retained `pkb_run_profile` when present.
2. Selected `run-index.json` and `clusters.json` for outcomes, scenario identity, failure grouping, capabilities, retention, step rollups, the complete `runProfile`, profile fingerprints, and representative visual references.
3. Selected scenario `summary.json` when additional sparse detail is needed, including the same `runProfile`.
4. Relevant `events.jsonl` only when exact step/lifecycle/order/INFO+ detail remains unanswered.
5. Existing `comparisonToPrevious` or Pickleball run/fingerprint comparison before opening screenshots.
6. A representative PNG only when semantic visual meaning must be understood.
7. `trace.jsonl.gz` or interrupted `trace.jsonl` only when structured/INFO+ evidence is insufficient.

Stop reading as soon as the current layer answers the investigation. Do not recursively ingest an entire diagnostic run.

From a live Workbench session, use `workbench_diagnostic_catalog`, `workbench_diagnostic_run`, and `workbench_diagnostic_summary` for layers 1–3 instead of globbing `reports/diagnostic-runs`. Those tools return sparse JSON only and do not dump `events.jsonl`, traces, or screenshot bytes.

After isolation and the diagnostic rerun, emit a small human handoff. JSON is the source of truth; HTML is a local render of that JSON plus at most two screenshots linked from the existing diagnostic pack. Do not copy the diagnostic run into `.pickleball/investigations/`. Do not embed PNG bytes in investigation JSON. Do not create a Git repo under `.pickleball`. Do not `git checkout` or reset the consumer HEAD from Workbench.

### Bottom-line chat after emit

Every human-facing explanation of a Pickleball test run ends with a **bottom line**. Humans must not scroll a long trace to reconstruct what happened.

Mandatory reading order (same order in chat after emit, `report.html`, Workbench Report tab, and this guide):

1. **Gherkin / business language** — what the scenario was trying to do, what went right or wrong, in user terms.
2. **Where** — parent scenario `feature:line`, then nested COMPONENT / CALL / data files.
3. **Cause vs failed assertion** — if an earlier step created the bad state, that earlier step is the cause.
4. **Then** lower-level Java, JSON/YAML, HTTP, git, environment.

After `workbench_investigation_emit`, print this block — not a dump:

```text
Bottom line: <one sentence in Gherkin/business language>
Where: <feature:line Scenario "Name">
        → COMPONENT "…"  <path:line>
        → data  <json-or-yaml-path>
Cause: <originating earlier step if cascade, else the failed step> — <why>
Git:   <path @ commit date by author — suspect / not a suspect / not tested / not checked>
Next:  <fix proposed | not fixed | needs a human decision>
Report: .pickleball/investigations/<id>/report.html
```

If a UI session is attached, at most two `wb://` links may follow. If no UI, omit `wb://`. No MCP transcripts, TRACE dumps, or PNG analysis in chat.

### Peek vs Play

| Action | Effect |
|---|---|
| Picker “Play this scenario” | Replace the live Gherkin buffer. |
| Explorer / Report / `workbench_go` from a **retained run** | Open a **peek** tab. Prefer the pack-local `source/files/` copy. Do **not** replace the live buffer. Do not write. |

Java from a retained run: show the Step definition panel first. If `definition.sourcePath` is a real **consumer** file, open a **read-only Workbench text tab**. Never open framework sources from the JAR. Paths stay inside the consumer project. A missing target is a visible miss, not a blanked live editor.

A local “revert” hypothesis pastes the pack copy into the live buffer and uses `execute-step`. Do not Save. Do not change HEAD.

### `workbench_go`

One navigation tool. Do not add generic IDE, git, or process MCP tools.

```text
workbench_go(link)
```

`link` fields: `to` (`explorer` | `editor` | `mapping` | `report` | `terminal`), `runId`, `eventSeq`, `nodeId`, `path`, `line`, `column`, `kind` (`feature` | `component` | `step` | `call` | `data` | `java`), `mapReference`, `key`, `investigationId`, `section`, `label`.

The same fields parse from `wb://explorer?run=&seq=&node=` (and the other `to` values). UI attach plus a control lease are required to move the window. Headless: validate and echo the resolved target. `label` may feed `workbench_set_current_action`. The tool does not write files. Report clicks, explorer Open, and MCP share one Java resolver.

### Source pack (read, do not check out)

Dense retained runs copy only the files that scenario actually used under `reports/diagnostic-runs/<run>/source/`:

```text
referenced.json          # path, role, sha256, vsHead: clean|dirty|untracked|no-git
referenced.patch.gz      # filtered `git diff HEAD -- <those paths>` when dirty
files/<project-relative> # full copies of the allowed set (even when they match HEAD)
live-buffer.feature      # only if isolate ran unsaved Gherkin that differs from disk
```

Allowed set: used `.feature` files (parent plus nested components named by events); consumer Java **step definition** sources named by `definition.sourcePath` (not helpers, not framework); JSON/YAML the scenario actually accessed.

Keep existing `source-provenance.json` (`pkb_gitsnapshot=metadata|diff|none`, default `metadata`). Do not use whole-tree `consumer-working-tree.patch.gz` as recovery. There is no fourth `pkb_gitsnapshot` mode and no shadow git under `.pickleball`. Flush is `pkb_reportretention` / delete the run folder.

Agent git suspects are a local procedure against the consumer repo (`git log -n 5 -- <path>`). There is no MCP git tool. Workbench never checks out the consumer repo.

## Visual evidence rules

- Never open a PNG merely to determine whether two screenshots differ.
- Prefer already-recorded `comparisonToPrevious` for adjacent screenshots.
- For cross-run or explicit comparison, use `DiagnosticCli`, `DiagnosticRunComparator`, or `VisualFingerprintComparator`.
- Do not manually decode `.pkbf` files or invent another image-comparison algorithm.
- `decodedPixelsExactlyEqual=true` establishes rendered-pixel equality.
- `IDENTICAL` ends a visual-difference investigation unless the image itself is required.
- Other similarity categories establish that pixels differ and their magnitude; open a representative PNG only when interpreting what visibly changed matters.
- Raw PNG byte inequality does not prove rendered pixels differ.

## Diagnostic utility commands

The agent-facing name is Workbench. From a consumer project where Pickleball is on the test classpath, use the project wrapper (`mvnw` or `mvnw.cmd`) and the same launcher as the project pointer. Only change `-Dexec.args`. Do not require a machine-wide Maven or Gradle install.

```text
mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"
"-Dexec.args=hint"
"-Dexec.args=discover [--tags <expr>] [--name <expr>] [--example <rows>]"
"-Dexec.args=confirm [--tags <expr>] [--name <expr>] [--example <rows>]"
"-Dexec.args=isolate [--example <rows>]"
"-Dexec.args=execute-step --text='Given stay'"
"-Dexec.args=status"
"-Dexec.args=events"
"-Dexec.args=stop"
```

Workbench `export-guidance` copies bundled guidance through JDK-only `PickleballLocalStore` (also used by `java -jar pickleball-<version>.jar export-guidance .pickleball`). `DiagnosticCli.export-guidance` delegates to that store. Hint and the comparison/rebuild utilities still go through DiagnosticCli on a full consumer classpath:

```text
DiagnosticCli guidance
DiagnosticCli export-guidance [output-directory]
DiagnosticCli discover-hint [project]
DiagnosticCli resolve-runvars [project]
DiagnosticCli emit-investigation <investigation-json-or--> <consumer-project-root>
DiagnosticCli compare-runs <left-run-index> <right-run-index> [output-json]
DiagnosticCli compare-fingerprints <left.pkbf> <right.pkbf> [output-json]
DiagnosticCli rebuild <diagnostic-runs-root-or-run-root>
```

`DiagnosticCli help`, `--help`, and `-h` print that DiagnosticCli list and state that Workbench is the agent entry.

Use Workbench `export-guidance` to materialize the complete version-matched documentation plus curated Maven consumer reference, `hint` for the workspace seed and `-Dpkb_workspace` launch keys (not a recommended `pkb_runvars` string), `workspace-show` / `workspace-edit` / `workspace-seed` to edit the run workspace, `discover` / `confirm` to find failures, and `isolate` / `execute-step` / `status` / `events` / `stop` for live debug. `CHROME_HEADLESS` is only the fallback when the project names no browser. `emit-investigation` writes `.pickleball/investigations/<id>/investigation.json` and `report.html`. After emit, print the six-line bottom-line block and the relative HTML path. Use `workbench_go` only to navigate an attached leased UI or to validate a target headless; it does not write files.

## Controlled diagnostic reruns

When an investigation requires a rerun and the intended execution settings are known:

1. Start from the selected run's retained `runProfile` in `run-index.json`.
2. Replay that retained final RunVar set through compact `pkb_runvars` or expanded `pkb_runvars.<pkb_var>` members.
3. Never mix compact and expanded `pkb_runvars` forms. Never supply `pkb_run_profile` as input.
4. Preserve explicit blank assignments from the retained profile.
5. Change only RunVars required by the current hypothesis.
6. Do not reconstruct optional effective RunVars by manually combining defaults, property files, profiles, system properties, and Cucumber aliases when a retained profile is available.
7. Supply diagnostic lineage separately through `pkb_investigation_id`, `pkb_run_purpose`, `pkb_parent_run_id`, `pkb_baseline_run_id`, and `pkb_changed_variables`.
8. Treat lineage as descriptive investigation context, not proof of an execution/source difference.
9. Use `pkb_changed_variables` only for canonical execution RunVar names intentionally changed, such as `pkb_browser` or `pkb_tags`. Do not put source paths, feature files, commits, test-data changes, reasons, profile controls, or derived fields in `pkb_changed_variables`.
10. If source changes but final execution RunVars should remain identical, omit `pkb_changed_variables` and describe the goal in `pkb_run_purpose`.
11. Evidence/logging controls such as `pkb_reportingmode`, `pkb_reportretention`, `pkb_diagnostic_output`, `pkb_platformlog`, `pkb_gitsnapshot`, and `pkb_loglevel` are RunVars; declare them when intentionally changed.
12. After the rerun, verify `runProfileFingerprint`, compatibility field `directRunProfile`, and actual source/comparison evidence before attributing differences.
13. Use `runProfileFingerprint`, not `configurationHash`, as the equality signal for the final RunVar set.
14. Never expand protected values into logs, prompts, committed files, or diagnostic evidence.
15. To freeze a Workbench run, seed the workspace, edit `runvars.properties` and `workspace/configs`, then launch. `pkb_overriderunvars` is deprecated. It still works for a plain `mvn test` that does not set `pkb_workspace`. Absent that input, and absent `pkb_workspace`, resolution is unchanged. Do not mix `pkb_overriderunvars` with `pkb_runvars` or a composing `pkb_profile`. Compare `runProfileFingerprint` after the run. The workspace fingerprint is `workspaceFingerprint` on `record.json`.

Example controlled replay:

```text
-Dpkb_runvars="<retained runProfile with only intended edits>"
-Dpkb_investigation_id=<investigation>
-Dpkb_parent_run_id=<parent>
-Dpkb_baseline_run_id=<baseline>
-Dpkb_run_purpose=<hypothesis>
-Dpkb_changed_variables=pkb_browser
```

See `docs/ai-run-configuration.md` for the full profile/RunVar contract and `docs/diagnostic-lineage-metadata.md` for lineage semantics.

## Pickleball syntax documentation

The exported `docs/` tree is the version-matched reference for all supported Pickleball behavior and syntax. Open a specific guide when the live loop or a diagnostic layer requires that topic; do not start by reading `docs/README.md` as a dump. Its links to the working consumer resolve into the exported `maven-consumer-project/` reference snapshot. In particular:

- dynamic Gherkin/action/assertion syntax — `docs/dynamic-steps.md`;
- element vocabulary/selectors — `docs/custom-element-definitions.md`;
- mappings/templates — `docs/mapping-and-templating.md`;
- Data Elements and values — `docs/data-values-and-elements.md` and `docs/data-element-query-runtime.md`;
- reusable component scenarios and canonical `RUN` authoring — `docs/component-scenarios.md`;
- service calls and `RUN`/`CALL:` result semantics — `docs/service-call-scenarios.md`;
- nested flow and conditionals — `docs/nested-steps.md`, `docs/block-conditionals.md`;
- reserved element names, Step Repetition, and Step Duration — `docs/reserved-element-names.md`;
- keyboard expressions — `docs/key-parser-dsl.md`;
- execution/configuration/profiles — `docs/configuration.md`, `docs/ai-run-configuration.md`;
- resource/config mapping — `docs/config-files-and-resource-mapping.md`;
- Cucumber compatibility — `docs/cucumber-compatibility.md`;
- Workbench live session, skip / resources-only sync, and the live worker — `docs/pickleball-workbench.md`;
- diagnostics and lineage — `docs/diagnostic-reporting.md`, `docs/diagnostic-lineage-metadata.md`.

Do not guess Pickleball syntax when the version-matched guide or executable consumer reference can answer it.

## Human-readable consumer guidance

Use `docs/consumer-project.md` on demand for the Maven consumer layout, local test site, common tag entry points, diagnostic usage, and example commands. Human readers can start with `docs/README.md` and open files under `maven-consumer-project/` in the IDE to inspect the version-matched working features, configuration, calls, data, runner, and test-site examples. Agents should not treat those as first-read.

## When the core Pickleball repository is also present

If the consumer is nested inside the Pickleball source repository, repository-level `AGENTS.md` may impose additional maintainer rules for framework changes. Those core-maintainer rules are additive and do not replace this consumer-facing contract.

For a normal external consumer, do not assume those core files exist.

## Maintainer-only: pointer-eval harness

This section is for Pickleball maintainers. It is not the product suite and is not first-read for consumer agents.

Pickleball's example Maven consumer includes an opt-in mixed pass/fail suite tagged only `@agent-pointer-eval` (`maven-consumer-project/src/test/resources/features/agent-pointer-eval.feature`). It is not part of `@all`, `@regression`, or the other Maven suite-profile tags. Do not add `@all` to that feature. The failures are intentional canned fixtures for scoring whether a consumer AI agent follows the short `AGENTS.md` pointer into this guide and then uses Workbench discover-then-confirm (and isolate/execute-step when live debug is needed). Do not treat those failures as product bugs, and do not "fix" the feature unless a human asked to change the harness. During an eval, still follow Discover / Confirm / live debug. Do not ignore canned fails.

Run it explicitly:

```text
mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=discover --tags=@agent-pointer-eval"
```

or `-Dpkb_runvars="pkb_tags=@agent-pointer-eval, pkb_browser=CHROME_HEADLESS"`. Do not add this tag to consumer `AGENTS.md` or Copilot pointer files.
