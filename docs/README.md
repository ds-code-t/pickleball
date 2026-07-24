# Pickleball Documentation

Pickleball extends Cucumber with a dynamic feature-file language while preserving normal Cucumber behavior. The pages below describe the supported authoring model and link to the real, executable examples in [`maven-consumer-project`](../maven-consumer-project/README.md).

Each functional guide begins with a prominent link to the specific consumer-project feature file that demonstrates the behavior.

## Start here

- [Getting started](getting-started.md) — add the Maven dependency, create one runner, and run feature files.
- [Cucumber compatibility](cucumber-compatibility.md) — mix Pickleball dynamic steps with standard Cucumber steps, hooks, tags, tables, and plugins.

## Browser scenarios

- [Dynamic steps](dynamic-steps.md) — describe Selenium elements, actions, assertions, values, contexts, and phrase chains directly in Gherkin.
- [Custom element definitions](custom-element-definitions.md) — optionally add project-specific element names without placing selectors in feature files.
- [Keyboard expressions](key-parser-dsl.md) — express sequential, simultaneous, and held-key input.

## Data and reusable behavior

- [Mapping and templating](mapping-and-templating.md) — use Scenario Outline values, runtime values, nested data, JSONata reads, and writable NodeMap paths.
- [Configuration files and resource mapping](config-files-and-resource-mapping.md) — load shared YAML, JSON, XML, CSV, and text resources.
- [Component scenarios](component-scenarios.md) — invoke reusable scenario-sized flows with `RUN SCENARIOS`.
- [Service-call scenarios](service-call-scenarios.md) — define and invoke reusable REST and SOAP calls.
- [Date and time utilities](date-time-utilities.md) — create, adjust, format, and compare temporal values.

## Conditional structure

- [Nested steps](nested-steps.md) — arrange parent and child steps and pass conditions or page context downward.
- [Block conditionals](block-conditionals.md) — choose one `IF:` / `ELSE-IF:` / `ELSE:` branch while keeping normal reports focused on executed business steps.

## Execution

- [Execution configuration](configuration.md) — control tags, feature locations, browsers, parallelism, logging, reports, and local overrides.

## Working consumer project

The example project is not pseudocode. It contains a Maven dependency, a runner, a loopback server, browser pages, REST and SOAP endpoints, feature files, configuration data, and reusable call definitions.

- [Consumer `pom.xml`](../maven-consumer-project/pom.xml)
- [Pickleball test runner](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java)
- [Local browser and service test server](../maven-consumer-project/src/test/java/com/example/pickleball/support/LocalTestSite.java)
- [Executable feature files](../maven-consumer-project/src/test/resources/features)
- [Shared configuration data](../maven-consumer-project/src/test/resources/configs)
- [Reusable service calls](../maven-consumer-project/src/test/resources/calls/service-call-definitions.feature)

[Return to the project README](../README.md)
