# Getting Started

Adding Pickleball to an existing Java test project requires only two framework additions:

1. Add the Pickleball test dependency.
2. Add one Pickleball test runner.

After that, most test work happens in `.feature` files.

## Requirements

- Java 21
- Maven or Gradle
- Feature files under `src/test/resources/features`, unless a different location is configured

## 1. Add the dependency

### Maven

Add this dependency to `pom.xml`:

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

Add this to `build.gradle`:

```groovy
dependencies {
    testImplementation 'tools.dscode:pickleball:2.1.0'
}
```

### Gradle — Kotlin DSL

Add this to `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("tools.dscode:pickleball:2.1.0")
}
```

## 2. Add the test runner

Create a test class such as:

```text
src/test/java/com/example/tests/PickleballTests.java
```

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

Use a name ending in `Tests` so normal Maven and Gradle test discovery can find the runner.

That completes the required Pickleball setup.

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
            ├── configs/
            └── pickleball_local.properties
```

The `configs` directory and local properties file are optional.

## Run the scenarios

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

Scenarios may also be run from IntelliJ using the Cucumber plugin or the test runner.

## Continue with feature files

- [Dynamic steps](dynamic-steps.md)
- [Mapping and templating](mapping-and-templating.md)
- [Local execution settings](configuration.md#local-overrides)

---

[Documentation home](README.md) · [Next: Dynamic steps](dynamic-steps.md)
