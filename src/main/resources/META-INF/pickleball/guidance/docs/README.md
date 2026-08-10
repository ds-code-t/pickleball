# Pickleball Documentation

Pickleball extends Cucumber with a dynamic feature-file language while preserving normal Cucumber behavior. The pages below describe the supported authoring model and link to the real, executable examples in [`maven-consumer-project`](consumer-project.md).

Each functional guide begins with a prominent link to the specific consumer-project feature file that demonstrates the behavior.
## Start here

- [Getting started](getting-started.md) — add the Maven dependency, create one runner, and run feature files.
- [Consumer project guide](consumer-project.md) — understand and run the executable Maven consumer example, its tag suites, local test site, diagnostic workflow, and project layout.
- [Consumer AI agent guide](consumer-agent-guide.md) — canonical instructions for AI agents authoring, running, diagnosing, and rerunning Pickleball scenarios from a Maven consumer.
- [Cucumber compatibility](cucumber-compatibility.md) — mix Pickleball dynamic steps with standard Cucumber steps, hooks, tags, tables, and plugins.
## Browser scenarios

- [Dynamic steps](dynamic-steps.md) — describe Selenium elements, actions, assertions, values, contexts, and phrase chains directly in Gherkin.
- [Custom element definitions](custom-element-definitions.md) — optionally add project-specific element names without placing selectors in feature files.
- [Keyboard expressions](key-parser-dsl.md) — express sequential, simultaneous, and held-key input.
## Data and reusable behavior
- [Mapping and templating](mapping-and-templating.md) — use supported `MAP ... TABLE VALUES`, `MAP ... TEXT/OBJECT VALUE`, and `CLEAR SAVED VALUES` steps together with comma-step `save` actions.
- [Data values and Data Elements](data-values-and-elements.md) — understand Jackson container preservation, template rendering, native Cucumber values, and explicit JSON-to-DataTable conversion.
- [Configuration files and resource mapping](config-files-and-resource-mapping.md) — load shared YAML, JSON, XML, CSV, and text resources.
- [Component scenarios](component-scenarios.md) — invoke reusable scenario-sized flows with `RUN SCENARIOS`.
- [Service-call scenarios](service-call-scenarios.md) — locate reusable REST and SOAP component scenarios, build requests with generic mappings, and execute them.
- [Date and time utilities](date-time-utilities.md) — create, adjust, format, and compare temporal values.
## Conditional structure

- [Nested steps](nested-steps.md) — arrange parent and child steps and pass conditions or page context downward.
- [Block conditionals](block-conditionals.md) — choose one `IF:` / `ELSE-IF:` / `ELSE:` branch while keeping normal reports focused on executed business steps.

## Execution

- [Execution configuration](configuration.md) — control tags, feature locations, browsers, named/composite profiles, direct `pkb_run_profile` overrides, ReportPortal aliases, parallelism, logging, reports, and local overrides.
- [AI and automation run configuration](ai-run-configuration.md) — use `pkb_run_profile` as a deterministic full RunVar override for controlled AI/automation reruns.
- [Diagnostic lineage and metadata](diagnostic-lineage-metadata.md) — distinguish lineage annotations, execution/evidence RunVars, and derived evidence; use `pkb_changed_variables` only for intentionally changed RunVars.
- [Diagnostic reporting](diagnostic-reporting.md) — capture layered AI-oriented evidence, Git/source provenance, step/capability metadata, compressed deep traces, browser screenshots/fingerprints, configuration provenance, and configurable retention.
- [AI diagnostic reporting plan](ai-diagnostic-reporting-plan.md) — follow the sparse-first investigation and controlled-rerun model.
## Working consumer project

The example project is not pseudocode. It contains a Maven dependency, a runner, a loopback server, browser pages, REST and SOAP endpoints, feature files, configuration data, and reusable call definitions. Its maintained human-facing documentation is centralized in the [Consumer project guide](consumer-project.md); the nested consumer README is intentionally only a pointer.

- [Consumer `pom.xml`](../maven-consumer-project/pom.xml)
- [Pickleball test runner](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java)
- [Local browser and service test server](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java)
- [Executable feature files](../maven-consumer-project/src/test/resources/features)
- [Example run profiles](../maven-consumer-project/src/test/resources/profiles.yaml)
- [Shared configuration data](../maven-consumer-project/src/test/resources/configs)
- [Reusable service calls](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)

[Return to the project README](../README.md)
