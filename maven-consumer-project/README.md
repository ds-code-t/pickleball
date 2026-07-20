# Pickleball Dynamic Steps Consumer

A regular Maven example implementation for the Pickleball browser-testing framework. It uses Pickleball as a test-scoped dependency and runs executable feature examples against a self-contained HTML5/JavaScript site.

## Requirements

- JDK 21
- Maven 3.9 or newer
- Chrome available to Selenium, or a different browser configured through Pickleball

## Run

```bash
mvn test
```

`PickleballTests` starts a loopback-only HTTP server on `127.0.0.1:8765` before the Cucumber run and stops it afterward. Features navigate to URLs defined in `src/test/resources/configs/URL.yaml`.

## Project layout

```text
pom.xml
src/test/java/com/example/pickleball/PickleballTests.java
src/test/java/com/example/pickleball/support/LocalTestSite.java
src/test/resources/configs/CALENDARS.yaml
src/test/resources/configs/URL.yaml
src/test/resources/features/
src/test/resources/site/
```

## Runnable feature examples

- [Catalog and custom context](src/test/resources/features/catalog-context.feature)
- [Component scenarios](src/test/resources/features/component-scenarios.feature)
- [Dialogs](src/test/resources/features/dialogs.feature)
- [Dynamic steps](src/test/resources/features/dynamic-steps.feature)
- [Form-oriented dynamic steps](src/test/resources/features/forms-dynamic-steps.feature)
- [Keyboard expressions](src/test/resources/features/keyboard.feature)
- [Mapping and resources](src/test/resources/features/mapping-and-resources.feature)
- [Navigation](src/test/resources/features/navigation.feature)
- [Nested steps and block conditionals](src/test/resources/features/nested-and-block-conditionals.feature)
- [Date and time utilities](src/test/resources/features/date-time-utilities.feature)

## What the scenarios cover

- Accessible selection of textboxes, textareas, checkboxes, radio buttons, dropdowns, text, buttons, dialogs, and custom element categories
- Entering values and observing JavaScript-driven DOM changes
- State, text, ordinal, navigation, keyboard, nested-flow, block-condition, and component-scenario behavior
- Runtime mapping from YAML and other resource files
- Multiple actions in comma-delimited dynamic steps
- Date/time formatting, input parsing, reformatting, time-zone conversion, durations, time ranges, assertion margins, business dates, and opening or closing hours

## Date/time consumer example

The [date-time-utilities.feature](src/test/resources/features/date-time-utilities.feature) example uses [datetime.html](src/test/resources/site/datetime.html), mapped as `URL.dateTime` in [URL.yaml](src/test/resources/configs/URL.yaml).

The project already defines calendars in:

```text
src/test/resources/configs/CALENDARS.yaml
```

The date/time feature uses the calendar that is already defined there under the `OpsUS` key. `CALENDARS.yaml` remains an ordinary checked-in consumer configuration file and is not generated or replaced by this bundle.

Run only the date/time examples with:

```bash
mvn test "-Dpkb_tags=@datetime"
```

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
- No custom Cucumber step definitions are needed.
- The runner adds one small `Radio Button` element category at startup, following Pickleball's documented custom-element mechanism, and the features otherwise use framework and dynamic steps.
- Port `8765` must be free while the tests run.
