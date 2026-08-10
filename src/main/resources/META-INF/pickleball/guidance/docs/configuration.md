# Execution Configuration

> **Runnable examples:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) exercises normal execution settings. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) exercises default data-path behavior and `data:/` file lookup. [`configuration-system-properties.feature`](../maven-consumer-project/src/test/resources/features/configuration-system-properties.feature) verifies JVM `pkb_*` value quote normalization and the direct-run/lineage contract. The consumer [`profiles.yaml`](../maven-consumer-project/src/test/resources/profiles.yaml) is a syntax example for run profiles.

Pickleball execution properties select scenarios and control how they run. Property names are case-insensitive; this page uses lowercase `pkb_` names for consistency.

```properties
pkb_tags=@smoke
pkb_browser=chrome
pkb_loglevel=debug
```

## Configuration sources and precedence

The legacy/default configuration is still resolved from the existing sources. When the same setting appears in several places, the stronger source wins:

1. JVM system property;
2. `globalTestProperties()`;
3. `pickleball_local2.properties`;
4. `pickleball_local.properties`;
5. `globalTestDefaults()`;
6. `pickleball.properties`, when no stronger value has been supplied.

That fully resolved legacy state becomes the in-memory `default_profile`. If no profile is selected, Pickleball deep-copies `default_profile` into the active run profile, preserving existing behavior.

Use `globalTestDefaults()` for normal team defaults. Reserve `globalTestProperties()` for values that must not be replaced locally.

## Runner defaults

```java
@Override
public void globalTestDefaults() {
    PKB_props.glue("com.example.pickleball");
    PKB_props.features("classpath:features");
    PKB_props.plugins("pretty");
    PKB_props.tags("@all");
    PKB_props.browser("chrome");
}
```

See the working [consumer runner](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java).

## Local overrides

Create `src/test/resources/pickleball_local.properties` and add normal Pickleball properties without `-D` prefixes:

```properties
pkb_tags=@forms
pkb_environment=QA
pkb_browser=chrome
pkb_debugbrowser=true
pkb_loglevel=debug
pkb_parallel=4
```

Use `-D` only on the command line:

```bash
mvn test -Dpkb_tags="@forms and @state-assertions" -Dpkb_loglevel="debug"
```

### Quoted JVM `pkb_*` values

Some shells and Maven launch paths preserve quotes around the value in an argument such as:

```bash
-Dpkb_loglevel="INFO"
```

For JVM system properties whose names begin with `pkb_`, Pickleball removes one matching outer pair of single or double quotes before applying configuration precedence. Embedded quotes remain part of the value.

Only JVM `pkb_*` system-property values receive this command-line normalization. Non-Pickleball JVM properties and values authored in `.properties` files keep their normal Java semantics.

## Pickleball run profiles

Profiles are optional named groups of Pickleball RunVars. They are loaded from classpath-root resources in this order:

```text
profiles.yaml
profiles_local.yaml
profiles_local2.yaml
```

Matching profile names are deep-merged at property level, with later/local files overriding earlier fields. Profile names are case-insensitive. `default_profile` and `run_profile` are reserved names.

Example:

```yaml
qa:
  pkb_glue: "<default_profile.pkb_glue>"
  pkb_features: "<default_profile.pkb_features>"
  pkb_tags: "<default_profile.pkb_tags> and @qa"
  pkb_environment: QA
  pkb_browser: CHROME_HEADLESS

browser_firefox:
  pkb_browser: firefox
```

Select one profile:

```properties
pkb_profile=qa
```

Or compose several profiles into one active run profile:

```properties
pkb_profile=qa,browser_firefox
```

Profiles merge left-to-right, so the later profile wins when both define the same RunVar. Selecting a custom profile does **not** implicitly merge `default_profile`. Include it explicitly when desired:

```properties
pkb_profile=default_profile,qa
```

All source profiles remain available for template references even when they are not selected:

```yaml
qa:
  pkb_tags: "<default_profile.pkb_tags> and @qa"
  pkb_browser: "<browser_defaults.pkb_browser>"

browser_defaults:
  pkb_browser: chrome
```

Profile templates use the existing Pickleball `<...>` mapping/template resolver and are resolved after the final composite profile is built. Cyclic or unresolved profile references fail configuration with a descriptive exception.

### Inline named profiles

A profile can also be defined anywhere a normal `pkb_*` property can be supplied:

```properties
pkb_profile_smoke=pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS
pkb_profile=smoke
```

The text after `pkb_profile_` is the profile name. Inline profile definitions are definitions only; they are not RunVars and are not used unless selected. The inline property value follows the normal Pickleball property-source precedence; the resulting inline definition is then merged over any same-named YAML definition, making it the final profile-definition override layer.

Compact profile assignment strings use a small deterministic grammar:

- comma or semicolon separates assignments;
- the first `=` in an assignment separates its key from its value, so later `=` characters are ordinary value text;
- a single or double quote becomes a quoting delimiter only when it is the first non-whitespace character after `=`; quotes appearing later in an unquoted value are literal text;
- a quoted value may contain commas and semicolons; inside it, `\\` represents a literal backslash and a backslash before the matching quote represents that literal quote;
- after a closing quote, only whitespace and the next assignment delimiter/end are allowed; malformed quoted values fail clearly;
- `<...>` template spans remain one token while assignments are identified, including template selectors containing commas; the parsed values are resolved afterward, so delimiters produced by template resolution cannot create additional assignments.

For example:

```properties
pkb_profile_custom=pkb_tags="@a, @b"; pkb_browser=chrome; pkb_rp_description=Bob's "QA" run
```

Here the quotes around `@a, @b` protect the comma, while the apostrophe and double quotes in `Bob's "QA" run` are literal because they do not begin the value.

Runner code can use the convenience helper:

```java
PKB_props.profileDefinition("smoke", "pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS");
PKB_props.profile("smoke");
```

A named YAML or inline profile may itself set `pkb_run_profile`. If that profile is selected, the `pkb_run_profile` value becomes the direct full RunVar override and the other RunVars in the composed profile are discarded. With multiple selected profiles, normal left-to-right merging determines the winning `pkb_run_profile` value. The compact string form remains valid:

```yaml
agent_direct:
  pkb_run_profile: "pkb_tags=<default_profile.pkb_tags> and @agent; pkb_browser=firefox"
```

YAML profiles may alternatively use a map, which avoids compact assignment parsing entirely and is preferred for punctuation-heavy values:

```yaml
agent_direct_map:
  pkb_run_profile:
    pkb_tags: "<default_profile.pkb_tags> and @agent"
    pkb_browser: firefox
    pkb_rp_description: "Bob's QA, phase 2; retry"
```

`pkb_run_profile` is treated atomically while profiles are merged, so a later selected/profile-resource definition replaces an earlier direct-run control rather than deep-merging two complete direct profiles.

```properties
pkb_profile=agent_direct
```

### `pkb_run_profile`: complete direct RunVar override

`pkb_run_profile` is both the retained serialized form of the final active RunVars and an optional direct input.

After normal/profile resolution, Pickleball stores a deterministic key-sorted, comma-separated, quote-aware representation such as:

```text
pkb_browser=CHROME_HEADLESS, pkb_features=classpath:features, pkb_glue=com.example.pickleball, pkb_tags=@smoke
```

The serializer uses double quotes only when needed and escapes literal double quotes/backslashes inside quoted values. For every retained nonblank scalar RunVar, serializing and reparsing the compact form preserves the exact value, including surrounding whitespace and punctuation.

`pkb_run_profile` itself, `pkb_run_profile.<pkb_var>` expanded members, `pkb_profile`, inline profile definitions, `pkb_options`, and diagnostic lineage metadata are control/metadata properties and are not copied into `RunVars`.

When `pkb_run_profile` is supplied explicitly, it is a **full RunVar override**:

```bash
mvn test "-Dpkb_run_profile=pkb_glue=com.example.pickleball, pkb_features=classpath:features, pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS"
```

### Expanded direct-profile form

As a secondary syntax, the same direct profile can be supplied as separate `pkb_run_profile.<pkb_var>` properties:

```text
pkb_run_profile.pkb_glue=com.example.pickleball
pkb_run_profile.pkb_features=classpath:features
pkb_run_profile.pkb_tags=@smoke
pkb_run_profile.pkb_browser=CHROME_HEADLESS
```

The expanded members may be supplied through the normal property sources, including JVM system properties, Pickleball property files, and runner code. Each member value is already associated with one RunVar and is therefore **not parsed as a compact `key=value, key=value` assignment string**. Commas, semicolons, `=`, apostrophes, quotes, backslashes, and brackets in that member value are ordinary value characters once the surrounding configuration source has delivered the string to Pickleball. Normal `<...>` template resolution still applies afterward.

For example, a properties file can use:

```properties
pkb_run_profile.pkb_tags=@smoke
pkb_run_profile.pkb_browser=CHROME_HEADLESS
pkb_run_profile.pkb_rp_description=Bob's "QA, phase 2; retry" = green
```

Runner code may avoid string serialization completely:

```java
PKB_props.runProfile(Map.of(
    "pkb_tags", "@smoke",
    "pkb_browser", "CHROME_HEADLESS",
    "pkb_rp_description", "Bob's \"QA, phase 2; retry\" = green"
));
```

Do not combine a compact `pkb_run_profile` value with `pkb_run_profile.*` members in the same resolved configuration. Pickleball rejects that mixture instead of guessing which representation should win. Expanded members still follow normal source precedence individually, so use the expanded form consistently across sources when member-by-member overrides are desired.

Command shells, Maven launchers, and CI products can still impose their own quoting rules before Pickleball receives a JVM property. The expanded form cannot remove those external rules, but it removes the additional nested Pickleball assignment grammar from each member value.

In direct mode:

- Pickleball first checks for an explicit compact `pkb_run_profile` or expanded `pkb_run_profile.*` direct profile; if neither is supplied, a selected profile may provide `pkb_run_profile`;
- `pkb_profile`, `default_profile`, runner defaults, property-file RunVars, and other resolved RunVars are not applied to execution;
- only the values contained in the selected direct-profile representation become RunVars;
- compact assignment boundaries are identified before template resolution; template references are then resolved against the loaded profile/default reference data, so delimiters introduced by a resolved template remain part of that RunVar value;
- projected Cucumber CLI RunVar overrides are ignored, so the direct run profile remains deterministic;
- normal downstream Cucumber/ReportPortal alias conversion still occurs;
- diagnostic lineage metadata supplied separately remains available without becoming part of the execution profile.

This mode is intended for reproducible automation and AI-agent test runs where the caller wants one explicit configuration without auditing every possible default or override source.

### Diagnostic run metadata

These properties describe investigation lineage rather than execution behavior and are therefore **not RunVars**:

```text
pkb_investigation_id
pkb_run_purpose
pkb_parent_run_id
pkb_baseline_run_id
pkb_changed_variables
```

Supply them separately from `pkb_run_profile`. They survive direct mode, are excluded from the retained run profile, its fingerprint, and the execution-configuration hash, and are rejected if placed inside a YAML/inline profile or direct run-profile assignment. This keeps the execution contract stable while allowing each diagnostic rerun to carry different lineage.

For example:

```bash
mvn test \
  -Dpkb_investigation_id="diag-214" \
  -Dpkb_parent_run_id="20260809-previous" \
  -Dpkb_changed_variables="pkb_browser" \
  "-Dpkb_run_profile=pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic"
```

A derived `pkb_run_profile` protects configured sensitive fields using placeholders such as:

```text
pkb_rp_api_key=${protected:pkb_rp_api_key}
```

When such a protected run profile is reused in another process, provide the sensitive value separately through normal secure configuration (for example `-Drp.api.key=...` or `-Dpkb_rp_api_key=...`). Pickleball resolves the protected placeholder from `default_profile` for the direct run without printing the secret in the serialized profile.

The explicit protected-property registry remains in `SensitiveConfiguration`. In addition, secret-like names containing fragments such as `password`, `secret`, `token`, `api_key`, or `credential` are treated as sensitive for display/serialization so a newly introduced credential-shaped RunVar cannot bypass redaction accidentally.

## Common properties

| Property | Example | Purpose |
|---|---|---|
| `pkb_glue` | `com.example.tests` | Cucumber glue packages |
| `pkb_features` | `classpath:features` | regular scenario feature location |
| `pkb_componentpath` | `src/test/resources/component` | reusable component-scenario location |
| `pkb_callpath` | `src/test/resources/calls` | reusable service-call location |
| `pkb_datapath` | `src/test/resources/data` | scenario-marker data and `data:/` file root |
| `pkb_tags` | `@smoke and not @slow` | Cucumber tag expression |
| `pkb_name` | `Checkout.*` | scenario-name expression |
| `pkb_environment` | `QA` | project environment name |
| `pkb_browser` | `chrome` | browser configuration name |
| `pkb_profile` | `default_profile,qa` | selected profile(s), composed left-to-right |
| `pkb_run_profile` | `pkb_tags=@smoke, pkb_browser=chrome` | retained final RunVars or compact explicit full RunVar override |
| `pkb_run_profile.<pkb_var>` | `pkb_run_profile.pkb_browser=chrome` | expanded direct-profile member; avoids compact assignment parsing |
| `pkb_investigation_id` | `diag-214` | diagnostic investigation lineage metadata; not a RunVar |
| `pkb_run_purpose` | `browser-counterfactual` | diagnostic run purpose metadata; not a RunVar |
| `pkb_parent_run_id` | `20260809-...` | immediate parent-run lineage metadata; not a RunVar |
| `pkb_baseline_run_id` | `20260809-...` | baseline-run lineage metadata; not a RunVar |
| `pkb_changed_variables` | `pkb_browser` | declares intended changed RunVars; not itself a RunVar |
| `pkb_debugbrowser` | `true` | retain extra browser troubleshooting state |
| `pkb_loglevel` | `debug` | `trace`, `debug`, `info`, `warn`, or `error` |
| `pkb_parallel` | `4` | maximum parallel scenario count |
| `pkb_reportingmode` | `diagnostic` | use the diagnostic evidence pipeline; any other/absent value uses normal reporting |
| `pkb_reportretention` | `all`, `failed`, or `none` | automatic local report/evidence retention; default `all` |
| `pkb_diagnostic_output` | `reports/diagnostic-runs` | optional diagnostic-runs root; the default needs no configuration |
| `pkb_platformlog` | `default`, `default+git`, `none`, `keys:...`, or `template:...` | controls automatic platform/caller identity stamps |
| `pkb_gitsnapshot` | `metadata`, `diff`, or `none` | diagnostic Git/source provenance |
| `pkb_compositeReport` | `true` or a path | combined HTML report |
| `pkb_scenarioReport` | `true` or a path | individual scenario reports |

## Diagnostic reporting and retention

Diagnostic mode requires only:

```properties
pkb_reportingmode=diagnostic
```

It is resolved once at runner startup. Diagnostic artifacts always capture TRACE-through-ERROR evidence while console output still follows `pkb_loglevel`. Automatic normal HTML/ReportPortal/XLSX lifecycle output is bypassed, while explicitly invoked reporting steps continue to work.

`pkb_reportretention` applies independently:

```properties
pkb_reportretention=failed
```

- `all` — default; keep automatic output for all scenarios.
- `failed` — keep dense output for failed/interrupted scenarios; passing diagnostic scenarios retain only lightweight indexes/summaries.
- `none` — suppress/prune automatic dense local output while retaining minimal diagnostic navigation indexes when diagnostic mode is active.

`pkb_platformlog` controls the automatic caller/platform identity stamp used by normal logging and external reporting. `pkb_gitsnapshot` controls diagnostic source provenance. See [Diagnostic reporting](diagnostic-reporting.md) for the complete evidence contract.

## `pkb_datapath`

`pkb_datapath` keeps its existing data-root behavior. When no configured value is resolved, framework data lookup falls back to:

```text
src/test/resources/data
```

The same resolved root is used by both kinds of `data:` references:

```text
<data:feature.scenario.marker>
<data:/directory/file>
```

The meanings differ only after the `data:` prefix: no leading slash selects scenario-marker data; a leading slash selects file lookup beneath the resolved data root.

## Cucumber aliases

| Pickleball property | Cucumber equivalent |
|---|---|
| `pkb_glue` | `cucumber.glue` |
| `pkb_features` | `cucumber.features` |
| `pkb_tags` | `cucumber.filter.tags` |
| `pkb_name` | `cucumber.filter.name` |

The default profile captures the normal synchronized Pickleball/Cucumber values. After a custom/direct profile is selected, its `pkb_*` values are used to derive the corresponding native Cucumber options.

## ReportPortal aliases and profiles

All native ReportPortal properties beginning with `rp.` have a generic Pickleball alias. Dots after `rp.` become underscores after `pkb_rp_`:

```text
rp.enable             <-> pkb_rp_enable
rp.endpoint           <-> pkb_rp_endpoint
rp.project            <-> pkb_rp_project
rp.launch             <-> pkb_rp_launch
rp.description        <-> pkb_rp_description
rp.api.key            <-> pkb_rp_api_key
rp.reporting.async    <-> pkb_rp_reporting_async
rp.http.proxy.password <-> pkb_rp_http_proxy_password
```

The mapping is generic, so supported ReportPortal `rp.*` settings do not need individual Pickleball code additions.

Normal native ReportPortal configuration (`-Drp.*`, environment/reportportal properties loaded by the ReportPortal client) is synchronized into `default_profile`. `pkb_rp_*` properties can also be supplied through JVM arguments, the runner subclass, Pickleball property files, inline profiles, or profile YAML.

The final active run profile is authoritative for the ReportPortal bridge. The bridge receives only the final synchronized `rp.*` values, so an old native property that is not present in the selected/direct run profile cannot silently re-enter ReportPortal configuration.

ReportPortal logging additionally requires active `pkb_rp_enable=true`. This preserves an explicit Pickleball-side enable gate even if an unrelated/native `rp.enable=true` remains in the JVM.

Sensitive ReportPortal settings remain usable in profiles and RunVars, but human-readable Pickleball output redacts them. The central protected-property registry currently includes API keys, OAuth passwords/client secrets, keystore/truststore passwords, and ReportPortal proxy passwords. Add future protected names to `SensitiveConfiguration` rather than adding one-off logging checks.

Example:

```yaml
reportportal_qa:
  pkb_rp_enable: true
  pkb_rp_endpoint: https://reportportal.example
  pkb_rp_project: qa-project
  pkb_rp_launch: QA Regression
  pkb_rp_api_key: "<default_profile.pkb_rp_api_key>"
```

## Consumer Maven profiles

Maven POM profiles such as `-Psmoke` are separate from Pickleball `pkb_profile`. They can coexist: a Maven profile controls Maven configuration, while `pkb_profile` composes Pickleball RunVars.

[Previous: Keyboard Expressions](key-parser-dsl.md) · [Documentation home](README.md) · [Next: Custom Element Definitions](custom-element-definitions.md)
