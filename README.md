# Pickleball

Pickleball is a dynamic testing framework built on an enhanced version of Cucumber for Java 21.

Traditional Cucumber projects connect each Gherkin sentence to a separately implemented step definition. Pickleball adds a dynamic execution layer that can interpret expressive, natural-language test instructions at runtime. Tests can describe browser elements, actions, assertions, conditions, context, and nested control flow without requiring a new Java method for every sentence.

Pickleball is intended to keep consumer-project setup small while supporting reusable test behavior, project-specific element definitions, local execution settings, and structured test scenarios.

## Documentation

- [Documentation home](docs/README.md)
- [Getting started](docs/getting-started.md)
- [Configuration and `pkb_` properties](docs/configuration.md)
- [Custom element and XPath definitions](docs/custom-element-definitions.md)
- [Dynamic steps](docs/dynamic-steps.md)
- [Nested steps and conditional flow](docs/nested-steps.md)
- [Keyboard DSL](docs/key-parser-dsl.md)
