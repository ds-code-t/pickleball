# Cucumber Compatibility

> **Working feature examples:** [`mapping-and-resources.feature`](../maven-consumer-project/src/test/resources/features/mapping-and-resources.feature) demonstrates standard `Scenario Outline` and `Examples` syntax with Pickleball steps; [`component-scenarios.feature`](../maven-consumer-project/src/test/resources/features/component-scenarios.feature) demonstrates reusable Pickleball behavior inside normal Cucumber feature structure.

Pickleball is built on Cucumber rather than replacing it. A consumer can use Pickleball's dynamic steps where they are useful and ordinary Cucumber Java step definitions where project-specific Java behavior is still needed.

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

[Documentation home](README.md) · [Next: Dynamic Steps](dynamic-steps.md)
