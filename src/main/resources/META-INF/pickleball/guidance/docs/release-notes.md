# Release notes

## 2.1.17

### Breaking changes

These three statuses apply only to Pickleball Workbench (`discover`, `confirm`, and the live session commands). A normal `mvn test` / `gradlew test`, Cucumber’s own exit code, and `DiagnosticReporter` are unchanged. Diagnostic mode still reports `NO_TESTS`. Nested `SCENARIO` / `COMPONENT` / `CALL` / `RUN` still fail when `CucumberScanUtil.listPickles` finds nothing.

- **EMPTY.** A Workbench `discover` or `confirm` that selects zero scenarios prints `status=EMPTY`, the effective tags, name, example, and browser, and exits non-zero. It does not print `status=PASSED`. The run record status is `EMPTY`. When the caller did not pass `--learned`, the record says `No scenarios matched …`.
- **TIMEOUT.** `--step-timeout` and `--run-timeout` on a live Workbench command record `TIMEOUT` and exit non-zero when that budget is exceeded. They do not use `pkb_stepMaxTime`.
- **STOPPED.** `stop --force` kills the Workbench worker and its browser process tree and records `STOPPED`. A cooperative `stop` without `--force` is unchanged and still waits.

### Date, time, and expressions

Previously passing expressions, steps, and comparisons stay the same text and the same result.

- An invalid `<{...}>` in `TRACE:`, `DEBUG:`, `INFO:`, `WARN:`, `ERROR:`, or `FAIL:` log text stays the literal text. A valid `<{...}>` and a valid `<name>` still resolve. The same invalid expression outside a log step still fails the step. `ERROR:` and `FAIL:` still hard-fail because of the log token, not because the expression was invalid.
- Nested `<...>` references, including a saved value that itself contains `>`, resolve past two levels and do not leave a stray `>`. One- and two-level nesting stay byte-identical.
- `equal to … within a margin of N minutes`, `within a margin of N minutes`, and `within N minutes` compare date-times only when the margin is a duration and both sides parse as date-times. Other strings stay a string compare, and the margin is ignored. Equal text that already compared as date-times still does.
- A calendar `DefaultOutputPattern` is also accepted as an input pattern, after the existing `DateTimeFormats`, so the calendar can parse its own rendered output. Patterns that already parsed still parse the same way.
- Pipe modifiers `next business day` and `business day on or after` roll to the calendar’s existing business dates. `next open`, `is business day`, and `+ N business days` are unchanged. An unknown modifier still throws `Unknown date/time pipe modifier`.
- Optional calendar setting `UnzonedInputZone: calendar` reads an unzoned input in the calendar zone. The default, and the values `UTC`, `GMT`, and `Z`, stay UTC. A zoned input is unchanged.

### Workbench

Normal scenario execution is unchanged. The feature index, plan, timeouts, and the reports below exist only on the Workbench path.

- `discover` and `confirm` use a hash-checked feature index. When the index can narrow the selection, the Workbench-built Maven command launches only those feature paths. An unreadable feature or an unsupported tag expression such as `xor` falls back to a full scan. `CucumberScanUtil.listPickles` is not changed.
- `plan`, and `discover --plan` / `confirm --plan`, resolve `--tags`, `--name`, `--example`, and `--select path/file.feature:line` to scenarios and Examples rows, including argument values. They do not build and do not open a browser. An empty plan warns.
- The Workbench-built Maven command adds the Surefire Cucumber engine filter. A consumer POM and a plain `mvn test` do not.
- `--tags` and `--name` are echoed. An unmatched quote or parenthesis is `Tag quoting error`. An invalid name regular expression is `Name quoting error`. `--tags-file` and `--tags-b64` pass an expression the shell would split. Shared `pkb_tags` / `pkb_name` parsing is unchanged.
- `--agent` keeps a per-agent `last-discover-agent-<id>.json`, and each run keeps `last-discover-run-<id>.json`. The shared `last-discover.json` is still the latest discover. Confirm without `--agent` still reads that shared file. Discover reports the current run’s profile, not the newest catalog row.
- Session start closes stdin and logs `start` only after the session is healthy. `No healthy Workbench session` names `never-started`, `still-starting`, `crashed`, or `missing-session-file`.
- Worker start timeout is `pkb_workbench_worker_start_timeout` (system property, `PKB_WORKBENCH_WORKER_START_TIMEOUT`, or a `pkb_runvars` member). The default is 180 seconds. It is read only by Workbench. A startup timeout names the elapsed time, the stage (`compile`, `classpath`, `worker-launch`, `anchor-parse`, `paused`), and the last worker output.
- `--stop-after` is the live-session alias of the existing one-shot breakpoint.
- Failure text captured beside the run can include `final XPath`, `raw match count`, `visible count`, `rejection`, and `expected` versus `actual`. Core `events.jsonl` and `trace.jsonl` are not rewritten. A core `summary.json` is not overwritten. Workbench writes `workbench/summary.json` and `workbench/trace-index.json`. If the core summary is absent, the same body is also `summary.json`. Console text for the Workbench run is `runs/<id>/console/<scenario>.txt`. A compact line `summary status=… scenarios=… steps=…` is printed.
- `inspect-page --html=<file>` lists interactive elements and the Pickleball phrase that would target each one. `find-element --category=… --text=… --html=<file>` prints the built XPath, the raw match count, and the visible count. Neither command clicks. The live `ElementControl.inspect` signature is unchanged.
- A locked Workbench directory install retries the move three times, then copies and deletes the staged tree.
- A CLI error that wraps an `IOException` includes that cause. Session-start and sync failures record a plain-language reason on the run.
- The config copy under the Workbench run folder redacts `password`, `passwd`, `secret`, `token`, `credential`, `api_key` / `api-key`, and `access_key`. The project file a normal run reads is not modified.

### Clarifications

These were already true. 2.1.17 only writes them down.

- The shared `last-discover.json` is the latest discover. Two agents that omit `--agent` can replay each other’s tags. Maven `target/` and Gradle `build/`, and the Workbench `live/classes` overlay, are generated sync input. Do not hand-edit them.
- `pkb_browser` names a configuration under the `configs` mapping. Workbench discover uses the browser ladder, often `CHROME_HEADLESS`, and prints that name. A normal `mvn test` uses the project value. A `pkb_runvars` member wins over `-Dpkb_browser`.
- `gc-runs` is the explicit payload sweep. `finish` does not run it. `--retention=all|failed|none` is Workbench `discover` / `confirm` only. Discover’s default retention is `failed`. Neither flag changes a normal `mvn test`.
- Suite tags such as `@all` and agent tags are different. `@agent-pointer-eval` must not also carry `@all`. Workbench tag expressions are Cucumber-style `@tag` with `and`, `or`, and `not`, case-sensitive after `@`.

## 2.1.16

### Breaking change

Element text matching is a full match. `matching` used to succeed when the pattern was found anywhere in the element text (`Pattern.find`). It now uses `Matcher.matches`, the same full-match rule phrase `matches` already used, and it honors quote types. A single quote on either side is case-insensitive. A backtick on either side is raw text. There is no regex warning.

On a page with buttons Save, Save All, and Autosave:

- `click the Button matching "Save"` clicks only Save
- `click the Button matching "Save.*"` clicks Save or Save All
- `click the Button matching 'save all'` clicks Save All

`containing` is still a literal partial match. For a partial regular expression, wrap the pattern in `.*`, for example `".*Sa.e.*"`. For exact text that contains `(` `)` `.` or `$`, use `equals` or escape the pattern. Quote types change case and whitespace only. There is no new syntax.

### New features

- Java value API on `tools.dscode.Values`: `getValue` / `saveValue` and the run, scenario, root-scenario, step, default, override, and singleton pairs, plus Jackson `Class<T>` overloads. See [Java values](java-values.md).
- `softFail(String message)` records a soft failure. The step continues, the scenario fails at the end, and `RUN IF SCENARIO SOFT FAILED` runs.
- `throttle(String key, int seconds)` waits until `seconds` have passed since the last call with that key in the JVM. There is no Gherkin syntax.
- `~XML-ESCAPE;` escapes `& < > " '`. It works everywhere conversion directives work, and it is rejected on a `MAP "key" TEXT/OBJECT VALUE` destination key.
- Custom reference prefixes. `registerPrefix` adds `<password:testAccount>`. `secret(value)` prints `******`. `~MASK;` stores a masked value that stays masked in logs, reports, the terminal, and Workbench. `<pw~UNMASK;>` returns the plain value.
- Experimental JDBC service calls. A `REQUEST.endpoint` that starts with `jdbc:` runs a JDBC call. HTTP calls are unchanged. Drivers for PostgreSQL, Microsoft SQL Server, Oracle, MariaDB, and H2 are published with Pickleball. See [Experimental features](experimental-features.md).

### Other

- XML readers reject `DOCTYPE` and `ENTITY` declarations. Normal XML is unchanged. `~XML;` uses Jackson's `XmlMapper` conversion, documented with captured output in [Mapping and templating](mapping-and-templating.md).
- A `file:/...` or unprefixed `/...` path that is not on the classpath fails with a hint to use `wd-file:/...` for a filesystem path.
- Literal key escapes and registered-object cleanup are documented. The existing `cleanup` property runs at scenario end after a pass, a soft failure, or a hard-failed step.
- A hard-failed scenario and a deferred `softFail` both fail the Cucumber scenario after cleanup. The workbook status and the JUnit result stay failed together. `CurrentScenarioState.absorbRecordedScenarioFailure` and `discardDeferredSoftFailure` are not a consumer API.
- A static import of `Values` includes the documented methods and `Values.Context`. Framework helpers are not part of that import.
- Sample `pkb_callpath` and `pkb_componentpath` values use the resources-folder names `calls` and `component`. When those properties are unset, the defaults remain `src/test/resources/calls` and `src/test/resources/component`.
