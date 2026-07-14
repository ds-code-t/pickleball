# Execution Configuration and `pkb_` Properties

`pkb_` properties control which scenarios run and how they run. They are execution settings rather than business steps, so they normally live in the test runner, a properties file, or the command line.

Examples:

```properties
pkb_tags=@smoke
pkb_browser=chrome
pkb_loglevel=debug
```

Property names are treated without regard to letter case. This documentation uses lowercase names for consistency.

## Where settings can be defined

| Location | Best use |
|---|---|
| `globalTestDefaults()` in the runner | Shared defaults that developers may override locally |
| `src/test/resources/pickleball.properties` | Shared project properties committed to Git |
| `src/test/resources/pickleball_local.properties` | Personal local settings; exclude from Git |
| JVM `-Dpkb_...` properties | One-run, build-server, or CI overrides |
| `globalTestProperties()` in the runner | Enforced values that local files should not replace |

## Which value wins

When the same property is defined in more than one place, the stronger source wins:

1. JVM system property, such as `-Dpkb_tags=@smoke`
2. `globalTestProperties()` in the runner
3. `pickleball_local.properties`
4. `globalTestDefaults()` in the runner
5. `pickleball.properties`, when the value has not already been supplied

Put normal team defaults in `globalTestDefaults()`. Reserve `globalTestProperties()` for values that must be enforced.

## Shared runner defaults

A project maintainer can define defaults in the test runner:

```java
@Override
public void globalTestDefaults() {
    PKB_props.glue("com.example.tests.steps");
    PKB_props.features("classpath:features");
    PKB_props.plugins("pretty");
    PKB_props.browser("chrome");
}
```

Feature authors normally do not need to edit this file.

## Local overrides

Create:

```text
src/test/resources/pickleball_local.properties
```

Example:

```properties
# Run only the scenarios currently being developed.
pkb_tags=@local

# Use local browser and troubleshooting settings.
pkb_browser=chrome
pkb_debugbrowser=true
pkb_loglevel=debug
pkb_debugargs=elementsnapshot
```

After saving the file, run:

```bash
mvn test
```

The same local settings also apply when scenarios are launched from IntelliJ. This makes it possible to change tags or troubleshooting options without repeatedly editing run configurations.

### Keep local settings out of Git

Add this line to `.gitignore`:

```gitignore
src/test/resources/pickleball_local.properties
```

Commit an example file instead:

```text
src/test/resources/pickleball_local.properties.example
```

A copyable example is included at [`examples/pickleball_local.properties.example`](examples/pickleball_local.properties.example).

### Properties file versus command line

Inside a `.properties` file, omit `-D`:

```properties
pkb_suppressjunitfilteredskips=false
```

On the Maven command line, include `-D`:

```bash
mvn test -Dpkb_suppressjunitfilteredskips=false
```

## Common selection and execution properties

| Property | Example | Purpose |
|---|---|---|
| `pkb_glue` | `com.example.tests.steps` | Packages containing project step definitions and hooks |
| `pkb_features` | `classpath:features` | Feature-file location |
| `pkb_featurename` | `Checkout` | Select or identify a feature by name |
| `pkb_tags` | `@smoke and not @slow` | Cucumber tag expression |
| `pkb_name` | `Checkout.*` | Scenario-name regular expression |
| `pkb_profile` | `local` | Named project profile |
| `pkb_plugins` | `pretty` | Cucumber output and plugin settings |
| `pkb_parallel` | `4` | Fixed parallel worker count |
| `pkb_environment` | `test` | Project environment identifier |
| `pkb_browser` | `chrome` | Browser or browser configuration name |
| `pkb_debugbrowser` | `true` | Enable browser troubleshooting behavior |

## Logging and troubleshooting

| Property | Example | Purpose |
|---|---|---|
| `pkb_loglevel` | `debug` | Controls Pickleball logging; normal runs use `info` |
| `pkb_debugargs` | `elementsnapshot,rawxpaths` | Enables selected troubleshooting details |

Examples of debug arguments used by the project include:

```properties
pkb_debugargs=elementsnapshot
# pkb_debugargs=logallsteps,nobase,rawxpaths
```

Use `debug` or `trace` temporarily. These levels can produce much more output than a normal business-readable report.

## Waiting and report properties

| Property | Example | Purpose |
|---|---|---|
| `pkb_steprepeatmaxtime` | `20` | Maximum time allowed for repeating or waiting behavior |
| `pkb_steprepeatmaxcount` | `300` | Maximum number of repeat attempts |
| `pkb_suppressjunitfilteredskips` | `false` | Controls JUnit results for scenarios excluded by filters |
| `pkb_htmlreportformat` | `scenarioReport,compositeReport` | Selects generated HTML report types |
| `pkb_compositereport` | `reports/summary.html` | Composite report output path |
| `pkb_scenarioreport` | `reports/<SCENARIO_NAME>-<TIME>.html` | Per-scenario report path template |

Older projects may also contain longer report-path property names:

```properties
pkb_htmlcompositereportpathtemplate=reports/summary-<TIME>.html
pkb_htmlcompositereportpath=reports/summary-<TIME>.html
pkb_htmlscenarioreportpathtemplate=reports/<SCENARIO_NAME>-<TIME>.html
```

Use the shorter `pkb_compositereport` and `pkb_scenarioreport` forms when they meet the project’s needs.

## Cucumber-compatible aliases

These Pickleball properties correspond to standard Cucumber settings:

| Pickleball property | Cucumber property |
|---|---|
| `pkb_glue` | `cucumber.glue` |
| `pkb_features` | `cucumber.features` |
| `pkb_tags` | `cucumber.filter.tags` |
| `pkb_name` | `cucumber.filter.name` |

## Command-line examples

Run smoke scenarios:

```bash
mvn test -Dpkb_tags="@smoke"
```

Select scenarios by name:

```bash
mvn test -Dpkb_name="Checkout.*"
```

Use four parallel workers:

```bash
mvn test -Dpkb_parallel=4
```

Combine several overrides:

```bash
mvn test \
  -Dpkb_tags="@smoke and not @slow" \
  -Dpkb_browser=chrome \
  -Dpkb_loglevel=debug
```

PowerShell:

```powershell
mvn test `
  "-Dpkb_tags=@smoke and not @slow" `
  -Dpkb_browser=chrome `
  -Dpkb_loglevel=debug
```

---

[Previous: Keyboard Expressions](key-parser-dsl.md) · [Documentation home](README.md) · [Next: Maintainer Element Definitions](custom-element-definitions.md)
