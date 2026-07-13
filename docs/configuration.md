# Configuration and `pkb_` Properties

Pickleball configuration can be supplied through the test runner, resource property files, JVM system properties, and supported Cucumber command-line arguments.

Project-level Pickleball property names use the prefix:

```text
pkb_
```

Examples:

```properties
pkb_tags=@smoke
pkb_browser=chrome
pkb_debugbrowser=true
```

Pickleball treats the `pkb_` prefix and the remainder of a `pkb_` key without regard to letter case by normalizing those keys internally. Lowercase names are used throughout this documentation for consistency.

## Recommended configuration layers

Use each layer for a different purpose:

| Location | Recommended purpose |
|---|---|
| `globalTestDefaults()` in the runner | Safe defaults shared by everyone |
| `src/test/resources/pickleball.properties` | Shared, committed project configuration |
| `src/test/resources/pickleball_local.properties` | Personal local overrides; do not commit |
| JVM `-Dpkb_...` properties | One-run or CI overrides |
| `globalTestProperties()` in the runner | Enforced runner values that local files should not replace |

## Effective precedence

For the same setting, the strongest source wins:

1. JVM system property, such as `-Dpkb_tags=@smoke`
2. `globalTestProperties()` in the test runner
3. `pickleball_local.properties`
4. `globalTestDefaults()` in the test runner
5. `pickleball.properties`, which fills values that are still missing

Therefore, values intended to be locally replaceable should be placed in `globalTestDefaults()`, not `globalTestProperties()`.

## Runner defaults

```java
@Override
public void globalTestDefaults() {
    PKB_props.glue("com.example.tests.steps");
    PKB_props.features("classpath:features");
    PKB_props.plugins("pretty");
    PKB_props.browser("chrome");
}
```

A developer can override these values locally without changing the runner:

```properties
pkb_tags=@work-in-progress
pkb_browser=firefox
```

## Local overrides

Create this file in the consumer project:

```text
src/test/resources/pickleball_local.properties
```

The local file is loaded before scenarios execute. A value in this file replaces the same value from `globalTestDefaults()` or shared `pickleball.properties`.

For example:

```properties
# Run only scenarios selected for local development.
pkb_tags=@local

# Use local browser/debug settings.
pkb_browser=chrome
pkb_debugbrowser=true
pkb_loglevel=trace
pkb_debugargs=elementsnapshot
```

Then run:

```bash
mvn test
```

or run the scenario/test configuration from IntelliJ. You do not need to add the same tags or properties to every run configuration.

A feature or framework operation that reads a `pkb_` setting receives the final merged value. The local file changes configuration; it does not rewrite the contents of a `.feature` file.

### Keep the file local

Add this entry to the consumer project's `.gitignore`:

```gitignore
src/test/resources/pickleball_local.properties
```

Commit an example instead:

```text
src/test/resources/pickleball_local.properties.example
```

A ready-to-copy example is provided at [`examples/pickleball_local.properties.example`](examples/pickleball_local.properties.example).

### Property-file syntax versus command-line syntax

Inside a `.properties` file, do **not** include `-D`:

```properties
pkb_suppressjunitfilteredskips=false
```

On a Maven or Java command line, use `-D`:

```bash
mvn test -Dpkb_suppressjunitfilteredskips=false
```

A JVM system property has higher precedence than the local file, making it suitable for CI and one-time overrides.

## Runner properties exposed by `PKB_props`

These settings have named accessors in `tools.dscode.testengine.PKB_props`.

| Property | Runner method | Purpose |
|---|---|---|
| `pkb_glue` | `PKB_props.glue(...)` | Comma-separated glue package locations |
| `pkb_features` | `PKB_props.features(...)` | Feature path, such as `classpath:features` |
| `pkb_featurename` | `PKB_props.featureName(...)` | Select or identify a feature by name |
| `pkb_tags` | `PKB_props.tags(...)` | Cucumber tag expression, such as `@smoke and not @slow` |
| `pkb_name` | `PKB_props.name(...)` | Scenario-name regular expression |
| `pkb_profile` | `PKB_props.profile(...)` | Named configuration profile |
| `pkb_plugins` | `PKB_props.plugins(...)` | Cucumber plugin/output configuration |
| `pkb_parallel` | `PKB_props.parallel(...)` | Fixed parallel worker count |
| `pkb_environment` | `PKB_props.environment(...)` | Project environment identifier |
| `pkb_browser` | `PKB_props.browser(...)` | Browser identifier used by the test project/framework |
| `pkb_debugbrowser` | `PKB_props.debugBrowser(...)` | Enables browser debugging behavior |

All values can also be accessed generically:

```java
PKB_props.put("pkb_tags", "@smoke");
String tags = PKB_props.get("pkb_tags");
```

## Additional runner settings

The following options are defined or consumed directly by `PickleballRunner`.

| Property | Example | Purpose |
|---|---|---|
| `pkb_loglevel` | `trace` | Pickleball log level; defaults to `INFO` |
| `pkb_debugargs` | `elementsnapshot,rawxpaths` | Comma-separated framework debugging flags |
| `pkb_parallel` | `4` | Enables Cucumber fixed parallel execution with the given count |
| `pkb_debugbrowser` | `true` | Enables the runner's global browser-debug state |

Known debug arguments used by the project include:

```properties
pkb_debugargs=elementsnapshot
# pkb_debugargs=logallsteps,nobase,rawxpaths
```

## Runtime and report settings shown by the consumer project

The sample consumer project also demonstrates these settings:

| Property | Example | Intended use |
|---|---|---|
| `pkb_suppressjunitfilteredskips` | `false` | Controls suppression of JUnit results for scenarios excluded by filtering |
| `pkb_steprepeatmaxtime` | `20` | Maximum repeat/wait duration used by repeating step behavior |
| `pkb_steprepeatmaxcount` | `300` | Maximum repeat count used by repeating step behavior |
| `pkb_htmlreportformat` | `scenarioReport,compositeReport` | Selects generated HTML report formats |
| `pkb_compositereport` | `reports/summary.html` | Enables or supplies the output path for a composite report |
| `pkb_scenarioreport` | `reports/<SCENARIO_NAME>-<TIME>.html` | Enables or supplies the output-path template for scenario reports |

The consumer project's sample file also contains older, more explicit report-path names:

```properties
pkb_htmlcompositereportpathtemplate=reports/summary-<TIME>.html
pkb_htmlcompositereportpath=reports/summary-<TIME>.html
pkb_htmlscenarioreportpathtemplate=reports/<SCENARIO_NAME>-<TIME>.html
```

Prefer the shorter `pkb_compositereport` and `pkb_scenarioreport` forms when they meet the project's needs.

## Cucumber aliases

Pickleball synchronizes these aliases with their corresponding Cucumber properties:

| Pickleball | Cucumber |
|---|---|
| `pkb_glue` | `cucumber.glue` |
| `pkb_features` | `cucumber.features` |
| `pkb_tags` | `cucumber.filter.tags` |
| `pkb_name` | `cucumber.filter.name` |

This allows a project to use the Pickleball names consistently while still integrating with Cucumber's underlying configuration.

## Command-line examples

Run only smoke tests:

```bash
mvn test -Dpkb_tags="@smoke"
```

Select by scenario name:

```bash
mvn test -Dpkb_name="Checkout.*"
```

Use four parallel workers:

```bash
mvn test -Dpkb_parallel=4
```

Override several settings:

```bash
mvn test \
  -Dpkb_tags="@smoke and not @slow" \
  -Dpkb_browser=chrome \
  -Dpkb_loglevel=trace
```

PowerShell:

```powershell
mvn test `
  "-Dpkb_tags=@smoke and not @slow" `
  -Dpkb_browser=chrome `
  -Dpkb_loglevel=trace
```

## Internal values

Pickleball creates a few derived values for its own bookkeeping:

- `pkb_options`
- `pkb_cucumber_cli_args`
- `pkb_cucumber_cli_feature_selectors`

These values describe resolved options or captured Cucumber arguments. They should normally be treated as read-only implementation details rather than user configuration.

## Next step

For project-specific DOM vocabulary and XPath behavior, see [Custom element and XPath definitions](custom-element-definitions.md).

---

[Previous: Getting started](getting-started.md) · [Documentation home](README.md) · [Next: Custom definitions](custom-element-definitions.md)
