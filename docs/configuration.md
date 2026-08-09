# Execution Configuration

> **Runnable examples:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) exercises normal execution settings. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) exercises default data-path behavior and `data:/` file lookup. [`configuration-system-properties.feature`](../maven-consumer-project/src/test/resources/features/configuration-system-properties.feature) verifies JVM `pkb_*` value quote normalization.

Pickleball execution properties select scenarios and control how they run. Property names are case-insensitive; this page uses lowercase `pkb_` names for consistency.

```properties
pkb_tags=@smoke
pkb_browser=chrome
pkb_loglevel=debug
```

## Configuration sources and precedence

When the same setting appears in several places, the stronger source wins:

1. JVM system property;
2. `globalTestProperties()`;
3. `pickleball_local.properties`;
4. `globalTestDefaults()`;
5. `pickleball.properties`, when no stronger value has been supplied.

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

For example, these values resolve as shown:

```text
"INFO"                         -> INFO
'all'                          -> all
"@smoke and not @slow"        -> @smoke and not @slow
'name="A B"'                  -> name="A B"
```

Only JVM `pkb_*` system-property values receive this command-line normalization. Non-Pickleball JVM properties and values authored in `.properties` files keep their normal Java semantics.

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
| `pkb_debugbrowser` | `true` | retain extra browser troubleshooting state |
| `pkb_loglevel` | `debug` | `trace`, `debug`, `info`, `warn`, or `error` |
| `pkb_parallel` | `4` | maximum parallel scenario count |
| `pkb_reportingmode` | `diagnostic` | use the diagnostic evidence pipeline; any other/absent value uses normal reporting |
| `pkb_reportretention` | `all`, `failed`, or `none` | automatic local report/evidence retention; default `all` |
| `pkb_diagnostic_output` | `reports/diagnostic-runs` | optional diagnostic-runs root; the default needs no configuration |
| `pkb_platformlog` | `default`, `default+git`, `none`, `keys:...`, or `template:...` | controls automatic platform/caller identity stamps; absent/default preserves current logging |
| `pkb_gitsnapshot` | `metadata`, `diff`, or `none` | diagnostic Git/source provenance; default `metadata`; `diff` also stores a gzipped consumer working-tree patch when dirty |
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

`pkb_diagnostic_output` is optional; diagnostic mode needs only `pkb_reportingmode=diagnostic`.

`pkb_platformlog` controls the automatic caller/platform identity stamp used by normal logging and external reporting. It is independent of diagnostic mode. The default is deliberately backward-compatible:

```properties
pkb_platformlog=default
```

Supported forms are:

- `default` or absent — preserve the current platform/caller text;
- `default+git` — preserve the current text and append compact consumer/Pickleball source identity;
- `none` — suppress automatic platform identity records;
- `keys:hostname,user.name,ci.type` — log only the requested `PlatformSnapshot`/source-provenance keys;
- `template:Caller=${user.name} Repo=${git.consumer.name} Commit=${git.consumer.commit}` — render a caller-defined template.

The Git/source keys available to `keys:` and `template:` include `git.consumer.name`, `.remote`, `.webUrl`, `.branch`, `.commit`, `.commitMessage`, `.dirty`, the corresponding `git.pickleball.*` values, and `pickleball.version`. HTTP(S) Git credentials embedded in a remote URL are removed before provenance is persisted.

`pkb_gitsnapshot` controls diagnostic source provenance. `metadata` is the default and records repository/branch/commit/dirty state without copying source diffs. `none` disables live consumer Git inspection. `diff` adds a gzipped working-tree status/diff artifact when the consumer repository is dirty; use it only when retaining local source changes in diagnostic evidence is acceptable.

ReportPortal is not controlled by `pkb_reportretention` in normal mode because it is a remote reporting integration. See [Diagnostic reporting](diagnostic-reporting.md) for the artifact layout, scenario identity model, source provenance, step metadata, capability observations, screenshots, fingerprints, and recovery behavior.

## `pkb_datapath`

`pkb_datapath` keeps its existing resolution and precedence behavior. This feature does not introduce a second data-root setting or a new precedence model.

When no configured value is resolved, framework data lookup falls back to:

```text
src/test/resources/data
```

The same resolved root is used by both kinds of `data:` references:

```text
<data:feature.scenario.marker>
<data:/directory/file>
```

The meanings differ only after the `data:` prefix:

- no leading slash: scenario-marker data lookup;
- leading slash: file lookup beneath the resolved data root.

For example:

```text
<data:/files/customerPayload>
```

means "find `files/customerPayload` below the resolved data root." The slash is not an operating-system absolute-path marker.

`data:/` then reuses the normal `file:` parsing, suffix discovery, and nested query behavior:

```text
<data:/files/customerPayload.customer.orders[0].id>
```

A configured filesystem/source path remains a filesystem/source path; a supported classpath form remains classpath-based according to the existing resolver. `data:/` does not force the data root to classpath-only lookup.

## Cucumber aliases

| Pickleball property | Cucumber equivalent |
|---|---|
| `pkb_glue` | `cucumber.glue` |
| `pkb_features` | `cucumber.features` |
| `pkb_tags` | `cucumber.filter.tags` |
| `pkb_name` | `cucumber.filter.name` |

## ReportPortal

ReportPortal integration is disabled unless a supported enable property is explicitly set to `true`, case-insensitively:

```properties
rp.enable=true
```

Equivalent environment/property forms such as `rp_enable` and `RP_ENABLE` are recognized where supplied.

## Consumer Maven profiles

The working [pom.xml](../maven-consumer-project/pom.xml) supplies profiles including `all`, `smoke`, `browser`, `data`, `forms`, `mapping`, `workflow`, `conditionals`, `nested`, `keyboard`, `dialogs`, and `components`.

Examples:

```bash
mvn test -Pall
mvn test -Psmoke
mvn test -Pworkflow
```

[Previous: Keyboard Expressions](key-parser-dsl.md) · [Documentation home](README.md) · [Next: Custom Element Definitions](custom-element-definitions.md)
