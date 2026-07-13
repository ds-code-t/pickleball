# Custom Element and XPath Definitions

Pickleball's dynamic steps resolve natural-language element categories through an `ExecutionDictionary`. A consumer project can extend that dictionary in its test runner so project-specific terms map to XPath expressions or custom context behavior.

Examples of project vocabulary might include:

- `Submit Button`
- `Top Panel`
- `Account Row`
- `Results Frame`
- `Form Field`

## Register definitions before the Cucumber run

Add a lifecycle hook to the same runner used for initial setup:

```java
package com.example.tests;

import com.xpathy.XPathy;
import tools.dscode.common.annotations.LifecycleHook;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import static com.xpathy.Attribute.type;
import static com.xpathy.Tag.input;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineOr;

public class PickleballTests extends PickleballRunner {

    @Override
    public void globalTestDefaults() {
        PKB_props.glue("com.example.tests.steps");
        PKB_props.features("classpath:features");
        PKB_props.plugins("pretty");
    }

    @LifecycleHook(Phase.BEFORE_CUCUMBER_RUN)
    public static void registerProjectDefinitions() {
        ExecutionDictionary dictionary = getExecutionDictionary();

        dictionary.category("Project Panel")
            .addBase("//div");

        dictionary.category("Submit Button")
            .or((category, value, operator) ->
                input.byAttribute(type).equals("submit"));

        dictionary.category("Form Field")
            .and((category, value, operator) ->
                combineOr(
                    new XPathy("//input"),
                    new XPathy("//textarea"),
                    new XPathy("//select")
                ));
    }
}
```

The initial dependency and runner remain the only required setup. This hook is optional customization placed inside the runner when a project needs its own element vocabulary.

## Add a base XPath

```java
dictionary.category("Project Panel")
    .addBase("//div");
```

This associates `Project Panel` with a base XPath. Dynamic phrases can then use that category as part of their element description.

## Add an alternative definition

```java
dictionary.category("Submit Button")
    .or((category, value, operator) ->
        input.byAttribute(type).equals("submit"));
```

This adds an alternative way to recognize a `Submit Button`: an `<input>` whose `type` attribute equals `submit`.

## Combine several XPath bases

```java
dictionary.category("Form Field")
    .and((category, value, operator) ->
        combineOr(
            new XPathy("//input"),
            new XPathy("//textarea"),
            new XPathy("//select")
        ));
```

This combines several XPath expressions into one category definition.

## Inherit behavior from another category

The consumer project demonstrates category composition:

```java
dictionary.category("Content Container")
    .andAnyCategories("Project Panel")
    .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
    .or("//section", "//div");
```

Composition can reuse existing matching behavior while adding project-specific bases or alternatives.

## Custom starting contexts

A category can supply the DOM or browser context from which descendant searches begin:

```java
dictionary.category("Top Panel")
    .startingContext((category, value, operator, webDriver, context) ->
        context);
```

Starting contexts are useful when a phrase should scope later element searches to a panel, frame, section, or other project-defined region.

## Register an iframe

```java
import com.xpathy.Tag;

import static com.xpathy.Attribute.id;

dictionary.registerTopLevelIframe("Results Frame")
    .and((category, value, operator) ->
        XPathy.from(Tag.iframe)
            .byAttribute(id)
            .equals("iframeResult"));
```

A project can also register default context-switching behavior using `registerDefaultStartingContext(...)`. The existing consumer runner contains a complete example that switches to a registered frame when available.

## Mark a page context

```java
dictionary.category("Results Frame")
    .flags(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);
```

A page-context flag identifies a category that changes the search context rather than merely describing a normal page element.

## Other lifecycle phases

The runner may define project hooks for phases such as:

```java
@LifecycleHook(Phase.AFTER_CUCUMBER_RUN)
public static void afterRun() {
    // Project cleanup
}

@LifecycleHook(Phase.AFTER_SCENARIO_FAIL)
public static void afterScenarioFailure() {
    // Failure diagnostics
}

@LifecycleHook(Phase.AFTER_SCENARIO_PASS)
public static void afterScenarioPass() {
    // Successful-scenario handling
}
```

Keep shared element registration in `BEFORE_CUCUMBER_RUN` so the dictionary is ready before dynamic scenario steps are executed.

---

[Previous: Configuration](configuration.md) · [Documentation home](README.md) · [Next: Dynamic steps](dynamic-steps.md)
