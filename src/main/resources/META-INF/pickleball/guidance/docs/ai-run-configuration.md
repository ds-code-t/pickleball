# AI Run Configuration

This guide is the authoritative Pickleball contract for AI-controlled execution, deterministic reruns, profiles, RunVars, and retained run configuration.

## The three different concepts

Do not use these names interchangeably:

- `default_profile` is Pickleball's in-memory snapshot of the normal resolved project RunVars before a named profile or controlled RunVars are applied. It is reference data.
- `pkb_runvars` is optional **input** for a controlled run. It describes the RunVars the caller wants to control directly.
- `pkb_run_profile` is deterministic **output** generated after resolution. It serializes the final Pickleball RunVars and is retained in diagnostics for comparison and replay.

The execution flow is:

```text
normal configuration sources
        ↓
default_profile
        ↓
selected profile and/or pkb_runvars
        ↓
required execution-context inheritance
        ↓
profile template resolution
        ↓
final RunVars
        ↓
pkb_run_profile
        ↓
test execution / diagnostics
```

`pkb_run_profile` is internal derived output only. Supplying `pkb_run_profile` or `pkb_run_profile.<pkb_var>` from a JVM property, Pickleball property file, runner configuration, inline profile, or YAML profile is an error. Controlled execution uses only `pkb_runvars` / `pkb_runvars.<pkb_var>`.

## Normal configuration

Without `pkb_profile` or `pkb_runvars`, normal Pickleball configuration is resolved into `default_profile` and becomes the active RunVars. The runner then serializes those effective RunVars into `pkb_run_profile`.

For consumer-facing normal configuration, stronger sources override weaker sources in this order:

1. JVM `-D` system properties;
2. `globalTestProperties()`;
3. `pickleball_local.properties`;
4. `pickleball.properties`;
5. `globalTestDefaults()`.

This means `globalTestDefaults()` is the true fallback layer, shared `pickleball.properties` can override those defaults, the regular local property file can override shared configuration, runner properties can enforce project values, and JVM properties remain invocation-specific final overrides.

The resolved normal RunVars are then captured as `default_profile`. Profile and controlled-run resolution is a later stage; do not flatten named profiles or `pkb_runvars` into the normal source-precedence list.

## Named profiles

Profiles may be publicly defined in:

- `profiles.yaml`
- `profiles_local.yaml`
- inline `pkb_profile_<name>` properties

Matching names from `profiles_local.yaml` deep-merge over the shared `profiles.yaml` definition at property level. `pkb_profile` accepts one or more comma-separated names. Multiple selected profiles compose left-to-right; later values overwrite earlier values.

Named profiles remain reference namespaces. A profile does not receive every optional value from `default_profile` automatically. It does automatically receive any missing **execution-context RunVars** listed below so the profile remains runnable without repeating project wiring.

Profile values can explicitly reference retained profiles:

```yaml
qa:
  pkb_tags: "<default_profile.pkb_tags> and @qa"
  pkb_browser: firefox
```

Templates resolve after profile/direct composition.

### Explicit JVM RunVars

A JVM `-Dpkb_*` value that is an execution RunVar is treated as an explicit runtime override. With a selected named profile, it overlays the profile while preserving the profile's other RunVars. With controlled `pkb_runvars`, explicit JVM RunVars remain part of the run, but `pkb_runvars` wins conflicts. This lets a caller intentionally add one runtime override without losing the rest of a named profile.

This rule applies to JVM **RunVars**, not to `pkb_profile`, `pkb_runvars`, `pkb_run_profile`, lineage metadata, or other control/derived names.

## Required execution context

When a named profile or `pkb_runvars` omits one of these keys, Pickleball inherits it from the normal project configuration when available:

```text
pkb_glue
pkb_features
pkb_datapath
pkb_callpath
pkb_componentpath
pkb_configpath
```

These are inherited because they describe how the consumer project is wired to Pickleball resources. Optional execution choices such as `pkb_browser`, `pkb_tags`, reporting controls, and ReportPortal settings do not leak into a controlled run merely because they exist in normal configuration.

### Missing versus blank

For an inherited execution-context key:

```text
missing       -> inherit project value when available
nonblank      -> use the supplied value
blank / null  -> suppress inheritance and let the framework's normal Java fallback apply
```

Blank is therefore meaningful during inheritance and remains a blank tombstone in the canonical `pkb_run_profile`. The blank blocks the project value and tells the downstream Pickleball subsystem to use its established fallback behavior. This distinction matters: for example, a blank `pkb_datapath` preserves marker-only `data:` behavior that can use the running scenario's own feature source, while an explicit `src/test/resources/data` path forces lookup under that directory. Replaying the retained blank through `pkb_runvars` reproduces the same semantics.

The literal text `null` has no special meaning in compact assignment strings; it remains the literal string `null`.

## Controlled runs with `pkb_runvars`

Compact form:

```text
-Dpkb_runvars="pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS"
```

Expanded form:

```text
-Dpkb_runvars.pkb_tags=@smoke
-Dpkb_runvars.pkb_browser=CHROME_HEADLESS
```

Expanded form is useful when values contain punctuation that would otherwise need compact-string quoting.

Never combine compact and expanded `pkb_runvars` in one resolved configuration.

Never supply `pkb_run_profile` as input. Pickleball rejects compact and expanded external forms because the name is reserved for derived output.

### Compact assignment grammar

Assignments are separated by comma or semicolon:

```text
pkb_tags=@smoke, pkb_browser=chrome
pkb_tags=@smoke; pkb_browser=chrome
```

Quote a value when separators must be literal:

```text
pkb_name="Checkout, payment; receipt", pkb_browser=chrome
```

A quote is syntactic only when it starts the value. Mid-value quote characters are literal text.

Commas inside a Pickleball template token stay in the template:

```text
pkb_name=<orders #1,3>, pkb_browser=chrome
```

Protected values are serialized as `${protected:<pkb-key>}` and restored from normal secure configuration. Never substitute a secret into an AI prompt, committed command, diagnostic description, or retained profile.

## Templates in controlled RunVars

Controlled RunVars can reference `default_profile` or named profiles:

```text
-Dpkb_runvars="pkb_configpath=<qa.pkb_configpath>, pkb_browser=firefox"
```

```text
-Dpkb_runvars="pkb_tags=<default_profile.pkb_tags>, pkb_browser=firefox"
```

Profile references are reference lookups, not inheritance. Required execution-context inheritance happens separately.

Runtime `<config:...>` / legacy `<configs...>` mappings do **not** participate in resolving `default_profile`, named profiles, `pkb_runvars`, or the final `pkb_run_profile`. For example, this is intentionally unsupported:

```text
pkb_configpath=<configs.someOtherPath>
```

Allowing runtime configs to choose the source from which runtime configs are loaded would create an initialization cycle.

## `pkb_configpath`

`pkb_configpath` controls where Pickleball loads the configuration documents exposed through the stable `configs` mapping namespace.

Examples:

```text
pkb_configpath=configs
pkb_configpath=classpath:environment/qa/configs
pkb_configpath=src/test/resources/environment/qa/configs
pkb_configpath=file:/opt/project/configs
```

The recommended mapping syntax does not change when the source path changes:

```text
<config:application.baseUrl>
<config:users.admin.name>
```

Legacy `<configs.application.baseUrl>` / `<configs.users.admin.name>` references remain supported for compatibility.

If `pkb_configpath` is absent or explicitly blank, the Java fallback remains the historical classpath resource root `configs`.

Pickleball deliberately does **not** normalize the public path semantics of `pkb_features`, `pkb_datapath`, `pkb_callpath`, or `pkb_componentpath` as part of this change. Use the documented semantics for each existing key.

## Canonical `pkb_run_profile`

After profile/RunVar resolution Pickleball serializes active RunVars in deterministic key order into `pkb_run_profile`.

Properties of the serialization:

- only execution RunVars are included;
- diagnostic lineage metadata is excluded;
- profile controls are excluded;
- explicit blank execution-context values are retained as replayable tombstones so subsystem fallback semantics are preserved;
- sensitive values use protected references instead of plaintext;
- ordering is deterministic.

Example:

```text
pkb_browser=firefox, pkb_configpath=configs, pkb_features=classpath:features, pkb_glue=com.example.steps, pkb_tags=@smoke
```

Diagnostics expose the sanitized final `runProfile`, its `runProfileFingerprint`, and whether direct controlled RunVars were active. Existing diagnostic JSON field names such as `directRunProfile` remain for compatibility even though the preferred input control is now `pkb_runvars`.

## Diagnostic rerun workflow

For an AI-controlled rerun:

1. Read the selected baseline run's retained `runProfile`.
2. Use that retained final RunVar set as the starting controlled contract.
3. Pass it back through `pkb_runvars` (compact or expanded).
4. Change only the RunVars required by the current hypothesis.
5. Supply lineage metadata separately.
6. Run the narrowest useful scenario/tag selection.
7. Verify the resulting `runProfileFingerprint` and source/comparison evidence before attributing a difference to the intended change.

Example compact rerun:

```text
-Dpkb_runvars="pkb_browser=firefox, pkb_features=classpath:features, pkb_glue=com.example.steps, pkb_tags=@checkout"
-Dpkb_investigation_id=checkout-217
-Dpkb_run_purpose=browser-comparison
-Dpkb_parent_run_id=<previous-run-id>
-Dpkb_baseline_run_id=<baseline-run-id>
-Dpkb_changed_variables=pkb_browser
```

For an agent's bounded confirmation `mvn test` (not `PickleballTests` human defaults of `pretty` / `@all`), include diagnostic evidence controls, headless Chrome, and high parallelism when more than one scenario will run:

```text
-Dpkb_runvars="pkb_tags=@the-failing-tag, pkb_name=The failing scenario, pkb_browser=CHROME_HEADLESS, pkb_parallel=auto, pkb_reportingmode=diagnostic, pkb_loglevel=warn, pkb_reportretention=failed"
```

Lineage metadata is not execution configuration:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

Do not put those keys inside profiles or `pkb_runvars`. `pkb_changed_variables` contains canonical RunVar names intentionally changed by the rerun; it is not a place for source paths, commits, reasons, or derived fields.

## Cucumber CLI projection

Direct controlled RunVars are authoritative. When `pkb_runvars` is active, projected Cucumber CLI tag/name/glue overrides are not merged into the controlled RunVar set. Put the intended effective values in `pkb_runvars` instead.

Normal non-controlled runs retain existing CLI projection behavior.

## ReportPortal and protected values

Native `rp.*` properties map generically to Pickleball `pkb_rp_*` aliases. Profiles and controlled RunVars use the Pickleball aliases. The ReportPortal bridge receives the resolved native values after alias synchronization.

Secrets such as API keys must come from normal secure configuration. Retained `pkb_run_profile` output uses protected references rather than plaintext secrets.

## Java API

Preferred direct-input API:

```java
PKB_props.runVars("pkb_tags=@smoke, pkb_browser=firefox");
```

or:

```java
PKB_props.runVars(Map.of(
        "pkb_tags", "@smoke",
        "pkb_browser", "firefox"
));
```

Configuration-source API:

```java
PKB_props.configPath("configs");
```

Canonical output getter:

```java
String resolved = PKB_props.runProfile();
```

There are no `PKB_props.runProfile(String)` / `runProfile(Map)` input setters. `runProfile()` is read-only output; use `runVars(...)` for input.

## Agent rules

When operating in a consumer project:

- when you launch tests and know the intended execution settings, default to `pkb_runvars` as the authoritative test-run input;
- use ordinary JVM `pkb_*` RunVars or `pkb_profile` instead only when intentionally testing normal configuration/profile precedence or when the user requests those semantics;
- never supply `pkb_run_profile` as input;
- treat `pkb_run_profile` as retained resolved output;
- preserve explicit blank values from a retained profile;
- inherit only the documented execution-context keys when constructing controlled runs from partial input;
- do not infer that a named-profile reference means inheritance;
- do not use `<configs...>` to resolve run configuration;
- do not rewrite existing path conventions merely to make them look consistent;
- never expose protected values;
- keep diagnostic lineage outside the RunVar set;
- prefer the retained run profile over manually reconstructing configuration from many source layers.

## AI agents

The agent-facing entry is Pickleball Workbench (`hint`, `discover`, `confirm` to find failures; `isolate` / `execute-step` for live debug). Set a **complete** Discover `pkb_runvars` rather than a partial overlay. Workbench `hint` prints the browser-ladder result and estimated integer parallel count. The browser ladder keeps a remote project `pkb_browser` (`SAUCE_*` / `GRID_*` / `REMOTE_*`); otherwise it prefers `CHROME_HEADLESS`. Unused Sauce/Grid yaml files are not auto-selected.

```text
pkb_browser=<browser ladder>
pkb_parallel=<conservative JVM estimate or auto>
pkb_reportingmode=diagnostic
pkb_loglevel=warn
pkb_reportretention=failed
```

plus the narrowest useful `pkb_tags` / `pkb_name`. Multi-scenario Discover/Confirm use that high parallelism. Live isolate stays one paused scenario on a headless CLI session started with Maven-exec `isolate`.

After Discover, inspect `pkb_run_profile` from `run-catalog.json`, `run-index.json`, or `summary.json`. Confirm and live isolate replay that retained profile through `pkb_runvars` (LastDiscoverSnapshot). If there is no prior Discover snapshot, Workbench says so; it does not silently re-resolve from project defaults.

Never supply `pkb_run_profile` as input. Workbench MCP `workbench_diagnostic_catalog`, `workbench_diagnostic_run`, and `workbench_diagnostic_summary` return the same retained `runProfile` when present. The consumer worker resolves the same snapshot internally through `PickleballRunner`; it does not accept `pkb_run_profile` as input.
