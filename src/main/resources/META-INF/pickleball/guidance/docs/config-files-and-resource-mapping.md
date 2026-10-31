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
pkb_configpath=file:configs
pkb_configpath=classpath:configs
pkb_configpath=qa/configs
pkb_configpath=classpath:environment/qa/configs
pkb_configpath=wd-file:configs
pkb_configpath=wd-file:/opt/pickleball/configs
```

Changed in 2.1.15. A path with no prefix is a classpath resource, including a path that contains a slash. `qa/configs` is the resources folder. `wd-file:src/test/resources/qa/configs` is that folder under the working directory. `wd-file:/opt/...` is an absolute directory. `file:/opt/...` is the classpath resource `opt/...`. There is no project-root prefix. A `file:/...` or unprefixed `/...` path that is not on the classpath fails with a hint: use `wd-file:/...` for a filesystem path. A relative miss and a missing `wd-file:` path stay empty.

| Prefix | Meaning |
|---|---|
| (none) | resources folder (classpath) |
| `file:` | resources folder (classpath), same as no prefix |
| `classpath:` | resources folder (classpath) |
| `wd-file:X` | `<working directory>/X` |
| `wd-file:/X` | absolute path `/X` |

When the value is absent or blank, Pickleball keeps the classpath resource root `configs`. A missing folder loads an empty config and does not fail the run. Configs that exist only inside a jar dependency still load. A normal run and an agent or Workbench private-run copy resolve the same value to the same folder.

A project-level `pkb_configpath` is part of required execution context. Named profiles and controlled `pkb_runvars` inherit it when they omit the key. An explicit blank suppresses that inheritance and returns to the Java fallback.

Example:

```text
project: pkb_configpath=classpath:environment/qa/configs
run:     pkb_runvars=pkb_configpath=,pkb_browser=firefox
```

The controlled run uses the default `configs` resource root because the blank value intentionally suppresses the project path.

## Bundled browser configs

Named browser yaml files under the configured config path remain the local override, including headed `CHROME.yaml`. When `CHROME_HEADLESS` is absent from that mapping, Pickleball fills it from the JAR resource `META-INF/pickleball/configs/CHROME_HEADLESS.yaml` so agents can set `pkb_browser=CHROME_HEADLESS` without copying yaml. See [Execution Configuration](configuration.md).

## Per-run copy

An agent run or a Workbench run copies that config tree into `.pickleball/runs/<run-id>/config` when the worker or test JVM opens the run, after `pkb_configpath` is resolved, and then reads only the copy. The copy is a faithful copy of the project configs (real values, valid YAML) so the worker can load them. The project config is unchanged. Redaction for logs and reports stays a separate path and does not rewrite the run copy. The copy uses the same path rule as a normal run. A root `configs/` directory is chosen when `wd-file:` points at it. `session-start`, `discover`, and `confirm` do not copy before that JVM exists. A directory that contains only the bundled `CHROME_HEADLESS.yaml` is not a finished copy. Edits, including from the Workbench Config tab, write the copy and do not write the project files. A second run has its own copy. A normal test does not copy and still loads `pkb_configpath` from the project. The stored run profile keeps the project `pkb_configpath`. It does not record the run directory.

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

That resolution is one sequence for a normal run and a Workbench dry-run: `PickleballRunner` `resolveShared` (hooks, then `ProjectPropertySources`, then `PKB_props.resolveInto`). The dry-run constructs the consumer runner when it is visible, so hook values are in the preview. The Workbench worker does not resolve again: `Workspace.loadForLaunch` reads the workspace copy.

Configuration data is not loaded during profile/RunVar resolution. `ParsingMap.initializeConfigs` binds the final source only after the effective RunVars and `pkb_configpath` are known.

`<config:...>` and legacy `<configs...>` are runtime mapping data. Neither form can choose `pkb_configpath` or otherwise resolve `default_profile`, named profiles, `pkb_runvars`, or the final `pkb_run_profile`.

Supported profile references remain valid because they do not depend on runtime configs:

```text
pkb_runvars=pkb_configpath=<qa.pkb_configpath>,pkb_browser=firefox
```

## Other resource paths

`pkb_features`, `pkb_datapath`, and the Gherkin `<file:...>` reference use the same prefix table as `pkb_configpath`.

```text
pkb_features=features
pkb_features=file:features
pkb_features=classpath:features
pkb_features=wd-file:features
pkb_datapath=data
<file:files/customers #1.name>
```

`features`, `file:features`, and `classpath:features` are the resources folder. `wd-file:features` is passed to Cucumber as `file:features`. The default `pkb_datapath` is `data`. `<file:files/customers #1.name>` stays a classpath file reference. The path after `file:` follows the prefix table, so `files/customers` is a classpath resource.

`pkb_callpath` and `pkb_componentpath` keep their previous project-relative path rules and Java fallbacks. `pkb_glue` is Java/Cucumber glue package syntax, not a resource path.

## Structured resource formats

`FileAndDataParsing` continues to provide suffix-agnostic resource lookup and the structured formats already supported by Pickleball, including JSON, YAML/YML, XML, properties, INI/conf/text, and CSV where applicable.

Template resolution behavior for ordinary runtime resource files is unchanged by `pkb_configpath`.

## Consumer-agent rule

When a consumer agent needs project configuration data, read the version-matched Pickleball documentation and the consumer's active `pkb_configpath`. Prefer `<config:...>` for new references; preserve existing `<configs...>` references unless there is a reason to modernize them. Never rewrite config references to match a physical directory name.
