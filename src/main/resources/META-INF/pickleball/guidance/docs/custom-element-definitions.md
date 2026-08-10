# Custom Element Definitions

> **Working feature example:** [`catalog-context.feature`](../maven-consumer-project/src/test/resources/features/catalog-context.feature) uses the project-specific `Product Card`, `Status Badge`, and `Test Panel` element categories registered by the consumer runner.

> This page is primarily for project maintainers. Feature authors normally use the element vocabulary already registered by the project.

Pickleball's built-in element language handles common HTML controls. A project can optionally add business-specific categories so feature files use names such as `Test Panel`, `Product Card`, `Status Badge`, or `Account Row` without exposing selectors.

## Register categories before Cucumber runs

The consumer runner registers project vocabulary in a lifecycle hook:

```java
@LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
public static void beforeRun() {
    registerProjectElementVocabulary();
    testSite = LocalTestSite.start(TEST_SITE_PORT);
}
```

See the complete [PickleballTests.java](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java).

## Simple category

```java
ExecutionDictionary dictionary = getExecutionDictionary();

dictionary.category("Radio Button")
        .addBase("//input[@type='radio']");
```

Feature files can then write:

```gherkin
* , click the "Email" Radio Button
```

## Text-aware business category

```java
dictionary.category("Product Card")
        .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
        .addBase(
            "//article[contains(concat(' ', normalize-space(@class), ' '), ' product-card ')]"
        );
```

Feature files can use visible text and state with that category:

```gherkin
* , ensure the "Starter Plan" Product Card is displayed
* , ensure the "Team Plan" Product Card is not displayed
```

## Page or section context

```java
dictionary.category("Test Panel")
        .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
        .addBase(
            "//section[contains(concat(' ', normalize-space(@class), ' '), ' test-panel ')]"
        );
```

The category can scope another dynamic lookup:

```gherkin
* , in the "Secondary Queue" Test Panel, click the "Approve" Button
```

Only the custom category definition contains the technical selector. Scenario steps remain business-readable.

## Combine or extend categories

The execution dictionary can:

- add one or more base selectors;
- add `and` or `or` selector logic;
- inherit text-matching behavior;
- reuse existing categories;
- register page contexts or frames; and
- attach category flags that affect lookup behavior.

Keep selectors centralized in startup configuration instead of repeating them across feature files.

## When to add a category

Add one when:

- the term is meaningful to feature authors;
- it appears repeatedly across scenarios;
- the application has a stable semantic structure; or
- the built-in category is too broad for the project's UI.

Do not create a separate category for every individual element. Dynamic text, state, ordinal, and context matching should continue to do most of the work.

## Working examples

- [Registered consumer categories](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java)
- [Features using those categories](../maven-consumer-project/src/test/resources/features/catalog-context.feature)
- [Catalog page DOM](../maven-consumer-project/src/test/resources/site/catalog.html)

[Previous: Execution Configuration](configuration.md) · [Documentation home](README.md)
