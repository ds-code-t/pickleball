# Pickleball Workbench

Pickleball Workbench is the external controller for interactive Pickleball execution and investigation. Its executable contains controller code, GUI/MCP adapters, synchronization support, JSON transport, and the neutral wire protocol—but no Pickleball core/runtime. Real execution occurs only in a separate consumer worker using the consumer project's compiled output and resolved test runtime.

Workbench replaces the former Pickleball Studio application. The supported architecture is deliberately execution-oriented: project synchronization, a persistent consumer worker, live runtime control, Mapping, browser/service evidence, semantic breakpoints, Step Override authoring, lightweight non-Spring MCP stdio, and a Swing UI over the same service seam.

## Architecture

Source dependencies and distribution are strictly separated:

```text
pickleball core/worker --------> JDK-only control protocol
pickleball-workbench ----------> JDK-only control protocol
published pickleball JAR ------> opaque Workbench executable bytes
```

The distribution arrow is an assembly input, not a Workbench-to-core Java dependency. **Pickleball may contain Workbench; Workbench must not contain Pickleball.** Separate JVMs are required, but they are not sufficient: dependency graphs, class visibility, JAR entries, nested JARs, service providers, and runtime origins are checked too.

Pickleball owns scenario execution semantics, Cucumber integration, DynamicControl/Gherkin execution, Mapping, browser/service behavior, the consumer-side Control Bridge, semantic hooks/breakpoints, Step Overrides, and woven Cucumber/AspectJ behavior.

Workbench does not implement Dalton. The Dalton tab, the MCP tool `dalton`, and `DaltonCliForwarder` only build `java -jar <pickleball.jar> dalton ...`. The tab browses files, features, scenarios, steps, entities, and run history, shows conflicts, and runs status, hydrate, and extract. Feature text is saved with `dalton feature put --file`. An entity is saved with `dalton entity put --payload-file`. The Run button forwards `dalton run` and does not start a second engine. Labels are the human text (`edited=yes`, `scenarios=2`, `Entity Type id version N`). The tab does not open SQLite and does not load Dalton classes. Gradle `daltonStatus`, `daltonHydrate`, `daltonExtract`, `daltonFeature`, `daltonQuery`, `daltonHistory`, and `daltonRun` do the same forward. Diagnostic reports stay on the existing reporting path.

Workbench owns synchronization, `.pickleball/workbench/` disposable state, worker lifecycle, the protocol client, `WorkbenchLiveSession`, `WorkbenchServices` / `WorkbenchController`, MCP stdio, the headless live-scenario presentation model, and the Swing adapter. It does not import the worker entry point; it launches the protocol's class-name string on the captured consumer classpath.

`pickleball-control-protocol` owns immutable wire records, request/response envelopes, transport constants, capabilities, version/minimum-version negotiation, and JDK-only `.pickleball` layout/store helpers (`PickleballLocalLayout`, `PickleballVersion`, `PickleballArtifactLocator`, `PickleballLocalStore`). Worker-side bridge server/coordinator/bootstrap and all translation to runtime operations remain in Pickleball core.

MCP and Swing are adapters over the same Workbench service seam. They must not introduce a second runtime implementation. A visible UI keeps one Workbench JVM and one consumer worker. An AI agent attaches to that live session through a localhost HTTP JSON facade; it must not start a second Workbench or a second worker. `open-window` posts `open-ui` to a healthy `cli-session` and starts Swing in that controller JVM. A second `ui` process starts only when that post does not report `playback=OPENING`.

The canonical worker bridge environment is:

```text
PKB_CONTROL_BRIDGE_SESSION_DIR
PKB_CONTROL_BRIDGE_SESSION_ID
PKB_CONTROL_BRIDGE_TOKEN
PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO
```

Pickleball may accept `PKB_STUDIO_BRIDGE_*` as deprecated compatibility input aliases only. Workbench emits only the neutral names.

## Launch from a consumer project

The normal `tools.dscode:pickleball:<version>` test dependency already carries the matching controller at:

```text
META-INF/pickleball/workbench/pickleball-workbench.jar
```

Run the small launcher from the consumer test classpath. For Maven consumers, this command requires no cache path, separate Workbench dependency, or separately selected version.

Workbench is the consumer AI-agent front door. Agents use `export-guidance`, `hint`, `discover`, and `confirm` to find failures. When a Workbench window is already open, the same session commands (`open-scenario`, `example`, `play`, `execute-step`, `stop`, `diagnostic-run`, and the rest of the table below) drive that session so the person sees the result. When nobody is watching, `isolate` / `execute-step` / `status` / `events` / `stop` stay the headless path. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI.

Run the project's wrapper. Pick the script from the OS: `mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`, or `java -jar` on `.mvn/wrapper/maven-wrapper.jar` or `gradle/wrapper/gradle-wrapper.jar`. Do not require a machine-wide Maven or Gradle install. Discover, confirm, and sync invoke that wrapper themselves. MCP and the launcher stay Pickleball actions. There is no general `mvn` or `gradle` tool.

```bash
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher \
  -Dexec.classpathScope=test \
  "-Dexec.args=export-guidance .pickleball"
```

```powershell
.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"
```

```bash
"-Dexec.args=hint"
"-Dexec.args=discover --tags=@smoke"
"-Dexec.args=discover --tags=@smoke --retention=all"
"-Dexec.args=confirm --tags=@smoke --name='The failing scenario'"
"-Dexec.args=confirm --tags=@smoke --example='1 2 5 3.4 7-11'"
"-Dexec.args=isolate --example=1.1"
"-Dexec.args=execute-step --text='Given stay'"
"-Dexec.args=short-log"
"-Dexec.args=note --text='row 2 failed closed'"
"-Dexec.args=inbox --write --to=any --text='window is taken'"
"-Dexec.args=inbox --take --agent=agent-1"
"-Dexec.args=finish --run-id=<id> --learned='the empty row fails'"
```

Same launcher; only change `-Dexec.args`. When nobody is watching, `isolate` starts a detached headless session and later execs are one-shot HTTP clients. Isolate selects one scenario. It does not force `pkb_parallel=1`. When a window is already open, only one agent drives it and `isolate` does not start a second session. One window has one driver. Agents are not daemons, and each agent uses its own run id. Read the short log `.pickleball/agent-log` before a run, after a run, and during a long session, instead of the dense diagnostic log. Use the inbox only for a short note to one named agent. Taking that note deletes it. A note for every agent is a post (`post --write --to=all`), not an inbox file, and listing or taking does not delete it. Presence, posts, and `history.log` stay separate from the inbox. Two headless isolates do not share a session file; each file lives under `.pickleball/runs/<run-id>/session/`. `status`, `events`, and `execute-step` with no `--run-id` use the only live session there. Several live sessions must pass `--run-id`. `events` defaults to step and phrase lines; `--hooks` is the raw stream. A failed `execute-step` is JSON with `text`, `phrase`, `message`, and `stackHead`. `--text-file` and `--text-b64` keep inner quotes. `session list` prints a TSV header. `elapsed-step-ms=idle` when no step is running. `finish` and `stop` do not delete the run directory, the profile, the session file, or the log. `close-window` keeps that behavior. New session, attach, last-discover, logs, and `live/classes` live under `v/<current>/workbench` when `current.json` is complete. `.pickleball/workbench/` is only the fallback when that pointer is missing or not usable.

`hint`, `discover`, `confirm`, and `isolate` accept `--example` / `--example=` the same way they accept `--tags` and `--name`. The value is a selection key on that launch (`pkb_example`). It is not written over the workspace file. That is the headless way to keep specific Examples rows. A normal Scenario counts as row 1. `pkb_example` is not a tag. `--example='1 2 5 3.4 7-11'` keeps those rows, in source order, in each scenario that tags and name already selected. The Play row picker calls the same `example` filter. Play shows the first source-order match. Headless `pkb_example` still runs every match. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test.

`discover`, `confirm`, and `isolate` also accept repeatable `--runvar key=value` (or `--runvar=key=value`). A key written as `environment` is stored as `pkb_environment`. The value is merged into the planned run vars. If that key already has a different value, Workbench warns and the command-line value wins. `record.json` `environment` is that resolved value, or the token `unset` when `pkb_environment` was not set. `browser` is the resolved browser. The summary line ends with `environment=` and `browser=` when those values are set. Trust those fields, not a launcher JVM preview. A blank old run var in an edit warning is shown as `""`. `workspace-show` prints `source=hook|properties|default|cli/override` on every value. ` computed` means the value came from a runner hook and needs a reseed.

## Selection, timeouts, and side reports

These commands stay on the Workbench launcher. They do not change a normal `mvn test`, `pkb_stepMaxTime`, `CucumberScanUtil.listPickles`, or `DiagnosticReporter`.

When the feature index can narrow a `discover` or `confirm` selection, only the top-level Cucumber discovery is narrowed. The matching paths go to `-Dpickleball.workbench.topFeatures`. They are not written over `pkb_features`. Nested `SCENARIO` / `COMPONENT` / `CALL` / `RUN` still call `CucumberScanUtil.listPickles` with the configured feature set, so a scenario in another file is found, including a path with spaces. `--no-narrow` and `--full-scan` skip that top-level list. A normal `mvn test` does not set the property. A full scan is the fallback for an unreadable feature or an unsupported tag such as `xor`, and that fallback still enumerates every scenario. Nested lookup no longer needs it.

The index cache file stays `feature-index.v3`. The record version inside that file is 4 (own tags and feature tags). An older record is ignored and parsed again. Beside it, `feature-index.v3.stat` stores each file's size and last-modified time. A warm select does not read the feature bytes when both still match. A mismatch hashes the bytes, and a different hash reparses. `--refresh` ignores the sidecar. A select that changes nothing does not rewrite the index. `plan`, `discover`, `confirm`, and the feature catalog all read that index. The catalog does not walk the tree or parse Gherkin again; it copies scenario text from the files the index already named. `selectMs` is how long that read took. Discover passes only the matching feature URIs to Cucumber (`prefilteredFeatures`); a spilled `@top-features.txt` list stays a file argument. `CucumberScanUtil` is not replaced. A Rust sidecar was measured on the consumer project (56 files, 405 scenarios): 139 ms cold, 18 ms warm. `scripts/time_feature_scan.py` repeats that whole scan. It is not shipped. `WorkbenchFeatureIndex.IMPLEMENTATION` is `java`. Discover and confirm append `workbench launch jvm=launcher|test stage=<name> elapsedMs=<n>` to `runs/<id>/launch-timing.txt`, and the `index` stage includes `selectMs`. That line stays off stderr unless `PKB_WORKBENCH_TIMING=1` or `-Dpkb_workbench_timing=true`. The launcher clock and the test JVM clock are separate. A normal `mvn test` does not print them. A narrowed path list longer than 4,000 characters is `top-features.txt`, and Maven is given `@` plus that file so a Windows command line can still start. The test JVM reads the file. A shorter list stays inline.

Maven Surefire discovers, then discovers again, then executes. The second discovery in that fork reuses a feature whose size and last-modified time have not changed, and it reuses the already filtered suite when the selectors and configuration are the same. Execution drops that copy. That reuse is not narrowing, and a normal `mvn test` still selects the configured feature set. On a synthetic 1,800-file / 36,000-scenario project the warm Maven launch reached the first scenario at 6.4 s of fork time (14 s wall) instead of 7.2 s (17 s wall), with the same 36,000 tests and 35,999 skipped. The release notes have the stage times.

`xor` is not a Cucumber tag operator. The warning prints the escaped form that parses, `--tags=@smoke\ xor\ @wip`. Maven receives `(A or B) and not (A and B)` in `pkb_tags` only. The index parser is unchanged.

`pkb_example` decodes a classpath feature URI before it opens the file. `%20` is a space, and a Windows `\` is a `/`. One unreadable feature logs `WARNING: pkb_example skipped unreadable feature` and is left out of that filter. It does not abort the rest of the run.

`record.json` takes a failed outcome from `run-index.json` when that file exists, including `counts.errors` and outcome `ERROR`, and including `diagnostic/run-index.json`. `failureCount`, `failedScenario`, and `failureSummary` are filled from it. A full scan with no scenario directory prints `scenarios=` and `steps=` from that index, or from the console `Scenarios` / `Steps` lines. When scenario directories are present, `steps=` sums every scenario for retention `all`, `failed`, and `none`: the step list size when that list is not empty, otherwise the core summary `steps.executed` (then `total`, then `passed` + `failed` + `skipped` + `other`). The index is used only when that sum is 0.

```text
"-Dexec.args=plan --tags=@smoke --name='Checkout.*'"
"-Dexec.args=discover --plan --select=src/test/resources/features/checkout.feature:42"
"-Dexec.args=discover --tags=@smoke --tags-file=tags.txt --agent=agent-1"
"-Dexec.args=discover --tags-b64=<base64>"
"-Dexec.args=confirm --tags=@smoke and not @wip"
"-Dexec.args=execute-step --text='Given stay' --step-timeout=30 --run-timeout=120"
"-Dexec.args=execute-step --stop-after='the anchor'"
"-Dexec.args=stop --force"
"-Dexec.args=inspect-page --html=page.html"
"-Dexec.args=find-element --category=Button --text=Save --html=page.html"
```

A `--tags` expression with spaces has to be one argument. Maven `exec` and PowerShell split an unquoted value, so `--tags=@A and not @B` arrives as `--tags=@A` plus `and`, `not`, and `@B`. The launcher exits non-zero before `plan`, `discover`, `confirm`, `isolate`, or any other `--tags` command runs. The error shows `@A and not @B` and says to quote it (`--tags='@A and not @B'`) or use `--tags-file` / `--tags-b64`. A later option such as `--run-id=x` is not part of the expression. A quoted `--tags` value, `--tags-file`, and `--tags-b64` stay as they were. This check is the Workbench command line only. It does not change Cucumber tag parsing or a normal `mvn test`.

`plan` and `--plan` resolve tags, name, example rows, and `file.feature:line` and print each hit. They do not compile and do not open a browser. An empty selection warns. The feature index treats `Example:` as a scenario and exact `Scenarios:` as an examples table, skips docstring bodies, and always includes a file it cannot model, including a `# language:` other than `en` or a real Gherkin keyword from another dialect, without phantom hits. A free-text description such as `Note:` is not a keyword and does not force inclusion. The cache schema is `feature-index.v3`. `discover` and `confirm` that select nothing print `status=EMPTY` and exit non-zero. They do not report `PASSED`. `discover` and `confirm` reject `--step-timeout` (`use --run-timeout; per-step limits apply to execute-step`). `--run-timeout` on those commands records `TIMEOUT`. `--step-timeout` applies to `execute-step` and is measured from the call. `--run-timeout` on `execute-step` is measured from the run's `startedAt`. A timed-out step is still running; use `stop --force`. A timed-out `discover` or `confirm` kills the wrapper process tree (descendants first, then the process) and waits up to 10 seconds. `stop --force` uses the same kill and records `STOPPED`. Cooperative `stop` still waits. `status` and `events` print `sleep-gap=<milliseconds>ms at <instant>`, or `sleep-gap=none`. A sleep-gap is a machine sleep or stall the session heartbeat detected (a beat every 5 seconds, recorded when the wall gap exceeds 30 seconds). It is not a Gherkin sleep step, and spacing between events does not create one.

The shared `last-discover.json` is whichever discover finished last. `--agent=<id>` also writes `last-discover-agent-<id>.json`, and the run writes `last-discover-run-<id>.json`. Confirm without `--agent` still reads the shared file. Discover reports that run’s profile.

Maven `target/` and Gradle `build/` are generated sync input, as is `live/classes`. Do not hand-edit them.

`pkb_workbench_worker_start_timeout` (system property, `PKB_WORKBENCH_WORKER_START_TIMEOUT`, dotted `pkb_runvars.pkb_workbench_worker_start_timeout`, or a compact `pkb_runvars` member) defaults to 300 seconds. A blank, non-numeric, or non-positive value stays 300. Workbench reads it for the consumer worker start and for the `isolate` / `session-start` health wait. The forked controller is started with that same `-Dpkb_workbench_worker_start_timeout` so the worker wait matches the health wait. `open-window` still waits 60 seconds and does not use this setting. A normal `mvn test` does not read it. `copy-base`, `copy-live`, and `digest` print `files=`, `bytes=`, and `ms=` between `materialize` and `hash`. `copy-live` installs one relative directory symlink from `live/classes` to `base/classes` when the filesystem allows it. Windows tries that symlink, then a junction at the final base, then the per-file path. The per-file path hardlinks each file on the same thread pool as the copy path, and copies that file when the link fails. A provider that refuses links copies the rest of that tree without trying another link. Any other link error copies that file only and later files still try a link. The stage line still prints `links=<n> copies=<n>`. A directory link counts each file as a link and copies nothing. A save of a file under that link copies the tree into a real `live/classes` directory first, so the write does not change `base/classes`. `base/classes` is never a classpath entry. After `digest`, the controller writes `progress stage=worker-spawn` on the worker stderr log immediately after the consumer process starts. The worker writes `progress stage=jvm-main` as the first line of main, then `progress stage=classpath-scan` before classpath and glue initialization. The first successful CLI `GET /health` writes `progress stage=worker-ready` on the session log. `paused` still also records `worker-ready` on the worker log. `base/classes` remains the owned snapshot. Do not edit a linked file under `live/classes`; writes belong in `generated-classes`. The class-file fingerprint is the relative path, size, and mtime. It does not read class bytes. Jars still use the racy-window content hash. A later isolate of the same run id moves `session.log` and `startup-progress.json` to `runs/<id>/session/attempts/<n>/` before it truncates them. `n` uses the same next-attempt count as a discover diagnostic archive, in this session directory. `isolate` and `session-start` with `--run-id` and no workspace file print `error: no workspace for run id <id>` and `fix: Run workspace-seed or discover for that run id.` They do not start the controller. If the process still exits early, the parent prints the last error line from the new session log as `error:` and `fix:`. `validate` rejects a flag it does not use, including `--quiet`, with `error:` and `fix:`. `discover` and `confirm` still accept `--quiet`. A Linux unit materialize of 5259 class files (199252992 bytes) on JDK 21 with 2 processors took 576 ms (copy-live 32 ms, one directory symlink, links=5259 copies=0). The same tree's per-file link bench was 77 ms serial. A preferred-root runner and glue scan reads class-file headers and loads only the one concrete runner; jars still load classes. Windows fresh-cache isolate was not rerun. Replacing the projected Windows parallel copy-live of about 6.6 s with one junction leaves wall near 69 s until that isolate records `worker-spawn`, `jvm-main`, `classpath-scan`, and `worker-ready`. `validate --all` stays on hold and needs a controlled rerun.

While `isolate` / `session-start` is waiting, stdout prints a progress line about every 12 seconds: `session-start progress elapsed=<ms>ms stage=<stage>`. The stage is the latest `progress stage=` line in the session log or a worker `*.err.log` under the Workbench `logs` directory. Before any marker the stage is `starting / Maven resolve or feature index`. The controller then prints `index`, then `index-end`. A materializing cache hit prints `progress stage=sync`, `progress stage=materialize`, `progress stage=copy-base files=<n> bytes=<n> ms=<n>`, `progress stage=copy-live files=<n> bytes=<n> ms=<n> links=<n> copies=<n>`, `progress stage=digest files=<n> bytes=<n> ms=<n>`, and `progress stage=hash jars=<n> reused=<n> hashed=<n> bytes=<n>`. Jar identity is path, size, mtime, and file identity. A jar first seen while its mtime is still within 2 seconds of now is recorded with a content hash, which stays that jar's token. Bytes are read again when a later start is still inside that window, or when inode or ctime changed under the same size and mtime; once it is outside that window the same token is kept and its recorded time is refreshed, and `hashed` is how many. On Windows, fileKey() and ctime are not available, so the key is effectively path, size, and mtime plus the 2 s rule. When the pom hash still matches it also prints `progress stage=maven skipped=unchanged` and does not start Maven. An unchanged start prints `classpath-cache hashed=false` and does not hash them. Every `progress stage=` line in one read is kept, including `index-end`. A cache hit does not start Maven. A start that becomes healthy before the first interval stays quiet. A worker startup timeout still names elapsed time, stage (`compile`, `classpath`, `worker-launch`, `anchor-parse`, `paused`), and the last worker output. A session-start health timeout names the stage, the elapsed time, per-stage durations, the last 20 lines of the relevant log, and how to raise the limit (`-Dpkb_workbench_worker_start_timeout=N`, the environment variable, or the `pkb_runvars` member). Stage enter times are also written to `runs/<id>/session/startup-progress.json`. `No healthy Workbench session` names `never-started`, `still-starting`, `crashed`, or `missing-session-file`. Start is logged only once the session is up.

Workbench writes `runs/<id>/workbench/summary.json` and `runs/<id>/workbench/trace-index.json` for that run, and the same summary beside each scenario directory. A pass whose `events.jsonl` was removed by `--retention=failed` still gets the file from the core `summary.json` (scenario name, status, and duration). Step text and the assertion result are included when `events.jsonl` is still there. It does not rewrite `events.jsonl`, `trace.jsonl`, or a core `summary.json` that is already there. Console text is `runs/<id>/console/<scenario>.txt`, copied from the wrapper stdout and stderr. A `Scenario:` or `Scenario Outline:` line is that scenario even when a `# file:line` location follows, including a Windows path or a path with spaces. Text that matches no scenario is `unassigned.txt`. A Workbench discover or confirm (`pkb_run_purpose` starting with `workbench-`) prefixes each console line with `# pkb-console ` and the URL-encoded scenario name, matched exactly, so `Pend` does not take a line from `Pend case`. That prefix covers `print`, `println`, `printf`, `print(char[])`, and `write(byte[])`, including a Logback or Log4j2 console appender that already captured the original stream, one print that contains several lines, and a thread started while the scenario is running. The saved console file keeps the text and does not keep the `# pkb-console` prefix. `unassigned.txt` is omitted when every line matched. A normal `mvn test` does not install that tap, so its output stays the same. One advice, `TestCase_ConsoleTap`, marks the scenario. The console file is not empty when the wrapper printed. `inspect-page` and `find-element` print the phrase or the XPath, raw count, and visible count. `--html=<file>` is the offline fallback and prints `source=offline-html`. That file is XML, including a HTML5 doctype; unclosed tags are rejected. Without `--html` the same commands ask the live session for a read-only worker count and print `source=live-session`. They do not click. No session prints `start a session first` and exits 2. `inspect-page` takes no category. If the live command is still queued or running after 30 seconds, or the session reports `TIMEOUT`, the command prints `status=TIMEOUT` and exits 3. It does not exit 0 with an empty hit list. Exit 1 is a failed query. The run config copy is a faithful copy of the project configs (real values, valid YAML) so the worker can load them. The project config is unchanged. Redaction for logs and reports stays a separate path. A directory install tries a locked delete and a locked move, then retries each after 100 ms, 300 ms, and 1000 ms. The copy to a sibling `target.tmp-<n>` runs only after those retries fail, with no further wait. A failed copy deletes the temp directory and leaves the original target absent or unchanged. A CLI error that wraps an `IOException` shows that cause.

`--retention=all|failed|none` is this discover or confirm only. The default for discover is `failed`. `gc-runs` is the separate payload sweep and is not run from `finish`.

## The window is a client

Every action that changes the open session already exists as a launcher command or a command-queue command. The Swing window calls that command. It does not keep a second implementation. Switching the Config tab is only a view change. It shows `.pickleball/runs/<run-id>/config`, the same copy the run reads. Save writes that copy and does not write the project configs. An agent changes those files through the same run directory.

Project sync, starting a run, and any other long load run off the Swing event thread. While that work is in progress the window stays usable and a banner names it (`Syncing the project`, `Starting the run`, `Loading the project`, or the other long load). The banner clears when the work finishes. A failure stays on that banner and names the error. Every split pane can be dragged so one side takes almost the whole window.

| Window action | Command |
| --- | --- |
| Open a scenario | `open-scenario` (`--feature`, `--name`, optional `--example`) |
| Choose Examples rows | `example` — the same filter as `pkb_example`. Play shows the first source-order match. Headless `pkb_example` still runs every matching row. |
| Play | `play` |
| From here | `from-here` |
| Step | `execute-step` |
| Pause | `pause` |
| Stop playback | `stop` |
| Insert a step | `insert-step` |
| Update the selected step | `update-step` |
| Save | `save` |
| Switch the retained diagnostic run | `diagnostic-run` (`--run`) |
| Refresh status | `refresh` |
| Synchronize the project | `session-sync` (the one-shot forwarded command remains `sync`) |
| Start, restart, or stop the worker | `worker-start`, `worker-restart`, `worker-stop` |
| Show a run | `show-run` (`--run-id`) loads that run's record, logs, reports, and config. The run dropdown reads the same directory. It does not start a test. |
| Open the window on a run | `open-window` (`--run-id`) opens the one window on that run. It does not start a test. |
| Close the window | `close-window` closes the window. It does not delete the run directory, stop a headless run, or erase the short log. |
| List live direct-control sessions | `session list` (add `--json` for a JSON array). One line per descriptor whose state is not `stopped`. |
| Show one session descriptor | `session info <sessionId>` prints `.pickleball/runs/<runId>/session/descriptor.json`. `--json` prints the same bytes. |
| Hold step advance | `session hold <sessionId>` sets the descriptor to `held`. Workbench does not start the next step while it is `held`. |
| Release a hold | `session release <sessionId>` restores the state from when the hold began (`running` or `paused`). |

A live session publishes that descriptor so dom-composite 2.4.0 can attach to the browser Pickleball already owns. The file, `session list`, and `session info` are the same source. When `pkb_direct_control_provider` is set, Workbench runs `composite session attach` (it exits immediately) with `--session-dir .pickleball/runs/<runId>/session/composite` and writes `compositeSessionDir` and `compositeSessionId` on the descriptor. The element adapter joins that browser with the same CDP address and does not attach a second time when it is already joined. Join from the CLI with `composite --session-dir <compositeSessionDir> query --session <compositeSessionId> --css 'body'`. The binary is `COMPOSITE_BIN`, `pkb_composite_bin`, `COMPOSITE_HOME`, a sibling `dom-composite` checkout, or a native classifier (`linux-x64`, `linux-aarch64`, `windows-x64`, `macos-aarch64`, `macos-x64`). Detach runs `session close` and does not stop the browser. The window shows the CLI output and does not keep a second copy. Attach, detach, and hold are specified in [Direct-control handoff](direct-control-handoff.md). A normal `mvn test` does not write the file. A bare `session` command is still the controller launch. Headless `play`, `pause`, `save`, `refresh`, and the worker verbs use that same CLI session. A worker verb before `sync` fails and says to run `sync`. It does not report the worker as started.

There is no Gherkin step that opens the window. An agent that can only run a scenario and cannot call the launcher cannot open it. The open command stays on the launcher.

Advanced inspector actions (breakpoints, Step Overrides, browser page and screenshot, service call, Mapping) already call `WorkbenchServices`, the same methods as the existing MCP tools. Take control, Allow, and Deny stay the existing lease tools. Do not add a pixel, WebView, or JavaFX click tool.

When a Workbench window is already open, an agent uses these same commands against that session so the person sees the result. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI. When nobody is watching, headless `discover`, `confirm`, and `isolate` stay the path.

Build tools stay the project wrapper. Discover, confirm, and `session-sync` invoke `mvnw` or `mvnw.cmd`, `gradlew` or `gradlew.bat`, or `java -jar` on the wrapper jar. Pick the script from the OS. Do not require a machine-wide Maven or Gradle install. MCP and the launcher do not embed Maven or Gradle and do not expose a general `mvn` or `gradle` tool.

`hint`, `discover`, and `confirm` accept `--tags` / `--name` / `--example` plus `--retention=all|failed|none` (or `--retention <value>`). Discover and hint default to `failed`. Confirm without `--retention` keeps the Discover snapshot value. This retention overlay is launcher/planner-only; do not pass `--retention` to the Workbench controller `isolate` / `session` parser. Ordinary diagnostic runs still default to `pkb_reportretention=all`.

Discover and confirm seed `.pickleball/runs/<run-id>/workspace/` and launch with `-Dpkb_workspace` plus selection keys. A blank `pkb_reportingmode` in that saved copy is set to `diagnostic` once, before `--runvar` edits. A later prepare does not rewrite an unchanged copy. The dry-run prints that default, so the run writes `run-index.json` and `run-catalog.json` with a retained `pkb_run_profile`. An explicit mode, including `normal`, is left alone. If the run still retains no `pkb_run_profile`, Workbench prints a `WARNING:` and keeps Maven's exit code. When seed capture finds no consumer runner it warns about the `DefaultDynamicSuite` fallback; run `mvn test-compile`, then `workspace-seed --reseed`. `hint` and `resolve-runvars` print a dry-run of a fresh normal launch (`--json` is one object and includes `runProfileFingerprint`): the consumer runner is constructed when exactly one is on the preferred test-output root, so `globalTestDefaults()` and `globalTestProperties()` are in the preview, and property files are read only by `ProjectPropertySources` (normal launch: classpath ISO-8859-1 only, so UTF-8 bytes for é are not é; Workbench also reads project root and `src/test/resources` as UTF-8, and those same bytes are é). A file that `Properties` cannot load throws and the command exits 1. The preview does not publish system properties, start Cucumber, or open a browser. `Workspace.loadForLaunch` is the only launch read of the saved copy and does not apply hooks or property files again. Do not treat that preview, including its `pkb_environment`, as the environment Discover will use after the copy is edited. Trust the run record after Discover. `isolate` / `session-start` `--session-file` is the state file the controller writes and the launcher waits on; omitted, that file is the run's own `cli-session.json`. `pkb_overriderunvars` is deprecated for Workbench. It still works for a plain `mvn test` that does not set `pkb_workspace`. There is no sealed snapshot and no Apply-sealed control. `workspace-show`, `workspace-edit`, and `workspace-seed` edit the same files as the Config tab. A change on disk since the panel loaded is a warning and is not overwritten (`--expect` mismatch exits 3 and writes nothing). A `runvars.properties` that `Properties` cannot load, including a bad `\u` escape, is a read failure. `finish --status` is `PASSED`, `FAILED`, `EMPTY`, `TIMEOUT`, or `STOPPED` (default `STOPPED`); an unknown status exits 2. `execute-step` with no text exits 2 before it requires a session. `docs/roadmaps/workbench-cli-inventory.json` is the command and flag inventory. `WorkbenchCliCoverageGateTest` fails when a command or major flag has no `covers_command_*` / `covers_flag_*` method, or when that method does not call the command and assert the result.

`mcp` and `ui` remain host/human commands. Hosts that already run Workbench MCP can launch it from the consumer test classpath:

```bash
./mvnw -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher \
  -Dexec.classpathScope=test \
  "-Dexec.args=mcp ."
```

```powershell
.\mvnw.cmd -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.launcher.PickleballWorkbenchLauncher" "-Dexec.classpathScope=test" "-Dexec.args=mcp ."
```

Humans who want the Swing player can pass `ui .` instead. With no launcher arguments, `ui` and the current directory are selected automatically for that human default. Other Workbench commands are forwarded in the same form, for example `"-Dexec.args=sync ."`. Headless `mcp .` is optional host wiring, not an agent setup step. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI. When a person already has the window open, the session commands above drive that session so the person sees the result. When nobody is watching, stay on headless `discover`, `confirm`, and `isolate`. See `.pickleball/AGENT-GUIDE.md`.

The published Pickleball JAR is also executable. `java -jar pickleball-<version>.jar ui <project>` is the outer `Main-Class` (`PickleballWorkbenchLauncher`): it still extracts the nested controller and forks `java -cp`. It does not merge Workbench onto the Pickleball classpath. `java -jar pickleball-<version>.jar export-guidance .pickleball` is JDK-only: it copies bundled guidance through `PickleballLocalStore` and does not load DiagnosticCli or Jackson. Relocatable openers under `.pickleball/open/` walk up to the consumer project, find Java, and resolve a pickleball jar from `current.json`, a sibling `pickleball-*.jar`, or Maven local / the Gradle cache. They do not bake in a project, m2, or version path. Direct `java -jar pickleball-X.jar` uses X and pins `current.json` to X so versions can be tested side by side under `.pickleball/v/<version>/`. `use-version --version=<version>` points `current.json` at an already exported tree without deleting the others. `export-guidance` prints `Pickleball <version> built from commit <sha>` first. `gc-versions` is the explicit sweep for a tree unused for 3 days. It is not run from export or from switching.

Gradle consumers can expose the same dependency-owned launcher without resolving a cache path or adding a Workbench dependency:

```groovy
tasks.register('pickleballWorkbench', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'tools.dscode.launcher.PickleballWorkbenchLauncher'
    args = (project.findProperty('pkbArgs') ?: 'hint').toString().split('\\s+')
}
```

Run it with `./gradlew pickleballWorkbench` (or `gradlew.bat pickleballWorkbench`). Pass `-PpkbArgs='hint'` (or `plan`, `status`, `query`, `events`, `workspace-show`) when the task should forward those arguments; the default in the snippet above stays `hint`. The task uses the consumer's resolved test runtime only to locate the tiny launcher and nested bytes; actual controller code still starts in a separate `java -cp` process. After `discover` or `workspace-seed` has written `classpath.cache`, prefer `java -cp @classpath.cache tools.dscode.launcher.PickleballWorkbenchLauncher hint` (the opener script does this). That does not start Gradle or Maven. `discover`, `confirm`, and `isolate` still use the wrapper when they build or launch a worker. `--quiet` on discover or confirm prints only the summary line, plus `error:` and `fix:` when the launch itself failed. Seed warnings that start with `WARN:` or `WARNING:` are not printed. `fix:` stays. Tags, workspace, run id, the retained profile, and `NEXT:` are not printed. The console files are still written. Direct and wrapper launches use the same report: no `# pkb-console` lines, and no `error:` / `fix:` for an ordinary `FAILED`. A dead fork, or a failed run that recorded zero steps (a before-all hook that throws), is `LAUNCH_ERROR` with `scenarios=0` and `error:` / `fix:` from the console. A missing step count is not treated as zero. One-shot timings, with the incomplete-classpath caveats, are in the 2.1.18 release notes.

The launcher streams the nested payload (it does not load the controller JAR into a byte array), hashes SHA-256 while copying, and rejects payloads larger than 32 MiB. The nested JAR is thin: Workbench classes plus shaded `pickleball-control-protocol` only. OpenJFX, MCP, and Jackson are resolved at launch into `.pickleball/v/<version>/workbench/lib/<version>/` when `current.json` is complete (legacy `.pickleball/workbench/lib/<version>/` otherwise). It extracts atomically to:

```text
.pickleball/v/<version>/workbench/controller/<sha256>/pickleball-workbench.jar
```

Unversioned `.pickleball/workbench/controller/<sha256>/` remains the fallback when `current.json` is missing, so older trees keep working. `.pickleball/current.json` is a version pointer (`pickleballVersion`, `complete`, `updatedAt`), not a path to the dependency jar. Guidance, Workbench extract/live/base, Discover/session/attach state, and libs live under `v/<version>/`. Root `AGENT-GUIDE.md` and `GUIDANCE-MANIFEST.json` are aliases of the current version. Auto-materialize happens lazily from `PickleballRunner` and the launcher; it unpacks from the running jar and never copies a sibling version folder.

It verifies existing/extracted bytes, starts `java -cp <thinJar:resolved-libs> tools.dscode.workbench.WorkbenchApplication` in a new Workbench JVM, inherits stdio, and propagates non-zero exit status. The content-addressed path prevents a stale payload from silently replacing the version carried by the consumer dependency.

## Maintainer build and direct run

Build the standalone controller and strict isolation checks:

```powershell
.\gradlew.bat :pickleball-workbench:build verifyStrictControllerIsolation
```

The thin controller JAR is:

```text
pickleball-workbench/build/libs/pickleball-workbench-<version>.jar
```

Synchronize a consumer project before starting a worker manually from repository output. Maintainer in-repo runs may put Workbench `runtimeClasspath` files on `-cp` next to the thin JAR; the consumer launcher must not use that compile classpath and instead resolves the generated lockfile:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"
$workbenchCp = "$workbenchJar;<resolved OpenJFX/MCP/Jackson controller libs>"
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication sync ".\maven-consumer-project"
```

Read the last synchronization manifest without invoking Maven/Gradle:

```powershell
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication status ".\maven-consumer-project"
```

Synchronization uses the selected project wrapper to establish compiled output and the effective test runtime classpath. It keeps `-DskipTests` (Surefire never runs during sync). Input fingerprints of Java sources, resources, build files, and dependency artifacts decide how much of the wrapper to run:

- **Skip** when those inputs match the last recorded input fingerprints and the live snapshot is present. The output `fingerprint` in `manifest.json` is provenance over merged classes plus dependency bytes; it is not the skip key.
- **Resources-only** when only feature/config/data (test resources) changed: Maven `process-resources` / `process-test-resources`, or Gradle `processResources` / `processTestResources`, without `test-compile` / `testClasses`. If compiled outputs were cleaned, sync escalates to a full compile so live classes are not wiped.
- **Full** when Java sources, the build descriptor, or dependency artifact bytes changed, or when no prior snapshot exists.

Live Gherkin buffer edits never require sync and never write the original `.feature` until explicit Save. Worker restart without rebuild already exists. Step Overrides stay worker-side compile.

`.pickleball/workbench/base/classes` is provenance only; the worker runs against the merged `.pickleball/workbench/live/classes` state plus captured external dependencies. Do not use `live/classes` as an editor.

At worker connection time, Workbench requires a different PID, compatible protocol range and capabilities, a Pickleball code source that is exactly one captured consumer classpath entry, the synchronized Pickleball version (except explicit development output), and no Workbench controller artifact on the worker classpath. It fails clearly instead of falling back to a bundled runtime.

## Swing UI

Start Workbench for one consumer project:

```powershell
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication ui ".\maven-consumer-project"
```

The Swing UI is a presentation adapter over the same `WorkbenchServices` / `WorkbenchController` seam used by MCP. It does not own a second worker manager, bridge client, Mapping implementation, Gherkin execution engine, or Pickleball runtime model.

### Player-style layout

The primary workspace is an interactive Gherkin player with a project feature picker:

```text
+-----------------------------------------------------------------------------------+
| Scenarios | Project  Pickleball <version> / readiness   Play Pause Stop  Status   |
+-----------+--------------------------------------------+--------------------------+
| Name +    | LIVE GHERKIN TEXT EDITOR                   | Mapping | Terminal |     |
| match mode| Tab = leading :  keyword autocomplete      | Explorer | Report   |     |
| tags AND  | playhead on the same buffer                |                          |
| tags NOT  |                                            |                          |
| scenario  |                                            |                          |
| list      |                                            |                          |
| (feature  |                                            |                          |
|  filter   |                                            |                          |
|  hidden)  |                                            |                          |
+-----------+--------------------------------------------+                          |
|           | Step Editor [Step] [From Here]             |                          |
+-----------+--------------------------------------------+--------------------------+
| Footer activity                                                                   |
+-----------------------------------------------------------------------------------+
```

The left rail is a scenario filter, not a feature-file browser. Primary controls are scenario name (starts with / contains / ends with / full match; default contains; all four are case-insensitive against the Gherkin Scenario / Scenario Outline title), tags the scenario must have (AND), and tags it must not have (NOT). Include/exclude fields accept any number of tags, with or without a leading `@`, split on commas and/or whitespace. Empty include/exclude means no tag constraint. Feature-level tags, optional Rule tags, the scenario/outline's own tags, and Examples tags on an outline are inherited the same way Cucumber does; Workbench parses those tags from the catalog `.feature` files and does not call Cucumber. Feature-file selection is collapsed behind **Filter by feature** (Gherkin Feature name vs file path lives in that panel). Default: no feature filter, so name/tag apply to every catalog scenario in the synchronized project. Scenario Outlines expand to selectable Examples rows. Clicking a result opens the **whole originating `.feature` file** in the editor and sets that scenario (and optional Examples row) as the play target. The caller tab stays pinned; Ctrl+click or **Open target** on a `RUN` / `CALL` / `data:/` step opens the callee in another tab. Below the picker, the **Step definition** panel shows consumer Java glue for the selected step when it can be resolved, or that the line is a Pickleball dynamic step / unmatched reference. A small collapsible **Run vars** panel loads the run workspace `runvars.properties` (boolean-like as toggles, known enums as combos, else text). **Save** writes that file atomically. A change on disk since load warns and is not overwritten. There is no **Apply sealed** control and no sealed snapshot. `pkb_overriderunvars` is deprecated for Workbench. Workbench does not import `PKB_props` / `PickleballProfiles`; resolve stays in core. Workbench does not write `.feature` files unless you use the explicit **Save** control, which writes the editor buffer to the original file.

The center editor is one ordinary Gherkin text document over `LiveScenarioPlayer`. Nested steps use Pickleball's leading-colon grammar: Tab at the start of a line, or while only leading colons/spaces sit before the caret, inserts one extra `:`; Shift-Tab removes one leading `:` if present. Mid-line Tab inserts a single space and does not move focus to another Swing control. Typing the first letters of a Gherkin keyword after optional leading colons/whitespace offers completion for Feature, Rule, Background, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, `*`, and line-start control words IF / ELSE / ELSE-IF. Tab or Enter accepts the selected keyword and inserts a trailing space. When the completion popup is open, Tab accepts the completion; when it is not, Tab at the indent prefix inserts `:`. **Play** runs a derived plan: Feature/Rule Background steps, then the selected scenario's steps, with one selected Examples row substituted. **Step** remains isolated `executeStep`. **From Here** runs the plan from the selected template step. The default demo buffer still has no save path.

The player bar shows the running Pickleball version next to the project name. The right side is four tabs: Mapping, Terminal, Explorer, and Report. Low-level lifecycle controls stay under **Session**. Existing investigation tools stay under **Tools > Advanced Controls**.

### Open vs Play

| Action | Effect |
|---|---|
| Picker “Play this scenario” | Replace the live Gherkin buffer (existing 2.1.12 behavior). |
| Explorer / Report / `workbench_go` from a **retained run** | Open a **peek** tab. Prefer the pack-local `source/files/` copy. Do **not** replace the live buffer. Do not write. |

Ctrl+click, Open, or double-click a tree row calls the same `workbench_go` resolver as MCP. Java from a retained run: Step definition panel first; if `definition.sourcePath` is a real consumer file, a read-only Workbench text tab. Never open framework sources from the JAR. Paths stay inside the consumer project. A missing target is a visible miss and does not blank the live editor. Pause, Stop, and explorer rewind are view-only; they do not rewind browser, Mapping, or services.

### Live scenario buffer and player state

`LiveScenarioPlayer` is a headless Workbench-side presentation model. It owns only:

- stable line identities independent of display line number;
- the live session buffer as editable Gherkin text;
- selected line;
- playhead (the user-visible needle);
- player states `STOPPED`, `PAUSED`, `RUNNING`, and `WAITING_FOR_STEP`.

The Live Scenario Editor is a session-scoped Gherkin text document. Users can type Gherkin on any line, including text that already ran. Stable line ids are preserved across in-place edits so the player can keep selection, playhead, and execution cursor coherent. Loading a picker scenario replaces the live buffer only. The default remains session/live. **Save** is confirmation-gated: it copies the live scenario into the originating `.feature` file and scenario only after Allow. The Workbench-owned demo has no save path. Workbench never writes `.feature` files on picker load or on Deny.

The playhead behaves like an audio-player needle:

- clicking a scenario line instantly seeks the playhead to that line;
- while a run is active, `executeStep` advances the playhead once on success (or pauses it on failure); the UI Play loop continues from that new next step without remaking the same mark;
- **Pause** and **Stop** do not claim to rewind browser, Mapping, service, or other worker side effects.

Global **Play** always starts a fresh interactive scenario context and runs from the first executable step, even if the playhead is elsewhere. Fresh **Play** / **From Here** runs restart the consumer worker so prior side effects do not masquerade as the start of a scenario.

### Step Editor play actions

The Step Editor exposes two distinct play actions on the same `WorkbenchServices.executeStep` seam used by MCP:

```text
▶ Step       execute only the Step Editor text in the current paused live context
▶ From Here  start a fresh scenario context and run from the selected/playhead step through the rest of the buffer
Enter        insert a step (append-and-run while waiting at end)
Ctrl+Enter   update the selected line in place
```

**Step** pauses automatic playback, sends the displayed Gherkin unchanged, and leaves the main player paused. **From Here** treats the selected executable step as the first step of a new run.

Workbench never strips `Given` / `When` / `Then` / `And` / `But` / `*` and does not contain a second Gherkin matcher. If a displayed line starts with one of those keywords, worker-side `DynamicControl` parses that one line through `GherkinControl` and executes the resulting detached step. Historical raw detached-step text remains supported.

### Stay in play / add-and-continue

Reaching the end of the buffer does not drop out of play. The player remains `WAITING_FOR_STEP`. Typing a new step in the Step Editor and pressing **Enter** appends that step to the end of the live scenario and queues it through the same `executeStep` contract. Adding an executable line at the end of the in-place editor while waiting does the same. Inserting a line earlier in the document does not replay later steps.

### Default demo scenario

A new Workbench session loads a Workbench-owned sample, not a blank buffer and not a consumer `.feature` file. The default scenario is a small browser demo against the existing Maven consumer local test site:

```gherkin
Feature: Workbench Live Scenario

Scenario: Open the local test site
  Given navigate to: URL.home
  When , ensure "Pickleball Test Lab" Text is displayed
  And , click the "Open Forms Playground" Link
  Then , ensure "Forms Playground" Text is displayed
```

`URL.home` comes from the consumer's ordinary config mapping. The sample does not hard-code machine-specific filesystem paths. Once a worker is up, **Play** exercises real browser navigation and a click on the local test site.

### Mapping tab

There is no GUI-defined `Current Scope` concept and no hard-coded NodeMap names. The Mapping tab is a graphical tree: every NodeMap has a visible root, nested JSON/XML objects expand as children, and properties are edited in place (key, value, type: `string`, `numeric`, `boolean`, `object-as-JSON`, `object-as-XML`). Live maps come from the worker `ParsingMap`. Upcoming play-plan steps also appear as pending `STEP_MAP` entries before they run; values authored there are stored under the protocol `STEP_SEED` reference and copied onto that step's NodeMap the moment the step first executes. Typed writes go through the existing `mappingPut` service; key renames, deletes, and whole-object replacement use `mappingRestore`. The GUI must not recreate inheritance rules or keep a second Mapping store.

NodeMap implementations that are not exact ordinary `NodeMap` instances remain inspection-only. MCP continues to support arbitrary JSON-compatible Mapping values through the shared service methods.

### WebView packaging

JDK 21 does not ship a modern browser panel. Workbench embeds OpenJFX `WebView` through `JFXPanel` for the Mapping tree, Explorer, and Report. That choice stays Workbench-only: Maven-central JavaFX modules are resolved onto the forked controller classpath at launch from ordinary platform JARs. They are not shaded into the thin nested JAR. JCEF was not used because Chromium natives are harder to keep isolation-clean and do not package as ordinary Workbench dependencies. Do not add heavy JS graph libraries; Explorer stays vanilla JS. If JavaFX cannot start, Mapping, Explorer, and Report use their text fallbacks. The live editor is always the in-place Gherkin text buffer on the same `LiveScenarioPlayer` model.

A one-line JavaFX warning `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` is expected with that classpath launch. It is not a failure and does not mean Workbench is stuck. The window is shown first; JavaFX WebView is attached on the next Swing pulse so that warning cannot deadlock startup.

### Terminal, Explorer, and Report

The Terminal tab presents the existing consumer-worker stdout/stderr capture files under `.pickleball/workbench/logs/` as a scenario-run log. A dropdown filters `TRACE`, `DEBUG`, `INFO`, `WARNING`, and `ERROR`. Lines continue as the playhead moves because the panel tails those files and also records `executeStep` / Mapping results. Workbench does not redirect MCP stdout. If a worker log line has no printed level, it is shown at `INFO` rather than invented. Structured Pickleball logger output is used when present; there is no second log fabricator.

The Explorer is a two-panel WebView **replay** over Pickleball's retained diagnostic artifacts (`reports/diagnostic-runs`), and it **tails** an in-progress isolate: it re-reads `events.jsonl` while `completion` is `IN_PROGRESS`, and it can also follow live `workbench_events` as a `live-isolate` stream. Tailing does not rewind the browser, Mapping, or services. When isolate runs unsaved Gherkin that differs from the originating `.feature`, Workbench writes `.pickleball/.../workbench/live-buffer.feature`; Pickleball core copies that sidecar into the retained pack as `source/live-buffer.feature`. Workbench never writes the pack. Choose a catalog run, then walk it like a recording. The left panel is an expandable **indented** execution tree: indent is call depth. After a nested COMPONENT finishes, the next sibling under the caller is the return. There are no return arrows, no live graph, and no Mermaid. Color is status **at the playhead**. Default expand: failed scenario plus the current stack; completed callees collapse to a status chip. Click a row to seek the playhead to that node's `eventSeq`. Ctrl+click / Open / double-click is `workbench_go` to the file (peek, never replace the live buffer). Node kinds: `scenario` | `component` | `service-call` | `step` | `data` | `java` (`java` is a drill). A multi-row `RUN` becomes sibling rows under the parent step.

The right stage shows the screenshot for the current beat or an honest “no screenshot retained” gap, plus that beat’s Gherkin, `source.path:line`, definition, and INFO+ log lines. Do not invent Mapping for old retained runs. Play/Pause advances in order and **stops on the last step** (it does not wrap). Previous/Next, the scrubber, Left/Right arrows, and **speed** (beat interval) seek. Rewind is view-only. Evidence-layer chips show which catalog/index/events/screenshot files exist on disk; they do not invent a competing store or fake retained-run data:

1. `run-catalog.json`
2. selected `run-index.json` / `clusters.json`
3. scenario `summary.json`
4. relevant `events.jsonl` (the replay timeline)
5. existing comparison/fingerprint metadata
6. PNG frames only when a retained screenshot exists, shown with the Gherkin step text that was running when taken
7. raw trace only when structured evidence is insufficient

If the consumer project has no `run-catalog.json`, the explorer says so and stays empty.

The live explorer tree is built from `events.jsonl` in Java; the WebView only renders it. Do not add JCEF or heavy JS graph libraries. Explorer stays vanilla JS in OpenJFX WebView.

The **Report** tab is the fourth right tab and reads the same `investigation.json` as portable `report.html`. Reading order is Gherkin/business bottom line, where, cause vs failed assertion, then lower-level Java/JSON/HTTP/git/environment. Clicks call `workbench_go`. Portable `report.html` is an indented list; do not use Mermaid in live UI or `report.html`. `executionMap` in investigation JSON is a portable HTML snapshot only — never the live tree source. Schema version 2 is additive; v1 JSON still renders, and emit succeeds if v2 fields are missing.

### Existing advanced capabilities

The redesign preserves the underlying existing capabilities:

- selected project display and synchronization/status refresh;
- synchronize, start worker, restart fresh worker without rebuilding, and stop worker;
- worker PID/runtime/scenario/pause status;
- live raw Gherkin execution;
- Mapping get, put, and resolve;
- incremental semantic-event display with timestamp, hook, step/phrase, and signature detail;
- Step Override list, worker-side compile/replace, remove, and clear;
- browser page evidence and lightweight PNG screenshot display;
- existing Pickleball service-call execution/evidence;
- semantic breakpoint list, add, remove, and clear with hook/filter/one-shot/finite-lease controls;
- clean Workbench shutdown when the window closes.

The Step Override editor sends its source template unchanged to the worker. The source must contain `{{CLASS_NAME}}`; generated class naming, compilation, classloading, rule registration, matching, captures, replacement, and cleanup remain worker-side Pickleball responsibilities. Browser page, screenshot, service-call, event, and breakpoint controls expose existing bridge contracts rather than reimplementing them in Swing.

Synchronization, worker actions, live bridge calls, Mapping operations, event refresh, Step Override actions, browser/screenshot evidence, service calls, and breakpoint actions run off the Swing Event Dispatch Thread. Live controls are enabled only while the Workbench-owned worker is running and paused.

The UI is intentionally not a project IDE, generic process manager, generic Maven/Gradle task runner, source navigator, or collaboration system. The Live Scenario Editor is a session-scoped Gherkin player/editor, not a workspace file explorer and not an automatic writer of consumer `.feature` files.

### Watched AI-agent control lease

Workbench owns one control lease for the live session. Swing and MCP/HTTP adapters share it; the lease is not Swing-only state.

- Holder is `HUMAN` when the UI is up, or `AGENT` after an attached agent requests control.
- The snapshot also carries the agent display name, `currentAction` banner text, and at most one pending permission request.

While the human holds the lease, Swing play/edit/mapping/save/worker controls stay enabled. Agent mutating calls fail clearly until `workbench_request_control`.

While an agent holds the lease, the human can watch the same window. Play, edit, picker/filter, editor view toggle, Mapping writes, Save, and worker lifecycle controls lock. The WebView editors stay mounted and become read-only; they are not torn down. A banner names the agent and shows `currentAction`. **Take control** stays enabled. Take control returns the lease to `HUMAN`, unlocks Swing, and fails any in-flight agent permission wait so a blocked Save does not write.

The agent should update `currentAction` as it works. Playhead, Mapping, Terminal, and screenshots follow because the agent uses the same `LiveScenarioPlayer` / worker as the UI. Testing the live scenario (`executeStep`, play, Mapping reads, evidence) is allowed on the agent lease. Copying the live scenario into the original `.feature` is not; that goes through `workbench_request_save` and waits for Allow/Deny in the Swing banner.

Human **Save** uses the same service. After a picker scenario was loaded, Swing asks: copy these live steps into file X / scenario Y? Deny writes nothing. The demo buffer stays unsavable.

### Driving a session a person is watching

The Swing window is a client of the session commands above. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI. When a person already has it open, the agent runs those same launcher commands against that session so the person sees the result. When nobody is watching, stay on headless `discover`, `confirm`, and `isolate`. Headless `mcp .` is optional when the host already exposes `workbench_*` tools. Those tools are the same session, not a second implementation and not a setup step.

UI mode cannot share process stdout with stdio MCP. Starting `mcp` while the UI is already running would be a second Workbench JVM. Instead, `ui` starts a 127.0.0.1-only JSON attach endpoint over the same `WorkbenchServices` / `WorkbenchMcpTools` methods and writes disposable discovery state:

```text
.pickleball/workbench/attach.json
```

Example:

```json
{
  "url": "http://127.0.0.1:51234",
  "token": "hex-session-token",
  "pid": 12345,
  "project": "/path/to/maven-consumer-project",
  "mode": "ui-attach",
  "bind": "127.0.0.1"
}
```

A Copilot or other MCP-style client finds that file in the consumer project, then:

1. `GET {url}/health` — liveness, no token.
2. `GET {url}/lease` and `GET {url}/player` — `Authorization: Bearer <token>` or `X-Workbench-Token`.
3. `POST {url}/tools/workbench_request_control` with `{"agentName":"Copilot"}`.
4. Use the existing live tools (`workbench_execute_step`, `workbench_step_resolve`, Mapping, evidence, worker) while holding the lease, and `workbench_set_current_action` so the human can watch.
5. `POST {url}/tools/workbench_request_save` to ask to copy the live scenario into the original feature. The call blocks until the human clicks Allow or Deny, or Take control.

Headless `java -cp <thin-jar>:<resolved-libs> tools.dscode.workbench.WorkbenchApplication mcp <project>` stays stdio JSON-RPC only. That is optional host wiring, not an agent setup step. The consumer-agent live path is launcher `isolate` then `execute-step`. That client may hold the lease without a banner. Save is still a distinct explicit tool and never an implicit write.

A person may start `ui .` and watch the window. The agent then uses the session commands against that open session. Do not launch a second `mcp` process against the same live UI session, and do not open the GUI for your own testing in order to obtain `.pickleball/workbench/attach.json`. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test.

## MCP stdio

Start the lightweight non-Spring MCP server for a consumer project. This is optional host wiring when `workbench_*` tools are already connected, not an agent setup step:

```powershell
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication mcp ".\maven-consumer-project"
```

Or, from a Maven consumer test classpath, `"-Dexec.args=mcp ."` through `mvnw` or `mvnw.cmd`. That launcher is optional host wiring. Agents use Workbench `discover` / `confirm` to find failures. When a window is already open they drive it with the session commands. When nobody is watching they use `isolate` / `execute-step`. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test. Do not click the JavaFX or WebView UI. Do not require a machine-wide Maven or Gradle install.

The server uses the official Java MCP SDK core and stdio transport with the Jackson 2 JSON adapter. MCP dependencies are Workbench-only and are resolved onto the forked controller classpath at launch; they are not shaded into the thin nested JAR. Workbench deliberately does not use Spring Boot, Spring Framework, Spring AI, WebMVC, or Tomcat.

### Stdout contract

In MCP mode:

```text
stdout = MCP JSON-RPC only
```

Workbench reserves the original stdout stream for MCP before constructing the controller/SDK and redirects ordinary `System.out` output to stderr. Worker stdout/stderr are independently captured under:

```text
.pickleball/workbench/logs/
```

Do not add banners, normal logging, worker output, test output, or diagnostic messages to MCP stdout.

### MCP tools

The MCP server is an adapter over `WorkbenchServices` / `WorkbenchController`, which in turn delegates live operations to `WorkbenchLiveSession`. It does not implement a second Pickleball runtime.

Project and worker lifecycle:

```text
workbench_sync
workbench_sync_status
workbench_worker_start
workbench_worker_restart
workbench_worker_stop
workbench_worker_status
```

`workbench_sync` uses the skip / resources-only / full rules above. Live buffer edits do not require it.

Live runtime, Mapping, and watched-agent control:

```text
workbench_request_control
workbench_release_control
workbench_set_current_action
workbench_control_lease
workbench_player_state
workbench_player_replace_document
workbench_request_save
workbench_execute_step
workbench_step_resolve
workbench_mapping_get
workbench_mapping_put
workbench_mapping_resolve
workbench_mapping_snapshot
workbench_mapping_restore
workbench_events
```

`workbench_execute_step` returns a structured `SUCCESS` / `FAILED` / `UNAVAILABLE` result. A FAILED Gherkin hypothesis leaves the same paused worker available so the agent can inspect, insert, nest, or retry. That result is not an MCP `isError` and does not stop the worker. MCP `isError=true` is for controller/runtime problems such as a missing paused worker.

`workbench_step_resolve` is read-only: it maps one Gherkin step to consumer glue, a Pickleball dynamic/framework step, a matching Step Override, or `UNMATCHED` without executing it.

`workbench_events` is a paged read: pass `afterSequence` and a small `limit` (default 100, maximum 500). Do not request the full retained event history when a page answers the question.

Browser/service evidence:

```text
workbench_browser_page
workbench_browser_screenshot
workbench_element_inspect
workbench_service_call
```

Prefer `workbench_browser_page` and `workbench_element_inspect` over `workbench_browser_screenshot` unless the image itself is required. Screenshot bytes are expensive in agent context.

Live `inspect-page`, `find-element`, and `workbench_element_inspect` join the session descriptor's CDP address. `inspect-page` and `find-element` call `PageElementQuery`, and each of those calls `ElementOperations.describe` once. `workbench_element_inspect` calls `ElementControl`, which uses that same one-batch `describe`. A `--query` (CLI) or `query` (MCP) value is the engine selector string and is forwarded as-is. Category+text still builds the dictionary XPath. Counts, text, and evidence come from the returned rows. The CLI, the MCP tool, and the GUI session command do not look up elements themselves. Offline `--html` inspect does not start a browser. It stays `source=offline-html` and does not run the engine query.

Semantic breakpoints:

```text
workbench_breakpoint_list
workbench_breakpoint_add
workbench_breakpoint_remove
workbench_breakpoint_clear
```

Step Override authoring:

```text
workbench_step_override_list
workbench_step_override_compile
workbench_step_override_remove
workbench_step_override_clear
```

Sparse diagnostic readers. Do not glob `reports/diagnostic-runs`. Prefer `query` and the six `wb_*` tools. The three older names are deprecated aliases of that same path (`workbench_diagnostic_catalog` is `wb_runs`; `workbench_diagnostic_run` and `workbench_diagnostic_summary` are `wb_get`). They return compact JSON, not a file dump, and do not include `events.jsonl`, traces, or PNG bytes. Retained `pkb_run_profile` is `wb_props` / `query props`. Each successful call appends one `query` line to `.pickleball/agent-log`, the same line the launcher writes:

```text
workbench_diagnostic_catalog
workbench_diagnostic_run
workbench_diagnostic_summary
```

The same files are also queried, not globbed, by the launcher `query` command and by six thin MCP forwarders (`wb_runs`, `wb_get`, `wb_query`, `wb_search`, `wb_props`, `wb_errors`). They share `DiagnosticQuery`. `RunIndexFinder` is the only run-index locator (`AgentCoordination.findRunIndex` delegates to it). Default output is compact JSON with `where`, a small limit, and `...+N more, use cursor`. Worker `worker-*.log` tails are included when the kind is `log`. A successful MCP retrieval writes the same short-log line as `query`. `help` says discover, then plan, then run. Usage text is one table (`WorkbenchCliHelp`). A failure is `error:` and `fix:` (the table row, or the nearest command). `--runId` means `--run-id`, `--agentId` means `--agent-id`, and `--to=all` means `--to=any`; each deprecated spelling prints one `notice:` line. `session info|hold|release --run-id` is the session id. Wrapper launches pass arguments as a `ProcessBuilder` list, so they are not shell-quoted. A PowerShell `-Command` string uses `WorkbenchShellQuoting` (single quotes, apostrophe doubled); that is the Windows proxy download, the browser file version, and `Unblock-File`. See [Diagnostic query](diagnostic-query.md).

Human investigation handoff (writes `.pickleball/investigations/<id>/` and returns the relative `report.html` path only):

```text
workbench_investigation_emit
```

After emit, chat prints the six-line bottom-line block from `docs/consumer-agent-guide.md`, then the report path. If a UI session is attached, at most two `wb://` links may follow.

One navigation tool (do not add generic IDE / git / process MCP tools):

```text
workbench_go
workbench_agent_command
```

`workbench_go(link)` accepts `to` (`explorer` | `editor` | `mapping` | `report` | `terminal`), `runId`, `eventSeq`, `nodeId`, `path`, `line`, `column`, `kind` (`feature` | `component` | `step` | `call` | `data` | `java`), `mapReference`, `key`, `investigationId`, `section`, and `label`. The same fields parse from `wb://explorer?run=&seq=&node=`. UI attach plus a control lease are required to move the window. Headless: validate and echo the resolved target. `label` may feed `workbench_set_current_action`. The tool does not write files. Report clicks, explorer Open, and MCP share one Java resolver.

`workbench_agent_command` forwards `op` plus an `args` map to the same agent commands the CLI runs (`observe`, `launch`, `windows`, `tabs`, chat, highlight, overlay, html, `capture-scenario`). Repeated launch `arg` values are joined and sent as bridge `extraArgs`. The Chat tab loads history, polls, and sends only on the selected diagnostic run. A native overlay shows the guidance text and the provenance sentence from that same result map. It is removed on demand, on stop, on worker stop, when the window closes, when headless play finishes, when GUI playback pauses, stops, or finishes, when the scenario cleans up, and when the live session closes. In-page HTML is the agent path and is labeled `agent_injected`. A ranked handle keeps the browser, tab, and frame from the query that produced it, including a hit with no visual geometry. Query origin is the calling thread's engine Session, not another thread's join pointer, including a first query that does not yet have a Session. A failed page-session open does not focus the destination tab. A public window or tab switch does not report an open handle when the page session did not open or the page target could not be confirmed. A null page target is not browser-level success for a specific tab when the driver published `se:cdp` or a Chromium `debuggerAddress` (`goog:chromeOptions` or `ms:edgeOptions`). Closing a tab focuses the surviving tab when that page session opens; otherwise the close fails and the engine session is not left on the closed tab. Live surface ids use the destination tab's own page target. When the current Selenium handle cannot be read, that tab is confirmed before `Target.getTargetInfo`, or the target already remembered for that tab is reused. Selenium is put back on the thread's focused tab when one is known, including when the destination target cannot be read. A blank target does not start a new counter, and the active window's target is never stored under a different tab. A missing frame tree or null window-handle list fails cleanup instead of skipping documents. Scenario cleanup still finishes native overlays and later object cleanups when in-page cleanup throws. Closing a tab focuses the surviving tab. An empty iframe `name` or bridge attribute is restored as empty, and a restore that does not succeed fails the command. Cleanup, remove, and input polling reset to the top document, then visit ranked child frames even when the in-process site list is empty. A helper that does not confirm, or a tab, frame, or cleanup failure, fails the command, including scenario teardown. Two tabs on one browser keep two engine sessions, selected by the page target. `border` or `outline` shorthand that is not only a color, and CSS text `color`, use reversible DOM styling, say so, and restore the original style on unhighlight, instead of becoming only a CDP border color. Active nested documents (`data:application/xhtml+xml`, `data:image/svg+xml`, and the XML MIME types) are stripped along with `data:text/html`. Live surface ids are taken from the destination page after the handle's owner is selected, shared across run ledgers on that page, and a ledger that already used `pkb-N` does not hand that id out again.


`workbench_step_override_compile` sends the Java source template to the consumer worker. The source must contain `{{CLASS_NAME}}`; worker-side Pickleball remains responsible for compilation, generated classloaders, matching, replacement, captures, and execution.

Mutating live tools require the agent control lease. `workbench_request_save` never writes the original feature until the human Allows it in the UI, or until the explicit stdio tool call itself is the headless approval. Deny, Take control, and an unsavable demo buffer leave the file unchanged.

Controller/runtime failures are returned as MCP tool results with `isError=true`. They are not printed as arbitrary protocol output. Exploratory `FAILED` step, mapping, browser, or service-call results remain ordinary JSON tool results with `isError=false`.

## Scope boundary

Workbench MCP and Swing intentionally do not expose a generic IDE or build system. They do not add generic file editing/search, arbitrary process management, generic Maven/Gradle task execution, Gradle Tooling API project browsing, source navigation, collaboration tools, or git checkout/reset of the consumer HEAD. `workbench_go` is the one new navigation tool; it peeks pack copies and does not replace the live buffer.

## Dependency and artifact checks

The build proves the controller boundary with `verifyStrictControllerIsolation` and its component tasks:

- `pickleball-control-protocol` has no non-JDK dependency;
- the only Workbench project dependency is `pickleball-control-protocol`;
- the Workbench compile/runtime graph contains no root Pickleball, behavioral control API, Cucumber, Selenium, or REST-assured path;
- the Workbench executable contains its controller, GUI, MCP, protocol client, and runtime isolation guard;
- top-level and nested Workbench entries and service descriptors contain no core/worker implementation or nested Pickleball runtime;
- the published Workbench POM has no dependencies;
- the MCP convenience artifact / Jackson 3 path is not used;
- the outer Pickleball JAR contains exactly one opaque Workbench payload whose SHA-256 matches the standalone controller JAR;
- Workbench/MCP entries are not flattened into the outer runtime namespace; and
- the nested payload has the expected Workbench `Main-Class`.

Report the executable size and resolved MCP SDK artifacts with:

```powershell
.\gradlew.bat :pickleball-workbench:reportWorkbenchMcpImpact
```

The direct MCP dependencies are:

```text
io.modelcontextprotocol.sdk:mcp-core:2.0.0
io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.0
```

## Manual UI acceptance for the live player/editor

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"
$workbenchCp = "$workbenchJar;<resolved OpenJFX/MCP/Jackson controller libs>"
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication ui ".\maven-consumer-project"
```

Use the UI-owned worker for runtime checks; do not run `worker-check` or `live-check` concurrently with the UI.

1. Verify the top-level layout has a scenario name/tag filter rail (feature-file filter collapsed), the Live Gherkin text editor and compact Step Editor in the center, and exactly Mapping / Terminal / Explorer / Report on the right.
2. Confirm the default buffer is the Workbench demo scenario and includes `navigate to: URL.home` plus a click on the local test site when no picker scenario is selected.
3. Filter scenarios by name using contains (default) and the other match modes; confirm matching is case-insensitive and applies to the Scenario / Scenario Outline title.
4. Filter with include tags (AND) and exclude tags (NOT), with and without `@`, and confirm Feature-level tags apply to scenarios in that feature.
5. Confirm **Filter by feature** is collapsed by default and that name/tag filters then apply to every catalog scenario. Opening it still supports multi-select and Feature name vs file path.
6. Click a filtered scenario and verify it loads into the live Gherkin text editor. Tab at the start of an empty or colon-only line inserts `:`; Shift-Tab removes one leading `:`; mid-line Tab does not prepend `:`. Typing `Gi` at line start offers Given completion.
7. Click different scenario lines and verify the playhead highlight moves immediately to the clicked step.
8. Edit previously typed or previously executed Gherkin directly in the Live Scenario Editor and verify the line text updates in place.
9. Press global **Play** after seeking the playhead to a later step and verify execution still starts from the first executable step in a fresh worker context.
10. Use **From Here** on a later executable step and verify playback starts there and continues through the rest of the buffer.
11. Use **Step** in the Step Editor and verify isolated `executeStep` execution that leaves automatic playback paused.
12. Let a run reach the end and verify the player stays in **Waiting for step**. Type a new step and press Enter; the step is appended and executed without dropping out of play.
13. Treat **Pause** / **Stop** as presentation/control of automatic advancement only; they do not rewind browser or service side effects.
14. Verify Mapping has no `Current Scope` control and no hard-coded NodeMap choices. Top-level properties come from the worker ParsingMap and accept typed in-place edits.
15. Verify Terminal filters worker log files by level and continues as steps run, without writing to MCP stdout.
16. Verify Explorer lists retained runs from `reports/diagnostic-runs` only, or shows an honest empty state. Nested COMPONENT rows indent as children; after the callee ends, the next step is a sibling of that RUN. Play/Step walk events with screenshots when present, and a step without a PNG says so instead of leaving a blank stage. Click seeks; Open / Ctrl+click / double-click peeks the pack copy without replacing the live buffer. Play stops on the last beat. Report is the same `investigation.json` as `report.html`, business-first, no Mermaid.
17. Verify **Tools > Advanced Controls** still exposes Status, Recent Events, Step Overrides, Evidence, and Breakpoints.
18. Verify blocking runtime actions leave the Swing UI responsive.
19. Load a picker scenario, click **Save**, and cancel the confirmation; the original `.feature` file must be unchanged. Confirming copies only that scenario back into the originating file.
20. Attach an agent to `.pickleball/workbench/attach.json`, call `workbench_request_control`, and verify the banner plus locked play/edit/picker/filter/editor-view/mapping/save/worker controls. **Take control** remains enabled.
21. While the agent holds the lease, `workbench_request_save` shows Allow/Deny. Deny writes nothing. Take control cancels the wait without writing.
22. The default demo remains unsavable for both human Save and agent `workbench_request_save`.

## Regression

For the player state model and Swing/controller behavior:

```powershell
.\gradlew.bat :pickleball-workbench:test
```

For protocol, dependency, nested-artifact, and process-boundary checks:

```powershell
.\gradlew.bat verifyStrictControllerIsolation
```

For shared controller/MCP behavior:

```powershell
.\gradlew.bat :pickleball-workbench:verifyWorkbenchMcpStdio
```

For persistent worker/live behavior:

```powershell
$workbenchJar = ".\pickleball-workbench\build\libs\pickleball-workbench-<version>.jar"
$workbenchCp = "$workbenchJar;<resolved OpenJFX/MCP/Jackson controller libs>"

java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication sync ".\maven-consumer-project"
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication worker-check ".\maven-consumer-project"
java -cp $workbenchCp tools.dscode.workbench.WorkbenchApplication live-check ".\maven-consumer-project"
```

For changed consumer bridge behavior, use only the affected focused tags—never `@all`—and use parallelism 80 where practical:

```powershell
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test "-Dpkb_runvars.pkb_browser=CHROME_HEADLESS" "-Dpkb_runvars.pkb_parallel=80" "-Dpkb_runvars.pkb_tags=@control-bridge"
.\maven-consumer-project\mvnw.cmd -f maven-consumer-project\pom.xml -U test "-Dpkb_runvars.pkb_browser=CHROME_HEADLESS" "-Dpkb_runvars.pkb_parallel=80" "-Dpkb_runvars.pkb_tags=@step-override-bridge"
```

Run these invocations sequentially because the scenarios deliberately verify the process-global bridge bootstrap.

A warm launch adds `-o` (Maven) or `--offline` (Gradle) once the project has compiled test classes. If that offline launch fails only because a build plugin or provider was never downloaded (output contains `in offline mode` or `has not been downloaded from it before`), `discover` and `confirm` re-run the same command once without the offline flag and write `stage=maven-retry-online` to `launch-timing.txt`. Any other failure is reported as is. A normal `mvn test` is unchanged.

## Editor

The Editor tab adds, changes, and deletes element categories, configuration and data files, scenarios, scenario steps, and Java or JS/TS glue. Every button builds a `dalton` argument list and `DaltonCliForwarder` runs it. The tab does not open SQLite and does not load Dalton classes. MCP `dalton` and Gradle `daltonEdit` forward the same commands.

| Surface | Command |
|---|---|
| Categories | `dalton category list\|show\|put\|remove` |
| Files | `dalton file put\|delete\|show` |
| Scenarios | `dalton feature scenario-add\|scenario-modify\|scenario-delete` |
| Steps | `dalton feature step-add\|step-modify\|step-delete` |
| Glue | `dalton glue list\|show\|put\|remove` |

Deletes ask for confirmation, then pass `--confirm`. A conflict or an untracked database edit is refused unless `--force`. When Dalton is off the command writes the file. When Dalton is on, put sets `db_edited` and leaves the file until `dalton extract`. Scenario and step edits splice one region, keep comments outside it, normalize the indent of that region, and write valid Gherkin through `feature put`. `dalton scenario put` and `dalton step put` stay rejected. Java `@Given` / `@When` / `@Then` / `@And` / `@But` values stay one string literal. Files under `glue/js/` are source only; there is no JS/TS step runtime. A secret field must be a reference (`<name>` or `~MASK`). `pkb_dalton_log=edit` logs each edit, date-stamped, per entity, and size-capped.

## Java DOM engine

The Java DOM engine is part of Pickleball, not a side artifact. `dev.domengine` classes are inside the pickleball jar and the sources are inside the sources jar. There is no published `dom-engine` Maven artifact. `pkb_composite=off` stays on Selenium. The default locate path is the Java engine unless `pkb_composite_engine=rust` or an explicit binary is set.

Workbench does not call engine classes. MCP `domengine` and `DomEngineCliForwarder` only build `java -jar <pickleball.jar> domengine ...` (no `--project`, so engine flags are not given a Workbench flag). Gradle `domengine` forwards the same word through `PickleballWorkbenchLauncher` (`-PdomengineArgs=desktop,list`, default `version`). The step CLI `engine` is a different command. `DOMENGINE_LOG` or `COMPOSITE_LOG` names an entity (`cli`, `desktop`, `query`) or `all`. That log is date-stamped and size-capped, and it stays off until an entity is named.

`desktop attach --fixture FILE` is the recorded tree used by tests and headless CI. `desktop attach --live [--name WINDOW]` is the real desktop on the machine running the engine: Windows UI Automation, macOS Accessibility, or Linux AT-SPI. Live click and type act on that control. A missing session bus, a denied accessibility permission, or a missing input tool fails the command. It does not pretend the click happened. The same `--pipeline` used on a browser query is the desktop query. `frame`, `shadow`, and `pierce` are browser-only.
