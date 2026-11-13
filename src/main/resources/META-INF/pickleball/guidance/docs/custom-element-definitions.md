# Custom Element Definitions

> **Working feature example:** [`catalog-context.feature`](../maven-consumer-project/src/test/resources/features/catalog-context.feature) uses the project-specific `Product Card`, `Status Badge`, and `Test Panel` categories registered by the consumer runner. [`browser-action-contracts.feature`](../maven-consumer-project/src/test/resources/features/browser-action-contracts.feature) uses the consumer overlay of the built-in `Close Button` category.

> This page is primarily for project maintainers. Feature authors normally use the element vocabulary already registered by the project. The built-in names are listed in [Dynamic Steps](dynamic-steps.md).

Pickleball's built-in element language handles common HTML controls. A project can optionally add business-specific categories so feature files use names such as `Test Panel`, `Product Card`, or `Status Badge` without exposing selectors.

Registering a reserved name, or a name that starts with `Step `, logs a WARN. The message names the category, what it is reserved for, and that the custom definition will not be used. `Button`, `Loading`, and an overlay of `Close Button` do not warn. The full list is in [Reserved element names](reserved-element-names.md).

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

Call `reset()` first when the category should be redefined from scratch. `reset()` clears registrations defined directly on that name, including locator builders and flags such as `ALL_CONTEXTS`. Child aliases that inherit from it are left intact, and they inherit nothing until the category is registered again.

```java
dictionary.category("Close Button")
        .reset()
        .addBase("//*[self::button and @aria-label='Close']");
```

`Loading` is registered with `CategoryFlags.ALL_CONTEXTS` and the built-in locator. With the DOM engine (default), that scan is one query when the engine accepts `--pierce`. An engine that does not, such as the Rust 2.4.0 override, keeps the Java walk of the current document, the top document, frames, and open shadow roots. `category("Loading").or(xpath)` adds a clause and keeps both. `reset()` clears them. `restoreBuiltinLoading()` puts the locator and the flag back. Add the `or` again after that restore. A category that `inheritsFrom("Loading")` inherits the scan. Only a flagged category searches every context. `Button` does not.

Do not re-register a built-in with the same locator as a "new" category. `Radio Button` is already built in; the consumer does not register it.

## Fluent registration API

`ExecutionDictionary.category(name)` and `categories(name, ...)` return a `CategorySpec`. Common methods:

| Method | Purpose |
|---|---|
| `addBase(xpath)` | Set the primary locator. A later `addBase` on the same name overwrites it. |
| `addAlternateBase(xpath)` | Register an alternate structural locator for the same category. |
| `children("Buttons", ...)` | Register plural or alias names that inherit from this category. |
| `inheritsFrom(...)` | Pull in another category's matchers, including the text-matching parents below. |
| `and(...)` / `or(...)` | Add extra locator builders or constant XPath fragments. XPath builders still work. They are deprecated for a new definition that can be written as a combined clause. |
| `combined(clause)` | Add a Control Engine clause, for example `role=button`. Children inherit it. This is the form to use for a new definition. |
| `andAnyCategories(...)` / `orCategories(...)` | Reuse other categories' matchers without copying their XPath. Related methods include `andCategories` and `orAllCategories`. |
| `reset()` | Clear this category's own registrations before redefining it. |
| `flags(...)` | Attach lookup flags. |
| `context(...)` / `startingContext(...)` | Supply a `SearchContext` builder and mark the category as page context. |

`and(...)` does not keep source order. The fragments are sorted by specificity (a path that names an element, such as `//button`, is more specific than `//*...`) and then joined:

- When every fragment starts with `//*`, the result is one `//*` plus each fragment's suffix. `//*[@id='a']` and `//*[@data-kind='card']` become `//*[@id='a'][@data-kind='card']` (or the other order when the scores differ).
- When the most specific fragment starts with `//` but not `//*`, that fragment stays first and each later `//*` fragment loses its `//*` and is appended. `//button` plus `//*[@data-kind='card']` becomes `//button[@data-kind='card']`.
- Otherwise the fragments are joined with the XPath `and` operator.

A constant fragment should be a `//*[...]` predicate when it is meant to tighten the base element. A second full path is not appended as that element's predicate.

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

## How a category reaches the page

The dictionary resolves a category to a combined clause when one was registered, and keeps the XPath as the fallback. `SelectorTranslator` emits the combined form first: target clauses are one AND group, for example `(role=button AND text="Save")`, and context clauses are another. A phrase that also carries XPath sends both. The Control Engine runs the combined pipeline and runs the XPath only when that pipeline matches nothing. A custom category defined only by XPath still resolves. It logs one deprecation warning per run and definition (`xpath-definition:<category>`) telling the author to add `combined(...)`. Built-in categories that have no faithful engine clause stay XPath and do not warn.

Built-ins with a combined clause, because the role engine reproduces them: `Button`, `Submit Button`, `Link`, `Textbox`, `Textarea`, `Dropdown`, `Radio Button`, and `Checkbox`. These stay XPath-only, with the reason: `Close Button` (also links, images, and an id prefix), `Date Textbox` (date-specific attributes), `Toggle` (not `role=switch`), `Text` (any text container), `Icon` / `Image` (img, icon font, svg, or an empty link), `Menu` / `Menu Item` (a list item, not `role=menu`), `Modal` / `Dialog` (dialog, or a modal id, or a misspelled aria attribute), `Tab` / `Tab Panel` only when the XPath is a bare role — the built-in also inherits text and label matchers, so it stays XPath — `Section` / `Question` (a `div`, not `role=region`), and `Table`, `Row`, `Header`, `Header Row`, `Cell`, `Column`, and `Field` (structural XPath the role engine does not reproduce).

A phrase that has not yet been turned into XPath is translated once by `SelectorTranslator`. Known categories become `role=...` inside the AND group. Any other capitalized name, including `Product Card`, becomes `desc="..."`. Pure CSS still passes through and is not deprecated.

The same translator covers an OS desktop element. Set the phrase source to desktop. A template image becomes `image=template=<path>`. OCR text becomes `image=ocr~="<text>"`. The pipeline starts with `source=desktop`. Query, OCR, and template matching go through that one adapter. Desktop click and type work on the Java engine, which lists those verbs. They fail with `error:` / `fix:` only when `capabilities.desktop` lacks the verb, such as the Rust 2.4.0 override. Browser `submit`, `dblclick`, `contextclick`, `drag`, and `key` follow `capabilities.actions` the same way.

Live Workbench `inspect-page` and `find-element` post to the worker. The worker calls `PageElementQuery`, which calls `ElementOperations.describe` once. `workbench_element_inspect` calls `ElementControl`, which calls that same `describe`. A controller `query` string is the engine selector (`css=`, `xpath=`, `text=`, `image=`, relatives, `>>`, AND/OR/NOT) on that same call. Counts and evidence are the batch rows, not a second per-element read. Offline HTML inspect stays `source=offline-html` and does not run the engine query. There is no second locator implementation in the GUI or the CLI.

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

## Project category files

A project can add categories without a Java hook. Put a YAML file at `dalton-entities/ElementCategory/<Name>.yaml`. Pickleball loads those files after the built-in categories, on the next run, the first time the execution dictionary is used. Saving a file does not change a run that has already started.

```yaml
name: Status Badge
base: //*[contains(concat(' ', normalize-space(@class), ' '), ' status-badge ')]
inherits: Contains Text
overlay: false
```

`dalton category put|remove` is the write path. The Workbench Editor only forwards. A built-in name is immutable. `overlay: true` adds a project locator and does not replace the built-in. `Loading` keeps `ALL_CONTEXTS`. `ALL_CONTEXTS` is a flag, not a name. Reserved names in [Reserved element names](reserved-element-names.md) are rejected. When Dalton is on, the put is stored with `db_edited` and the file is written by `dalton extract`. When Dalton is off, the command writes the YAML file and does not open the database.

