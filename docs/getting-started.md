# Getting Started

For an existing Java test project, the Pickleball framework setup consists of **only two additions**:

1. Add the Pickleball test dependency.
2. Add one Pickleball test-runner class.

Your feature files and any project-specific step definitions are test content, not additional framework setup.

## Requirements

- Java 21
- Maven or Gradle
- Feature files under `src/test/resources/features`, unless another location is configured

## 1. Add the dependency

Choose either Maven or Gradle.

### Maven

Add Pickleball to the `<dependencies>` section of `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>tools.dscode</groupId>
        <artifactId>pickleball</artifactId>
        <version>2.1.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Gradle — Groovy DSL

Add Pickleball to `build.gradle`:

```groovy
dependencies {
    testImplementation 'tools.dscode:pickleball:2.1.0'
}
```

### Gradle — Kotlin DSL

Add Pickleball to `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("tools.dscode:pickleball:2.1.0")
}
```

## 2. Add the test runner

Create:

```text
src/test/java/com/example/tests/PickleballTests.java
```

Replace the package and glue package with names appropriate for your project:

```java
package com.example.tests;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

public class PickleballTests extends PickleballRunner {

    @Override
    public void globalTestDefaults() {
        PKB_props.glue("com.example.tests.steps");
        PKB_props.features("classpath:features");
        PKB_props.plugins("pretty");
    }
}
```

The name `PickleballTests` is intentional: its `Tests` suffix follows the normal test-class naming convention used by Maven and Gradle test discovery.

A copyable version is available at [`examples/PickleballTests.java`](examples/PickleballTests.java).

## That completes the framework setup

No separate Pickleball bootstrap file is required. The two required pieces are:

- the test-scoped dependency; and
- the class extending `PickleballRunner`.

The runner establishes project-wide defaults:

| Setting | Example | Purpose |
|---|---|---|
| `glue` | `com.example.tests.steps` | Packages containing project step definitions and hooks |
| `features` | `classpath:features` | Location of Gherkin feature files |
| `plugins` | `pretty` | Cucumber output/plugin configuration |

Pickleball supplies defaults for values that are not explicitly configured. For example, the feature location defaults to `classpath:features`.

## Suggested project layout

```text
your-project/
├── pom.xml
│   or build.gradle
└── src/
    └── test/
        ├── java/
        │   └── com/example/tests/
        │       ├── PickleballTests.java
        │       └── steps/
        │           └── ProjectSteps.java
        └── resources/
            ├── features/
            │   └── example.feature
            └── pickleball_local.properties
```

The local properties file is optional and should normally be excluded from Git. See [Configuration and local overrides](configuration.md).

## Run the tests

### Maven

```bash
mvn test
```

### Gradle

```bash
./gradlew test
```

On Windows:

```powershell
gradlew.bat test
```

You can also run scenarios through an IntelliJ Cucumber or JUnit run configuration. Pickleball merges the same project and local properties during either form of execution.

---

[Documentation home](README.md) · [Next: Configuration](configuration.md)
