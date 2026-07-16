# Cucumber Tag System

The scenarios use a layered tag system. A scenario can be selected broadly by suite, by functional page, or by one focused capability.

## Suite tags

| Tag | Purpose |
|---|---|
| `@all` | Aggregate entry point attached to every executable scenario |
| `@regression` | Complete regression coverage; currently covers the same scenarios as `@all` |
| `@smoke` | One representative path from each major functional area |
| `@browser` | Scenarios that require Selenium and the local website |
| `@local-site` | Scenarios that navigate to the loopback test site |
| `@data` | Data/resource checks that do not require browser navigation |

`@all` is intentionally explicit on every scenario. This means the all-suite entry point remains stable even if helper features or untagged experimental scenarios are added later.

## Functional-area tags

| Tag | Functional area |
|---|---|
| `@navigation` | Dashboard links and primary navigation |
| `@forms` | Form controls and dynamic actions/assertions |
| `@catalog` | Repeated elements, context scoping, and custom element categories |
| `@mapping` | Scenario values, templates, and resource data |
| `@resources` | Shared and on-demand resources |
| `@workflow` | Nested steps and block conditional paths |
| `@keyboard` | Key expressions, modifiers, and named keys |
| `@dialogs` | Alerts and confirmations |
| `@components` | `RUN SCENARIOS` and reusable scenario components |

## Focused capability tags

These tags support smaller development or troubleshooting runs:

| Area | Capability tags |
|---|---|
| Navigation | `@links`, `@page-navigation`, `@primary-navigation` |
| Forms | `@dynamic-steps`, `@form-input`, `@selection-controls`, `@state-assertions`, `@chained-steps`, `@form-submission`, `@clear-reset`, `@pointer-actions` |
| Catalog | `@context`, `@custom-elements`, `@filtering`, `@ordinal-elements`, `@repeated-elements`, `@page-context`, `@scoped-elements`, `@expanded-collapsed` |
| Mapping | `@templating`, `@scenario-outline`, `@resource-mapping`, `@config-resources`, `@on-demand-resource` |
| Workflow | `@nested-steps`, `@conditional-steps`, `@question-parent`, `@block-conditionals`, `@phrase-condition`, `@expression-condition`, `@inline-condition` |
| Keyboard | `@keyboard-expressions`, `@modifier-keys`, `@named-keys` |
| Dialogs | `@alerts`, `@confirmations`, `@accept-dialog`, `@dismiss-dialog` |
| Components | `@run-scenarios`, `@component-caller`, `@component-definition` |

## Scenario matrix

| Feature | Scenario | Main tags |
|---|---|---|
| Navigation | Open a playground and return | `@navigation @smoke @links @page-navigation` |
| Navigation | Use primary navigation | `@navigation @primary-navigation` |
| Forms | Text entry and selection | `@forms @smoke @form-input @selection-controls` |
| Forms | State assertions and chained actions | `@forms @state-assertions @chained-steps @form-submission` |
| Forms | Clear and reset | `@forms @clear-reset` |
| Forms | Pointer actions | `@forms @pointer-actions` |
| Catalog | Filter and custom elements | `@catalog @smoke @custom-elements @filtering` |
| Catalog | Ordinal selection | `@catalog @ordinal-elements @repeated-elements` |
| Catalog | Page context | `@catalog @page-context @scoped-elements` |
| Catalog | Expanded/collapsed states | `@catalog @expanded-collapsed @state-assertions` |
| Mapping | Scenario Outline form data | `@mapping @templating @scenario-outline @browser` |
| Mapping | Shared configs | `@mapping @resources @data @smoke @config-resources` |
| Mapping | On-demand file | `@mapping @resources @data @on-demand-resource` |
| Workflow | Nested conditional children | `@workflow @smoke @nested-steps @conditional-steps` |
| Workflow | Question-mark parent | `@workflow @nested-steps @question-parent` |
| Workflow | Phrase-style blocks | `@workflow @block-conditionals @phrase-condition` |
| Workflow | Expression-style blocks | `@workflow @block-conditionals @expression-condition` |
| Workflow | Inline block chain | `@workflow @block-conditionals @inline-condition` |
| Keyboard | Modifier expression | `@keyboard @smoke @keyboard-expressions @modifier-keys` |
| Keyboard | Named key | `@keyboard @keyboard-expressions @named-keys` |
| Dialogs | Accept alert | `@dialogs @smoke @alerts @accept-dialog` |
| Dialogs | Accept confirmation | `@dialogs @confirmations @accept-dialog` |
| Dialogs | Dismiss confirmation | `@dialogs @confirmations @dismiss-dialog` |
| Components | Component caller | `@components @smoke @run-scenarios @component-caller` |
| Components | Component definition | `@components @component-definition @scenario-outline` |

Every row in this matrix also has `@all` and `@regression`.

## Useful expressions

```powershell
# Run everything
mvn test "-Dpkb_tags=@all"

# Run one functional area
mvn test "-Dpkb_tags=@catalog"

# Run only form state checks
mvn test "-Dpkb_tags=@forms and @state-assertions"

# Run nested behavior but not block conditions
mvn test "-Dpkb_tags=@workflow and @nested-steps and not @block-conditionals"

# Run browser tests except dialogs
mvn test "-Dpkb_tags=@browser and not @dialogs"

# Run all non-browser resource checks
mvn test "-Dpkb_tags=@data and @resource-mapping"
```
