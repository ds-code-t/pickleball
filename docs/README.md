# Pickleball Documentation

Pickleball feature files are written as executable business-language descriptions. Start with dynamic steps, then use the other pages as the scenario becomes more structured or data-driven.

## Writing feature files

- [Dynamic steps](dynamic-steps.md)  
  Write elements, values, actions, assertions, conditions, and context phrases.
- [Mapping and templating](mapping-and-templating.md)  
  Insert example values, saved scenario values, configuration values, and nested data.
- [Nested steps and conditional flow](nested-steps.md)  
  Organize dependent behavior into readable parent-and-child branches.
- [Block conditionals](block-conditionals.md)  
  Branch while keeping reports focused on the path that actually ran.
- [Component scenarios](component-scenarios.md)  
  Reuse scenario-sized business flows with `RUN SCENARIOS`.
- [Keyboard expressions](key-parser-dsl.md)  
  Describe shortcuts, held keys, and simultaneous key presses.

## Project data and execution settings

- [Shared configuration files and resource data](config-files-and-resource-mapping.md)  
  Make YAML, JSON, XML, CSV, and text resources available to every scenario or load them when needed.
- [Execution configuration and `pkb_` properties](configuration.md)  
  Select tags, features, reports, browsers, log levels, and local overrides.

## Initial project setup

- [Getting started](getting-started.md)  
  Add the dependency and one test runner. These are the only two required framework additions.

## Maintainer customization

- [Custom element and XPath definitions](custom-element-definitions.md)  
  Add project-specific element words such as `Account Row`, `Results Frame`, or `Submit Button`.

## Copyable setup examples

- [`PickleballTests.java`](examples/PickleballTests.java)
- [`pickleball_local.properties.example`](examples/pickleball_local.properties.example)

---

[Return to the project README](../README.md)
