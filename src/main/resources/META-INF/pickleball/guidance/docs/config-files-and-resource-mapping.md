# Configuration Files and Resource Mapping

Pickleball loads structured configuration into one runtime configuration mapping.

## Configuration references

Prefer the source-qualified `config:` syntax regardless of the physical source path:

```text
<config:application.baseUrl>
<config:users.admin.username>
```

Legacy references remain supported:

```text
<configs.application.baseUrl>
<configs.users.admin.username>
```

Both forms resolve against the same loaded configuration data. Do not substitute a configured directory name into either reference. `pkb_configpath` changes the source, not the logical configuration mapping.

## Config source path

The RunVar is:

```text
pkb_configpath
```

Examples:

```text
pkb_configpath=configs
pkb_configpath=classpath:configs
pkb_configpath=classpath:environment/qa/configs
pkb_configpath=src/test/resources/environment/qa/configs
pkb_configpath=file:/opt/pickleball/configs
```

When the value is absent or blank, Pickleball keeps the historical Java fallback: the classpath resource root `configs`.

A project-level `pkb_configpath` is part of required execution context. Named profiles and controlled `pkb_runvars` inherit it when they omit the key. An explicit blank suppresses that inheritance and returns to the Java fallback.

Example:

```text
project: pkb_configpath=classpath:environment/qa/configs
run:     pkb_runvars=pkb_configpath=,pkb_browser=firefox
```

The controlled run uses the default `configs` resource root because the blank value intentionally suppresses the project path.

## Bundled browser configs

Named browser yaml files under the configured config path remain the local override, including headed `CHROME.yaml`. When `CHROME_HEADLESS` is absent from that mapping, Pickleball fills it from the JAR resource `META-INF/pickleball/configs/CHROME_HEADLESS.yaml` so agents can set `pkb_browser=CHROME_HEADLESS` without copying yaml. See [Execution Configuration](configuration.md).

## Initialization order

Run configuration is resolved before the final config source is bound:

```text
normal properties / profiles / pkb_runvars
                    ↓
             final RunVars
                    ↓
          final pkb_configpath
                    ↓
        load/replace configs map
                    ↓
             scenario runtime
```

Configuration data is not loaded during profile/RunVar resolution. `ParsingMap.initializeConfigs` binds the final source only after the effective RunVars and `pkb_configpath` are known.

`<config:...>` and legacy `<configs...>` are runtime mapping data. Neither form can choose `pkb_configpath` or otherwise resolve `default_profile`, named profiles, `pkb_runvars`, or the final `pkb_run_profile`.

Supported profile references remain valid because they do not depend on runtime configs:

```text
pkb_runvars=pkb_configpath=<qa.pkb_configpath>,pkb_browser=firefox
```

## Other resource paths

This feature intentionally preserves the existing path behavior of other RunVars.

- `pkb_features` uses Cucumber feature-location semantics. Explicit `classpath:` and `file:` locations are supported; a bare path follows the existing Cucumber/Pickleball feature-path behavior.
- `pkb_datapath` keeps its existing filesystem/classpath-root behavior and Java fallback.
- `pkb_callpath` keeps its existing component/service-call path behavior and Java fallback.
- `pkb_componentpath` keeps its existing component-scenario path behavior and Java fallback.
- `pkb_glue` is Java/Cucumber glue package syntax, not a resource path.

Do not assume those keys share one public path grammar merely because they are all inherited execution-context values.

## Structured resource formats

`FileAndDataParsing` continues to provide suffix-agnostic resource lookup and the structured formats already supported by Pickleball, including JSON, YAML/YML, XML, properties, INI/conf/text, and CSV where applicable.

Template resolution behavior for ordinary runtime resource files is unchanged by `pkb_configpath`.

## Consumer-agent rule

When a consumer agent needs project configuration data, read the version-matched Pickleball documentation and the consumer's active `pkb_configpath`. Prefer `<config:...>` for new references; preserve existing `<configs...>` references unless there is a reason to modernize them. Never rewrite config references to match a physical directory name.
