# Getting Started

> **Working feature example:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) is a small browser feature you can run after setup.

A consumer normally needs the Pickleball test dependency and one runner extending `PickleballRunner`. Most test behavior can then live in `.feature` files.

## Requirements

- Java 21
- Maven 3.9 or newer, or an equivalent Gradle setup
- a Selenium-supported browser for browser scenarios

## Maven dependency

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <pickleball.version>2.1.5</pickleball.version>
</properties>

<dependency>
    <groupId>tools.dscode</groupId>
    <artifactId>pickleball</artifactId>
    <version>${pickleball.version}</version>
    <scope>test</scope>
</dependency>
```

Use the version selected by your project. See the complete [consumer `pom.xml`](../maven-consumer-project/pom.xml).

## Test runner

```java
package com.example.tests;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

public final class PickleballTests extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        PKB_props.glue("com.example.tests");
        PKB_props.features("classpath:features");
        PKB_props.configPath("configs");
        PKB_props.plugins("pretty");
        PKB_props.browser("chrome");
    }
}
```

Use a class name ending in `Tests` so normal Maven Surefire discovery can find it.

The working [PickleballTests.java](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java) additionally selects `@all`, registers project element categories, and starts/stops the local example test server through lifecycle hooks.

## Suggested layout

```text
your-project/
├── pom.xml
└── src/test/
    ├── java/com/example/tests/
    │   ├── PickleballTests.java
    │   └── ProjectSteps.java        # optional custom Cucumber glue
    └── resources/
        ├── features/
        ├── calls/                   # optional reusable service calls
        ├── component/               # optional component scenarios
        ├── data/                    # optional structured/scenario data
        ├── configs/                 # optional shared config mapping
        ├── profiles.yaml            # optional named Pickleball profiles
        ├── pickleball.properties
        └── pickleball_local.properties
```

## Run

```bash
mvn test
```

Filter normally with RunVars such as:

```bash
mvn test "-Dpkb_tags=@forms and not @dialogs"
```

## Named profiles

```yaml
qa:
  pkb_tags: "<default_profile.pkb_tags> and @qa"
  pkb_environment: QA
  pkb_browser: CHROME_HEADLESS
```

```bash
mvn test -Dpkb_profile=qa
```

Multiple profile names compose left-to-right:

```bash
mvn test -Dpkb_profile=qa,browser_firefox
```

A named profile automatically receives missing project execution-context RunVars (`pkb_glue`, `pkb_features`, `pkb_datapath`, `pkb_callpath`, `pkb_componentpath`, and `pkb_configpath`) when those values exist in normal project configuration. Optional RunVars do not implicitly inherit.

## Deterministic controlled runs

For automation or an AI agent, use `pkb_runvars` as the direct input:

```bash
mvn test "-Dpkb_runvars=pkb_tags=@smoke, pkb_browser=CHROME_HEADLESS"
```

Project wiring omitted from a controlled input inherits only the six execution-context RunVars listed above. This lets a controlled rerun specify what changes without copying repetitive glue/resource wiring.

Expanded input avoids nested compact assignment parsing:

```text
pkb_runvars.pkb_tags=@smoke
pkb_runvars.pkb_browser=CHROME_HEADLESS
```

Do not mix compact and expanded `pkb_runvars` forms.

A blank execution-context member intentionally suppresses inheritance:

```text
pkb_runvars.pkb_features=
```

The blank suppresses the inherited value and remains blank in the final canonical `pkb_run_profile`; replaying that blank tells Pickleball to use the same historical subsystem fallback behavior.

Runner code can use:

```java
PKB_props.runVars(Map.of(
        "pkb_tags", "@smoke",
        "pkb_browser", "CHROME_HEADLESS"
));
```

`PKB_props.runProfile()` is the read-only getter for the final canonical serialized RunVars. There are no direct `runProfile(String/Map)` input setters; external `pkb_run_profile` input is rejected. Use `pkb_runvars`.

See [Execution Configuration](configuration.md) and [AI Run Configuration](ai-run-configuration.md).

## Configuration mapping path

Prefer `<config:...>` for configuration mappings, while legacy `<configs...>` remains valid. Use `pkb_configpath` only to select where that mapping is loaded from:

```text
pkb_configpath=configs
pkb_configpath=classpath:environment/qa/configs
pkb_configpath=src/test/resources/environment/qa/configs
```

If missing or blank, the historical `configs` resource root remains the Java fallback.

## First feature

```gherkin
Feature: Customer form

  Scenario: Submit a customer
    * navigate to: URL.forms
    * , enter "Ava" in the "First Name" Textbox
    * , select "Premium" in the "Account Type" Dropdown
    * , click the "Submit Form" Button
    * , ensure "Submitted: Ava" Text is displayed
```

Continue with [Dynamic Steps](dynamic-steps.md), and use [Documentation home](README.md) for the complete Pickleball syntax map.
