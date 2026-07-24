# Pickleball

Pickleball is a Java 21 testing framework built on Cucumber. It keeps normal Gherkin and Cucumber behavior while adding a dynamic feature-file language for browser tests, service calls, data mapping, conditional flow, and reusable scenarios.

## How Pickleball differs from standard Cucumber

In a typical Cucumber project, each new Gherkin sentence must match a Java step definition. Pickleball includes reusable dynamic steps that can interpret elements, values, actions, assertions, conditions, and context directly from a feature file.

For most tests, feature authors can create new scenarios without adding Java code. A step can locate a Selenium element by its visible text, type, state, position, and surrounding page context instead of requiring a separate XPath, CSS selector, page-object field, or custom step definition for every interaction.

Pickleball also adds:

- Java Selenium integration for browser navigation, element selection, actions, and assertions;
- nested steps that pass conditions and page context to child steps;
- inline and block `if` / `else-if` / `else` flow;
- mapping and templating for scenario values, saved values, JSON-like data, and resource files;
- reusable component scenarios and REST or SOAP service-call scenarios; and
- a small consumer setup consisting primarily of the Pickleball dependency and one test runner.

Pickleball remains compatible with standard Cucumber features such as tags, Scenario Outlines, Examples tables, DataTables, DocStrings, hooks, plugins, and custom Java step definitions. Dynamic Pickleball steps and ordinary project-specific Cucumber steps can be used together in the same suite.

The working [`maven-consumer-project`](maven-consumer-project/README.md) starts a loopback test server during the run. Its scenarios exercise both Selenium against a local HTML test site and service calls against local REST and SOAP endpoints.

[Read the Pickleball documentation](docs/README.md)
