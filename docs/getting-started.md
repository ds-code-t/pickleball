# Getting Started

> **Working feature example:** [`dynamic-steps.feature`](../maven-consumer-project/src/test/resources/features/dynamic-steps.feature) — a small browser feature you can run after completing the setup on this page.

A consumer project normally needs only two Pickleball-specific additions:

1. the Pickleball test dependency; and
2. one test runner that extends `PickleballRunner`.

Most test behavior can then be written in `.feature` files.

## Requirements

- Java 21
- Maven 3.9 or newer, or an equivalent Gradle setup
- a Selenium-supported browser for browser scenarios

## Maven dependency

The working consumer centralizes the version in a Maven property:

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <pickleball.version>2.1.1</pickleball.version>
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
        PKB_props.plugins("pretty");
        PKB_props.browser("chrome");
    }
}
```

Use a class name ending in `Tests` so normal Maven Surefire discovery can find it.

The working [PickleballTests.java](../maven-consumer-project/src/test/java/com/example/pickleball/PickleballTests.java) also:

- selects `@all` as its default tag expression;
- registers project-specific element categories;
- starts the local test server before Cucumber runs; and
- stops the server after the run.

The server setup is an example-project lifecycle hook, not a requirement for every Pickleball consumer. A real application suite can test an already-running application instead.

## Suggested layout

```text
your-project/
├── pom.xml
└── src/test/
    ├── java/
    │   └── com/example/tests/
    │       ├── PickleballTests.java
    │       └── ProjectSteps.java        # optional custom Cucumber steps
    └── resources/
        ├── features/
        │   └── example.feature
        ├── calls/                       # optional reusable service calls
        ├── configs/                     # optional shared data
        └── pickleball_local.properties  # optional local overrides
```

## Run

```bash
mvn test
```

The consumer also provides Maven profiles such as:

```bash
mvn test -Psmoke
mvn test -Pbrowser
mvn test -Pdata
mvn test -Pworkflow
```

Any Cucumber tag expression can be passed directly:

```bash
mvn test "-Dpkb_tags=@forms and not @dialogs"
```

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

See the working [browser feature files](../maven-consumer-project/src/test/resources/features) and continue with [Dynamic Steps](dynamic-steps.md).

[Documentation home](README.md)
