# Execution Configuration

> **Runnable examples:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) exercises normal execution settings. [`scenario-data-references.feature`](../maven-consumer-project/src/test/resources/features/scenario-data-references.feature) exercises default data-path behavior and `data:/` file lookup.

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
mvn test "-Dpkb_tags=@forms and @state-assertions" -Dpkb_loglevel=debug
```

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
| `pkb_compositeReport` | `true` or a path | combined HTML report |
| `pkb_scenarioReport` | `true` or a path | individual scenario reports |

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
