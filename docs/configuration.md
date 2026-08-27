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
pkb_run_profile
pkb_options
```

`pkb_run_profile` is the canonical resolved output and is reserved for Pickleball. External `pkb_run_profile` / `pkb_run_profile.<pkb_var>` input is rejected; use `pkb_runvars` / `pkb_runvars.<pkb_var>` instead.

Diagnostic lineage is separate metadata:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

Lineage survives controlled execution but is excluded from `pkb_run_profile` and `runProfileFingerprint`.

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

`PKB_props.runProfile()` is a getter only.

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
| `pkb_environment` | `QA` | project environment label |
| `pkb_browser` | `chrome` | browser configuration name looked up under the `configs` mapping (`CHROME_HEADLESS` uses the consumer yaml when present, otherwise Pickleball's bundled headless Chrome) |
| `pkb_profile` | `qa,browser_firefox` | selected named profile(s) |
| `pkb_runvars` | `pkb_tags=@smoke, pkb_browser=chrome` | compact controlled RunVar input |
| `pkb_runvars.<pkb_var>` | `pkb_runvars.pkb_browser=chrome` | expanded controlled RunVar member |
| `pkb_run_profile` | generated assignment string | canonical resolved RunVar output; external input rejected |
| `pkb_parallel` | `4`, `auto` | parallel scenario count; `auto` resolves at run start to a conservative JVM estimate and stamps the integer into `pkb_run_profile` |
| `pkb_loglevel` | `debug` | console log level |
| `pkb_reportingmode` | `diagnostic` | diagnostic evidence pipeline |
| `pkb_reportretention` | `all`, `failed`, `none` | automatic evidence/report retention |
| `pkb_diagnostic_output` | `reports/diagnostic-runs` | optional diagnostic output root |
| `pkb_platformlog` | `default`, `default+git`, `none`, etc. | platform/caller log stamps |
| `pkb_gitsnapshot` | `metadata`, `diff`, `none` | diagnostic Git/source provenance |

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
