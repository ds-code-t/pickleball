# Custom Element and XPath Definitions

> This page is for project maintainers. Feature authors normally use the element vocabulary that has already been configured for the project.

A project can add business-specific element words so feature files describe the application in familiar terms:

```gherkin
Then , in the "Top Panel", click the "Submit" Button
Then , from the "Accounts" Table, verify the "Primary" Account Row is displayed
Then , switch to the "Results Frame"
```

The feature file should describe the application. The test runner can define how words such as `Top Panel`, `Account Row`, or `Results Frame` locate real page elements.

## Add definitions during project startup

The initial dependency and test runner remain the only required setup. Custom definitions are optional additions to the runner.

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

Register shared vocabulary before the Cucumber run so it is available to every feature.

## Add a basic element category

```java
dictionary.category("Project Panel")
    .addBase("//div");
```

Feature files can then use `Project Panel` as an element category.

## Add another way to recognize an element

```java
dictionary.category("Submit Button")
    .or((category, value, operator) ->
        input.byAttribute(type).equals("submit"));
```

This lets the phrase `Submit Button` include an `<input type="submit">` element.

## Combine several HTML element types

```java
dictionary.category("Form Field")
    .and((category, value, operator) ->
        combineOr(
            new XPathy("//input"),
            new XPathy("//textarea"),
            new XPathy("//select")
        ));
```

The business term `Form Field` can now represent inputs, text areas, or selects.

## Reuse an existing category

```java
dictionary.category("Content Container")
    .andAnyCategories("Project Panel")
    .inheritsFrom(ExecutionDictionary.CONTAINS_TEXT)
    .or("//section", "//div");
```

Reusing categories keeps the feature language consistent while allowing project-specific HTML structures.

## Define a page or section context

A category can establish the area in which child elements should be found:

```java
dictionary.category("Top Panel")
    .startingContext((category, value, operator, webDriver, context) ->
        context);
```

This supports feature language such as:

```gherkin
Then , in the "Top Panel", click the "Save" Button
```

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

A project can then describe the frame by its business name instead of repeating iframe-selection details in feature files.

## Mark a page context

```java
dictionary.category("Results Frame")
    .flags(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);
```

A page-context category changes where later element searches begin rather than representing a normal clickable element.

## Other project lifecycle hooks

The runner may also contain project-specific setup, cleanup, or diagnostics:

```java
@LifecycleHook(Phase.AFTER_CUCUMBER_RUN)
public static void afterRun() {
    // Project cleanup
}

@LifecycleHook(Phase.AFTER_SCENARIO_FAIL)
public static void afterScenarioFailure() {
    // Failure diagnostics
}
```

Keep technical setup in the runner so feature files remain focused on the business flow.

---

[Previous: Execution Configuration](configuration.md) · [Documentation home](README.md)
