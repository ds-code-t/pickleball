# Pickleball

Pickleball is a Java 21 testing framework. Its dynamic runtime reads feature-file text for browser tests, service calls, data mapping, conditional flow, and reusable scenarios. The default execution engine is native. Cucumber is a supported compatibility adapter (`pkb_engine=cucumber`), so standard Gherkin features and Java step definitions still work.

## How Pickleball differs from standard Cucumber

In a typical Cucumber project, each new Gherkin sentence must match a Java step definition. Pickleball includes reusable dynamic steps that can interpret elements, values, actions, assertions, conditions, and context directly from a feature file.

For most tests, feature authors can create new scenarios without adding Java code. A step can locate an element by its visible text, type, state, position, and surrounding page context instead of requiring a separate XPath, CSS selector, page-object field, or custom step definition for every interaction.

Pickleball also adds:

- element finding, actions, state, geometry, and observation through the in-repo Java DOM engine packaged in the jar;
- Selenium/WebDriver for browser lifecycle, and for the element path when `pkb_composite=off`;
- nested steps that pass conditions and page context to child steps;
- inline and block `if` / `else-if` / `else` flow;
- mapping and templating for scenario values, saved values, JSON-like data, and resource files;
- reusable component scenarios and REST or SOAP service-call scenarios;
- composable execution profiles through `pkb_profile`, controlled execution input through `pkb_runvars`, and deterministic final RunVar output through `pkb_run_profile`;
- configurable `pkb_configpath` loading with recommended `<config:...>` references and legacy `<configs...>` compatibility;
- a dependency-matched Workbench controller embedded as an opaque payload, with all Pickleball execution isolated in a separate consumer worker JVM;
- optional `pkb_reportingmode=diagnostic` evidence capture with lightweight run/scenario indexes, losslessly compressed deep trace evidence, Git/source provenance, structured step/capability metadata, binary screenshots, compact visual fingerprints, failure clustering, cross-run comparison, and configurable evidence retention;
- opt-in Dalton, a Git-files-to-SQLite working copy for features, configs, logs, and entities (Git files stay the source of truth); and
- a small consumer setup consisting primarily of the Pickleball dependency and one test runner.

Pickleball remains compatible with standard Cucumber features such as tags, Scenario Outlines, Examples tables, DataTables, DocStrings, hooks, plugins, and custom Java step definitions. Dynamic Pickleball steps and ordinary project-specific Cucumber steps can be used together in the same suite.

The working [`maven-consumer-project`](docs/consumer-project.md) starts a loopback test server during the run. Its scenarios exercise a local HTML test site through the DOM engine (WebDriver starts the browser) and service calls against local REST and SOAP endpoints.

[Read the Pickleball documentation](docs/README.md) · [Pickleball Workbench](docs/pickleball-workbench.md) · [Consumer project guide](docs/consumer-project.md) · [Execution configuration](docs/configuration.md) · [AI run configuration](docs/ai-run-configuration.md) · [Diagnostic lineage metadata](docs/diagnostic-lineage-metadata.md) · [Diagnostic reporting](docs/diagnostic-reporting.md)
