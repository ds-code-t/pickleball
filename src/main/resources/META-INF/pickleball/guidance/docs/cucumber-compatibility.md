# Cucumber Compatibility

> **Working feature examples:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) demonstrates standard `Scenario Outline` and `Examples` syntax with Pickleball steps; [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) demonstrates reusable Pickleball behavior inside normal Cucumber feature structure.

Cucumber is a supported compatibility adapter. The default engine is native, and `pkb_engine=cucumber` keeps Cucumber. Standard Cucumber features and Java step definitions keep working, and dynamic steps can be mixed with them. Both engines fire `BEFORE_CUCUMBER_RUN` before the first scenario and `AFTER_CUCUMBER_RUN` after the last, then close logs, finish ReportPortal, stop the pickleball log, and call `AgentCoordination.finishCurrent`.

The native engine matches stored rows. `store hydrate` reads each glue annotation and writes `step_definitions.pattern` plus `step_definitions.parameter_types`. `PatternMatcher` matches a step against those rows and does not import Cucumber. A Cucumber expression such as `{string}`, `{int}`, `{float}`, `{word}`, `{bigdecimal}`, `{biginteger}`, `{double}`, `{byte}`, `{short}`, or `{long}` becomes a regular expression and a parameter-type name. A pattern that cucumber-expressions already treats as a regular expression (it starts with `^`, ends with `$`, or is wrapped in `/.../`) is stored as that expression and `parameter_types` is `[]`. A parameter type hydrate cannot translate fails that definition with `error:` and `fix: store glue show <path>`, and the previous row stays. The hydrate summary prints `translated=` and `failed=`. `pkb_engine_log=ingest` logs each translation. `pkb_glue` sets `step_definitions.enabled` for Java packages named in that list. A blank `pkb_glue` leaves definitions in the glue roots enabled. Ingest is the only writer of `scenario_tags`. `pkb_callpath` and `pkb_componentpath` are copied into `run_variables` as the strings already in the property files. Their lookup rules are unchanged.

The Cucumber adapter still dispatches Java glue with Cucumber. That matching lives in `CucumberStepRegistry` under `tools.dscode.engine.adapter.cucumber`. `engine run` and `engine step` stay the native execution verbs.

When Store is on, both engines take the scenario from the stored rows. `PickleballEngine` reads `StoreScenarioSource`. `pkb_engine=cucumber` hydrates, then the JUnit parser and the CLI feature supplier read those same rows through `RowFeatureSupplier`. They do not scan a feature directory and they do not fall back to the file. A missing row is `error:` and `fix: store hydrate`. A scenario with `enabled=0` or `@pkb-ignore` is left out of both engines. `pkb_engine_log=ingest` logs one line per skipped scenario. `pkb_store=false` keeps the file path, and `FeaturePathLoadCache` is that loader.

## Standard Cucumber behavior remains available

Feature files may continue to use:

- `Feature`, `Rule`, `Background`, `Scenario`, and `Scenario Outline`;
- `Given`, `When`, `Then`, `And`, `But`, and `*`;
- tags and Cucumber tag expressions;
- `Examples` tables;
- DataTables and DocStrings;
- custom Java step definitions;
- Cucumber hooks and plugins; and
- Cucumber glue-package discovery.

The runner configures glue and features through Pickleball properties:

```java
PKB_props.glue("com.example.pickleball");
PKB_props.features("classpath:features");
PKB_props.plugins("pretty");
```

See the working [consumer runner](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java).

## Dynamic and custom steps can coexist

A feature can mix a normal project step with Pickleball dynamic steps:

```gherkin
Given an account exists for "Ava"
When , enter "Ava" in the "Customer Name" Textbox
And , click the "Search" Button
Then , ensure the "Ava" Account Row is displayed
```

`Given an account exists for "Ava"` can be mapped with a normal `@Given` Java method. The remaining sentences are interpreted by Pickleball.

This provides a practical division:

- use dynamic steps for common browser actions, assertions, values, mapping, and control flow;
- use custom Java definitions for application-specific setup, special integrations, or behavior that does not fit the reusable language.

## Scenario Outlines and tables

Scenario Outline placeholders are resolved by Cucumber before Pickleball resolves runtime templates:

```gherkin
Scenario Outline: Submit customers
  * , enter "<name>" in the "Customer Name" Textbox
  * , select "<tier>" in the "Customer Tier" Dropdown

Examples:
  | name | tier     |
  | Ava  | Premium  |
  | Ben  | Standard |
```

Pickleball also uses tables for reusable component scenarios and service calls, while retaining ordinary Cucumber DataTable behavior for custom steps.

## Tags and selection

Use normal Cucumber tag expressions through `pkb_tags`:

```bash
mvn test "-Dpkb_tags=@browser and not @dialogs"
```

The consumer project's [feature files](../maven-consumer-project/src/test/resources/features) demonstrate normal tags, backgrounds, scenarios, outlines, examples, tables, and Pickleball steps in one suite.

Cucumber CLI `--tags` / `--name` / `--glue` projection can overlay ordinary and named-profile runs. Direct `pkb_runvars` and sealed `pkb_overriderunvars` skip that projection so the controlled or sealed RunVar set is not mutated. Supply tag/name/glue inside the compact or expanded control instead.

## Native engine CLI

`engine run` and `engine step` run ordinary glue from text, a step list, a file, or a Store row. They do not start Cucumber. Gradle `engineRun`, Workbench, and MCP `engine` only forward to that CLI. A normal JUnit run uses the native engine. Set `pkb_engine=cucumber` (or `PKB_ENGINE`, or `engine.properties` `mode=cucumber`) to select the Cucumber adapter. The default is `native`, and the adapter stays available.

`pkb_engine=cucumber` still selects the adapter. The adapter remains supported, and the Cucumber aspects it uses stay in place.

## Adapter boundary

Cucumber is an isolated legacy adapter. Gherkin syntax libraries (`io.cucumber.gherkin`, `io.cucumber.messages`, cucumber-expressions, tag-expressions, datatable, docstring, and the step annotations) stay allowed everywhere, because Gherkin syntax and step annotations do not change. `CucumberBoundaryTest` enforces the rest:

- The core spine imports nothing from `io.cucumber`: `tools.dscode.store`, `engine.model`, `engine.source`, `engine.parity`, `browser`, `common.agentcontrol`, `common.agentsurface`, `common.composite`, `common.domoperations`, `common.browseroperations`, `common.seleniumextensions`, `common.servicecalls`, `common.trace`, `common.runtime`, `registry`, `release`, `parallelutilities`, and the Workbench, DOM engine, and control protocol modules.
- The Cucumber listener stays in `tools.dscode.engine.adapter.cucumber`, and the `io.cucumber.*` overlay under `src/main/java/io/cucumber` is the adapter itself.
- Any other main file that imports a Cucumber runtime type (`io.cucumber.core`, `io.cucumber.plugin`, `io.cucumber.junit`, `io.cucumber.java.Scenario`) is listed in `src/test/resources/architecture/cucumber-runtime-allowlist.txt`. A new file fails the test. A listed file that no longer imports one also fails, so the list only shrinks.

The native runtime still shares Pickleball's step model classes that live in the `io.cucumber.core.runner` overlay (`StepExtension`, `CurrentScenarioState`, `GlobalState`). Moving that model out of the overlay is a separate redesign and is not part of this boundary.

[Documentation home](README.md) · [Next: Dynamic Steps](dynamic-steps.md)
