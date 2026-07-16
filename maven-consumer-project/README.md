# Pickleball Dynamic Steps Consumer

A minimal Maven consumer project for the Pickleball browser-testing framework. It uses Pickleball as a test-scoped dependency and runs dynamic-step scenarios against a self-contained HTML5/JavaScript page.

## Requirements

- JDK 21
- Maven 3.9 or newer
- Chrome available to Selenium, or a different browser configured through Pickleball

## Run

```bash
mvn test
```

`PickleballTests` starts a loopback-only HTTP server on `127.0.0.1:8765` before the Cucumber run and stops it afterward. The feature navigates to the URL defined in `src/test/resources/configs/URL.yaml`.

## Project layout

```text
pom.xml
src/test/java/com/example/pickleball/PickleballTests.java
src/test/java/com/example/pickleball/support/LocalTestSite.java
src/test/resources/configs/URL.yaml
src/test/resources/features/dynamic-steps.feature
src/test/resources/site/index.html
```

## What the scenarios cover

- Accessible selection of textboxes, a textarea, a checkbox, radio buttons, a dropdown, text, and buttons
- Entering values and observing JavaScript-driven DOM changes
- Checkbox checked/unchecked state
- Radio-button selected state
- Dropdown option selection
- Ordinal matching with the second and last repeated `Choose` buttons
- Multiple actions in one comma-delimited dynamic step

## Configuration overrides

The runner defaults to Chrome. To keep machine-specific settings out of Git, copy:

```text
src/test/resources/pickleball_local.properties.example
```

to:

```text
pickleball_local.properties
```

Then adjust its values. The generated `.gitignore` excludes that local file.

## Notes

- The Maven dependency is intentionally in `test` scope.
- The runner class name ends in `Tests`, so Maven Surefire discovers it without extra includes.
- No custom Cucumber step definitions are needed. The runner adds one small `Radio Button` element category at startup, following Pickleball's documented custom-element mechanism, and the feature otherwise uses framework and dynamic steps.
- Port `8765` must be free while the tests run.
