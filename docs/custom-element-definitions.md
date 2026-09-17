# Custom Element Definitions

> **Working feature example:** [`catalog-context.feature`](../maven-consumer-project/src/test/resources/features/catalog-context.feature) uses the project-specific `Product Card`, `Status Badge`, and `Test Panel` categories registered by the consumer runner. [`browser-action-contracts.feature`](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature) uses the consumer overlay of the built-in `Close Button` category.

> This page is primarily for project maintainers. Feature authors normally use the element vocabulary already registered by the project. The built-in names are listed in [Dynamic Steps](dynamic-steps.md).

Pickleball's built-in element language handles common HTML controls. A project can optionally add business-specific categories so feature files use names such as `Test Panel`, `Product Card`, or `Status Badge` without exposing selectors.

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

## Text-aware business category

Use `addBase` for the structural locator and inherit visible-text matching when feature authors will write a quoted name next to the category:

```java
ExecutionDictionary dictionary = getExecutionDictionary();

dictionary.category("Status Badge")
        .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
        .addBase(
            "//*[contains(concat(' ', normalize-space(@class), ' '), ' status-badge ')]"
        );

dictionary.category("Product Card")
        .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
        .addBase(
            "//article[contains(concat(' ', normalize-space(@class), ' '), ' product-card ')]"
        );
```

Feature files can then use visible text and state with those categories:

```gherkin
* , ensure the "Available" Status Badge is displayed
* , ensure the "Starter Plan" Product Card is displayed
* , ensure the "Team Plan" Product Card is not displayed
```

`Textbox`, `Dropdown`, and similar built-ins already match labeled, named, and placeholder text. Do not add a project category merely so `"First Name" Textbox` can resolve.

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

## Overlay a built-in category

`addBase` writes the category's primary locator. It does not clear inherited `and` / `or` builders, flags, aliases, or parent relationships.

The Maven consumer specializes the built-in `Close Button` that way, so `close` can find the test-site dismiss control:

```java
dictionary.category("Close Button")
        .addBase("//*[self::button and @aria-label='Close']");
```

Call `reset()` first when the category should be redefined from scratch. `reset()` clears registrations defined directly on that name. Child aliases that inherit from it are left intact.

```java
dictionary.category("Close Button")
        .reset()
        .addBase("//*[self::button and @aria-label='Close']");
```

Do not re-register a built-in with the same locator as a "new" category. `Radio Button` is already built in; the consumer does not register it.

## Fluent registration API

`ExecutionDictionary.category(name)` and `categories(name, ...)` return a `CategorySpec`. Common methods:

| Method | Purpose |
|---|---|
| `addBase(xpath)` | Set the primary locator. A later `addBase` on the same name overwrites it. |
| `addAlternateBase(xpath)` | Register an alternate structural locator for the same category. |
| `children("Buttons", ...)` | Register plural or alias names that inherit from this category. |
| `inheritsFrom(...)` | Pull in another category's matchers, including the text-matching parents below. |
| `and(...)` / `or(...)` | Add extra locator builders or constant XPath fragments. |
| `andAnyCategories(...)` / `orCategories(...)` | Reuse other categories' matchers without copying their XPath. Related methods include `andCategories` and `orAllCategories`. |
| `reset()` | Clear this category's own registrations before redefining it. |
| `flags(...)` | Attach lookup flags. |
| `context(...)` / `startingContext(...)` | Supply a `SearchContext` builder and mark the category as page context. |

Keep selectors centralized in startup configuration instead of repeating them across feature files.

## Text-matching parents

Pass these Java constants to `inheritsFrom(...)`. They are not Gherkin vocabulary.

| Constant | When to inherit it |
|---|---|
| `ExecutionDictionary.CONTAINS_TEXT` | Usual choice. Quoted text matches descendant visible text, as in `"Starter Plan" Product Card`. |
| `ExecutionDictionary.DIRECT_TEXT` | Match the element's own visible text rather than descendant text. |
| `ExecutionDictionary.COLOCATED_TEXT` | Match visible text that sits with the control rather than nested deeper. |
| `ExecutionDictionary.TEXT_CONTENT_OR_ATTRIBUTE` | Match descendant text, or name-like attributes on an otherwise empty element. |
| `ExecutionDictionary.HTML_NAME_ATTRIBUTES` | Match `id`, `title`, `name`, and similar naming attributes. |

Prefer `CONTAINS_TEXT` for business containers and badges. Skip text inheritance when the category is identified only by structure or role.

## Frames, shadow roots, and flags

Use the dedicated helpers rather than a plain `addBase` when lookup must switch into a frame or shadow root:

```java
dictionary.registerIframe("App Frame")
        .addBase("//iframe[@id='app']");

dictionary.registerShadowRoot("Widget Host")
        .addBase("//*[@data-widget='host']");
```

`registerTopLevelIframe` and `registerTopLevelShadowRoot` also return to default content before resolving the host.

`flags(...)` accepts `ExecutionDictionary.CategoryFlags` values. The ones maintainers normally set are:

| Flag | Effect |
|---|---|
| `PAGE_CONTEXT` | Treat the match as a lookup context for later elements. |
| `PAGE_TOP_CONTEXT` | Same, and resolve from the top of the page. |
| `IFRAME` | The category is an iframe host. Prefer `registerIframe`. |
| `SHADOW_HOST` | The category is a shadow-root host. Prefer `registerShadowRoot`. |
| `NON_DISPLAY_ELEMENT` | The control may not be a displayed node (file inputs and some toggles). |

The iframe and shadow helpers set the matching flag for you.

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
- [Close Button overlay used by `close`](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature)
- [Catalog page DOM](../maven-consumer-project/src/test/resources/site/catalog.html)

[Previous: Execution Configuration](configuration.md) · [Documentation home](README.md)
