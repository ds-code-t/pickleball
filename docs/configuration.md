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

## Agent coordination

Different consumer agents can run different tests at the same time. The shared files under the consumer `.pickleball` directory are a bulletin board. Run data stays private to each run.

A private `.pickleball/runs/<run-id>/` directory is only for an agent run or a Workbench run (`-Dpkb_run_id`, `-Dpkb_agent_id`, or a Workbench worker). A normal `mvn test` does not open that folder, does not set Chrome or Edge `user-data-dir`, and writes the composite HTML report to `reports/cucumber-report.html`. Diagnostic packs and scratch stay on their 2.1.13 paths for a normal test. The short log and inbox stay where they are.

When that private run starts, Pickleball copies the consumer project's configs into `.pickleball/runs/<run-id>/config` and reads only that copy. Browser yaml and the other files under the resolved `pkb_configpath` are in the copy, including a bundled `CHROME_HEADLESS.yaml` when the project does not already have one. The copy is taken once. Starting the same run again does not overwrite it, so an edit made for that run stays. A second run gets its own copy from the project files and does not see the first run's edits. Neither run writes the project configs. `pkb_configpath` in the run profile stays the project path. The run directory is not stored there. A normal `mvn test` does not copy anything and keeps reading the project configs.

An agent or Workbench run that does not pass a run id gets one (`--run-id` or `-Dpkb_run_id`). An agent may pass its own id. An omitted agent id is generated and printed (`--agent` / `--agent-id` or `-Dpkb_agent_id`). Optional group, sequence, who, and why are `-Dpkb_run_group`, `-Dpkb_run_sequence`, `-Dpkb_run_who`, and `-Dpkb_run_why`.

Reports, diagnostic packs, scratch, and a headless Workbench session file for that private run live under `.pickleball/runs/<run-id>/`. Local Chrome and Edge in one run do not share one profile. Each parallel worker gets `browser-profile/<worker>`. Remote drivers are unchanged. Options that already contain `user-data-dir` are left alone. Two runs never write the same output folder or the same live session file.

`pkb_compositeReport=false` suppresses the composite `reports/cucumber-report.html`. `pkb_scenarioReport=false` suppresses the per-scenario workbook HTML that `Log.closeAll` writes. A normal test leaves both unset and still writes both reports. An agent run, and a Workbench run including a person collaborating with an agent, default both to false. Agents keep diagnostic logs. An explicit `true` writes that report again. `pkb_reportingmode=diagnostic` still bypasses automatic HTML, ReportPortal, and XLSX unless one of those HTML run vars is explicitly true.

The run record is `.pickleball/runs/<run-id>/record.json`: run id, agent id, optional group, sequence, who, why, what was learned, start time, stop time, status, and the path to the run data. The short log only points at that record.

The short log is `.pickleball/agent-log`. Read it before a run, after a run, and during a long session, instead of the dense diagnostic log. Each line is a timestamp, agent id, run id, `start` or `stop` or `note`, a one-line purpose, and the path to the run record. Appending does not take a file lock. A dead agent must not block the others. On each write, lines older than 3 days are dropped. Newer lines stay. A dropped run whose directory remains keeps a one-line stub so the index does not vanish. Do not put fix history in the short log.

`inbox/<agent-id>/` is directed mail for that agent. `take` deletes that note. `list` does not. `inbox/any` is not first-taker-wins. A note to every agent is a post, not a consumed inbox file. The inbox is not a second log and not an assignment framework.

The board is three files, not more inbox folders. `.pickleball/presence/<agent-id>.json` is a heartbeat (`agent id`, `lastSeen`, current run ids). An active agent may drop a row older than 15 minutes. That drop removes the row only. `.pickleball/posts/<id>.json` is a hint (`id`, `from`, `to` an agent id or `all`, optional run id, one line, `createdAt`, `updatedAt`, `expiresAt`, optional `ackedBy`). Default expiry is 24 hours from the last update. One hour is appropriate for "I have the window" and is not the default. List does not delete. Ack is optional. `.pickleball/history.log` is an append-only summation (version, files, fix, branch, commit, merge, run id), trimmed from the front above 10 MB. It is not a mailbox. Agents are not daemons. Launcher commands are one-shot: `presence --touch|--list|--sweep`, `post --write|--list|--renew|--sweep`, `history --append|--tail`, `gc-runs`, `gc-versions`, and `use-version --version=<version>`.

`browser-profile/<worker>` under a run is deleted only by `gc-runs`, and only when that run has a stop line, nothing has the profile open, and no agent holds the run. Chrome `SingletonLock` and an Edge `lockfile` both mean the profile is open. Not when Cucumber returns, and not on finish alone. Remote drivers and a consumer-supplied `user-data-dir` are not this directory. Dense evidence stays for the run open in Workbench and for the last failed run. The last failed run is the latest stopped run whose status is FAILED, or a STOPPED record with a failure count, a failed-scenario flag, or a non-empty failure summary. `finish` writes FAILED when that run had failures and STOPPED only when it did not. Other stopped payloads may go 24 hours after `stoppedAt`. Always leave `record.json`, the sparse index, and `pkb_run_profile`. Never delete a run with no stop line. `finish` and `stop` only write `stoppedAt` and a stop line. `current.json` is a version pointer (`pickleballVersion`, `complete`, `updatedAt`), not a path to the dependency jar. `export-guidance` of version X writes `v/X`, records `v/X/.last-used`, and points `current.json` at X. It leaves every other `v/<other-version>/` tree on disk. `use-version --version=<version>` does the same pointer move when that tree is already a complete export, and refreshes the root aliases and `open/` from that tree. It does not invent a missing export and does not delete the tree being left. Session, attach, and last-discover stay under `v/<version>/workbench` for the version that owns them. A run records the version it started with. Moving the pointer does not retarget a RUNNING run. `gc-versions` is explicit. It is not run from export, use-version, or finish. It deletes a version tree only when it is not the usable current pointer, last use is at least 3 days ago, no RUNNING run started with it, and its workbench has no live session, attach, or worker. A missing `.last-used` counts as last used now. An unused version tree is expired, not stale. Stale still means an untrusted export.

When a Workbench window is already open, only one agent drives it. Other agents stay headless on their own run ids. Two headless isolates each get their own session file under the run directory. Commands without `--run-id` still target the open window, or the legacy shared CLI session when no window is open. Commands with `--run-id` look only at that run's session file. The window is a view of `.pickleball/runs/`. Its run dropdown and `show-run --run-id` read that same directory. `open-window --run-id` opens the one window on a run the agent already has and does not start a test. `close-window` closes the window and does not delete the run directory, the profile, the session file, or `.pickleball/agent-log`. Play, step, and stop apply only to the one live run. Another agent may view a different run read-only. Do not open the GUI for your own testing. Open it to show a person a specific run, or when they ask. Close it when you are done showing it. While testing for yourself, stay headless. Opening the window loads the run you already have. It does not start a second test.

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

`DiagnosticCli resolve-runvars` and Workbench `hint` print that preview, including per-key provenance (`override`, `runvars`, `profile`, `jvm`, `inherited-context`, `default`, `properties`). The preview is the launcher JVM, not the Discover worker. Do not treat its `pkb_environment` as the environment Discover will use. Trust the run record after Discover. Agents resolve → inspect → complete map → `pkb_overriderunvars` → compare fingerprint. Sealed input is `pkb_overriderunvars` only.

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
pkb_configpath=classpath:environment/qa/configs
pkb_configpath=src/test/resources/environment/qa/configs
pkb_configpath=file:/opt/project/configs
```

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

Missing or blank `pkb_configpath` uses the historical Java fallback `configs`. Pickleball first completes RunVar/profile resolution and only then reloads the global `configs` mapping from the final path. This preserves a one-way initialization dependency and prevents runtime config data from selecting its own source.

The existing path semantics for `pkb_features`, `pkb_datapath`, `pkb_callpath`, and `pkb_componentpath` are intentionally unchanged. See [Config Files and Resource Mapping](config-files-and-resource-mapping.md).

## Common properties

| Property | Example | Purpose |
|---|---|---|
| `pkb_glue` | `com.example.tests` | Cucumber glue packages |
| `pkb_features` | `classpath:features` | top-level feature location(s) |
| `pkb_componentpath` | `src/test/resources/component` | component-scenario location |
| `pkb_callpath` | `src/test/resources/calls` | reusable service-call location |
| `pkb_datapath` | `src/test/resources/data` | scenario-data / rooted `data:/` lookup |
| `pkb_configpath` | `configs` | source behind the stable `configs` mapping |
| `pkb_tags` | `@smoke and not @slow` | Cucumber tag expression |
| `pkb_name` | `Checkout.*` | scenario-name expression |
| `pkb_example` | `1 2 5 3.4 7-11` | Examples-row filter applied after tags and name. Not a tag |
| `pkb_environment` | `QA` | project environment label |
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
| `pkb_loglevel` | `debug` | console log level |
| `pkb_reportingmode` | `diagnostic` | diagnostic evidence pipeline |
| `pkb_compositeReport` | `true`, `false` | composite `reports/cucumber-report.html`; unset writes it on a normal test and suppresses it on an agent or Workbench run |
| `pkb_scenarioReport` | `true`, `false` | per-scenario workbook HTML; same default as `pkb_compositeReport` |
| `pkb_reportretention` | `all`, `failed`, `none` | automatic evidence/report retention |
| `pkb_diagnostic_output` | `reports/diagnostic-runs` | optional diagnostic output root |
| `pkb_platformlog` | `default`, `default+git`, `none`, etc. | platform/caller log stamps |
| `pkb_gitsnapshot` | `metadata`, `diff`, `none` | diagnostic Git/source provenance |

## Example row filter

`pkb_example` keeps specific Examples rows from scenarios that `pkb_tags`, `pkb_name`, and, on a component or `listPickles` scan, `pkb_featurename` have already selected. Where those filters select scenarios, `pkb_example` runs after them. It is not a tag and it does not extend Cucumber tag expressions (`and` / `or` / `not`). It has no `cucumber.filter` alias. `pkb_name` is a search of the Scenario or Scenario Outline title, the same rule Cucumber uses when it discovers scenarios. `Partial name` selects `Partial name example rows`. It still selects every row of that scenario until `pkb_example` runs. The Maven run keeps a scenario that this search selected. A normal Maven run applies the same filter after Cucumber's tag and name filters. It does not start honoring `pkb_featurename` on that engine path.

Absent or blank `pkb_example` adds no filter.

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

The resolved integer is stamped into the final RunVars and `pkb_run_profile`. Workbench `hint` prints that estimated number in the recommended Discover `pkb_runvars` command.

## Bundled `CHROME_HEADLESS`

`pkb_browser` names a configuration object under the loaded `configs` mapping. Resolution for `CHROME_HEADLESS`:

1. If the consumer `pkb_configpath` / configs mapping already contains `CHROME_HEADLESS` (or a case-insensitive named browser yaml such as `CHROME_HEADLESS.yaml`), that local override wins, including headed chrome.yaml-style configs.
2. Otherwise Pickleball injects a framework-bundled `CHROME_HEADLESS` resource from `META-INF/pickleball/configs/CHROME_HEADLESS.yaml` inside the Pickleball JAR.

The bundled headless config uses `--headless=new`, a fixed `--window-size=1920,1080`, no `MAXIMIZE`, and `QUIT_LOCAL_DRIVER`. Consumer `CHROME`, `EDGE`, `GRID`, and `SAUCE` yaml files are unchanged. Agents can set `pkb_browser=CHROME_HEADLESS` without copying yaml into the project.

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

## Agent Discover browser ladder

Workbench Discover/Confirm do not blindly MUST-use `CHROME_HEADLESS` for every project:

1. If `default_profile` / runner / retained `pkb_run_profile` `pkb_browser` is already a remote farm name (`SAUCE_*`, `GRID_*`, `REMOTE_*`, or clearly non-local), keep it. Those consumers run exclusively on the external farm.
2. Otherwise prefer `CHROME_HEADLESS` (consumer yaml if present, else the JAR-bundled config above).
3. If local headless cannot start and the project already defines and uses GRID/SAUCE/REMOTE as its `pkb_browser`, fall back to that project browser. Do not pick Sauce/Grid merely because unused yaml files exist in `configs/`.

Isolate stays one scenario and does not raise `pkb_parallel`.

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
