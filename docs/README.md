# Pickleball Documentation

Pickleball feature files are written as executable business-language descriptions. Start with dynamic steps, then use the other pages as the scenario becomes more structured or data-driven.

## Writing feature files

- [Dynamic steps](dynamic-steps.md)  
  Write elements, values, actions, assertions, conditions, and context phrases. See [dynamic-steps.feature](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) and [forms-dynamic-steps.feature](../maven-consumer-project/src/test/resources/features/forms-dynamic-steps.feature).
- [Mapping and templating](mapping-and-templating.md)  
  Insert example values, saved scenario values, configuration values, and nested data. See [mapping-and-resources.feature](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature).
- [Date and time utilities](date-time-utilities.md)  
  Format, parse, reformat, convert, and compare date/time values, including configured business calendars and operating hours. See [date-time-utilities.feature](../maven-consumer-project/src/test/resources/features/date-time-utilities.feature).
- [Nested steps and conditional flow](nested-steps.md)  
  Organize dependent behavior into readable parent-and-child branches. See [nested-and-block-conditionals.feature](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature).
- [Block conditionals](block-conditionals.md)  
  Branch while keeping reports focused on the path that actually ran. See [nested-and-block-conditionals.feature](../maven-consumer-project/src/test/resources/features/nested-and-block-conditionals.feature).
- [Component scenarios](component-scenarios.md)  
  Reuse scenario-sized business flows with `RUN SCENARIOS`. See [component-scenarios.feature](../maven-consumer-project/src/test/resources/features/component-scenarios.feature).
- [Keyboard expressions](key-parser-dsl.md)  
  Describe shortcuts, held keys, and simultaneous key presses. See [keyboard.feature](../maven-consumer-project/src/test/resources/features/keyboard.feature).

## Project data and execution settings

- [Shared configuration files and resource data](config-files-and-resource-mapping.md)  
  Make YAML, JSON, XML, CSV, and text resources available to every scenario or load them when needed. See [mapping-and-resources.feature](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature).
- [Execution configuration and pkb_ properties](configuration.md)  
  Select tags, features, reports, browsers, log levels, and local overrides. See [navigation.feature](../maven-consumer-project/src/test/resources/features/navigation.feature) for a consumer feature that uses configured resources.

## Initial project setup

- [Getting started](getting-started.md)  
  Add the dependency and one test runner. These are the only two required framework additions. See [dynamic-steps.feature](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) for a runnable first example.

## Maintainer customization

- [Custom element and XPath definitions](custom-element-definitions.md)  
  Add project-specific element words such as `Account Row`, `Results Frame`, or `Submit Button`. See [catalog-context.feature](../maven-consumer-project/src/test/resources/features/catalog-context.feature).

## Project notes

- [Feature status notes](feature-status-notes.md)  
  Review implementation and documentation status alongside the [consumer feature suite](../maven-consumer-project/src/test/resources/features/).

## Copyable setup examples

- [PickleballTests.java](examples/PickleballTests.java)
- [pickleball_local.properties](examples/pickleball_local.properties)

---

[Return to the project README](../README.md)
