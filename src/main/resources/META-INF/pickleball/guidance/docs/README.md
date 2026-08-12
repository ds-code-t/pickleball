# Pickleball Documentation

Pickleball extends Cucumber with a dynamic feature-file language while preserving normal Cucumber behavior. The pages below describe the supported authoring model and link to real executable examples in [`maven-consumer-project`](consumer-project.md).

## Start here

- [Getting started](getting-started.md) — add the Maven dependency, create one runner, and run feature files.
- [Consumer project guide](consumer-project.md) — run the executable Maven consumer, local test site, tag suites, configuration, and diagnostic workflow.
- [Consumer AI agent guide](consumer-agent-guide.md) — canonical instructions for AI agents authoring, running, diagnosing, and rerunning Pickleball scenarios from a Maven consumer.
- [Cucumber compatibility](cucumber-compatibility.md) — mix Pickleball dynamic steps with standard Cucumber steps, hooks, tags, tables, and plugins.

## Browser scenarios

- [Dynamic steps](dynamic-steps.md) — describe Selenium elements, actions, assertions, values, contexts, and phrase chains directly in Gherkin.
- [Custom element definitions](custom-element-definitions.md) — optionally add project-specific element names without placing selectors in feature files.
- [Keyboard expressions](key-parser-dsl.md) — express sequential, simultaneous, and held-key input.

## Data and reusable behavior

- [Mapping and templating](mapping-and-templating.md) — supported mapping and saved-value behavior.
- [Data values and Data Elements](data-values-and-elements.md) — native values, Jackson container preservation, Data Elements, and explicit conversions.
- [Configuration files and resource mapping](config-files-and-resource-mapping.md) — load shared config/resources, use `pkb_configpath`, prefer `<config:...>`, and retain legacy `<configs...>` compatibility.
- [Component scenarios](component-scenarios.md) — invoke reusable scenario-sized flows.
- [Service-call scenarios](service-call-scenarios.md) — locate and execute reusable REST/SOAP service-call scenarios.
- [Date and time utilities](date-time-utilities.md) — create, adjust, format, and compare temporal values.

## Conditional structure

- [Nested steps](nested-steps.md) — arrange parent/child steps and pass conditions or page context downward.
- [Block conditionals](block-conditionals.md) — choose one `IF:` / `ELSE-IF:` / `ELSE:` branch.

## Execution

- [Execution configuration](configuration.md) — tags, feature/resource locations, browsers, named profiles, controlled `pkb_runvars`, canonical `pkb_run_profile`, ReportPortal aliases, parallelism, logging, reports, and local overrides.
- [AI and automation run configuration](ai-run-configuration.md) — controlled `pkb_runvars`, inherited execution context, retained `pkb_run_profile`, `pkb_configpath`, protected values, and deterministic diagnostic reruns.
- [Diagnostic lineage and metadata](diagnostic-lineage-metadata.md) — distinguish lineage annotations, execution/evidence RunVars, controls, and derived evidence.
- [Diagnostic reporting](diagnostic-reporting.md) — sparse-first AI evidence, source provenance, step/capability metadata, trace evidence, screenshots/fingerprints, comparison, and retention.
- [AI diagnostic reporting plan](ai-diagnostic-reporting-plan.md) — current sparse-first investigation and controlled-rerun architecture.

## Working consumer project

The example project contains a Maven dependency, runner, loopback server, browser pages, REST/SOAP endpoints, feature files, configuration data, and reusable call definitions.

- [Consumer `pom.xml`](../maven-consumer-project/pom.xml)
- [Pickleball test runner](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java)
- [Local browser/service test server](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java)
- [Executable feature files](../maven-consumer-project/src/test/resources/features)
- [Example profiles](../maven-consumer-project/src/test/resources/profiles.yaml)
- [Shared configuration data](../maven-consumer-project/src/test/resources/configs)
- [Reusable service calls](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)

[Return to the project README](../README.md)
