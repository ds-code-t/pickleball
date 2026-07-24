# Date and Time Utilities

> **Working feature example:** [`date-time-utilities.feature`](../maven-consumer-project/src/test/resources/features/date-time-utilities.feature) exercises the supported date, time, duration, calendar, formatting, and comparison expressions.

Pickleball exposes date/time values, durations, and weekly time ranges through dynamic-value templates.

Use them inside `<$...>`:

```gherkin
* , save "<$DateTime:now>" as "currentDateTime"
* , save "<$today format: uuuu-MM-dd>" as "today"
* , save "<$Duration:2 hours 30 minutes>" as "timeout"
* , save "<$TimeRange:MON-FRI 09:00-17:00>" as "businessHours"
```

The prefixes are case-insensitive.

## Date/time entry points

| Form | Meaning |
|---|---|
| `<$DateTime:...>` | full date/time expression |
| `<$DateTime:Calendar:NAME ...>` | use a named configured calendar |
| `<$now ...>` | current date and time |
| `<$today ...>` | current date |
| `<$tomorrow ...>` | next date |
| `<$yesterday ...>` | previous date |

Examples:

```gherkin
<$DateTime:now format: uuuu-MM-dd HH:mm:ss>
<$today format: EEEE, MMMM d, uuuu>
<$tomorrow + 2 hours format: uuuu-MM-dd HH:mm>
<$DateTime:Calendar:OpsUS now format: uuuu-MM-dd HH:mm VV>
```

## Input values

The built-in evaluator accepts Java ISO forms such as:

```text
2026-07-20T19:45:00-04:00[America/New_York]
2026-07-20T19:45:00-04:00
2026-07-20T23:45:00Z
2026-07-20T23:45:00
2026-07-20
23:45:00
```

A calendar can add strict custom input patterns in `CALENDARS.yaml`.

An unzoned date/time is initially interpreted as UTC/GMT and then converted to the selected calendar's zone. It is not initially interpreted in the calendar's local zone.

## Arithmetic

```gherkin
<$DateTime:now + 2 days format: uuuu-MM-dd HH:mm>
<$DateTime:2026-07-20T23:45:00Z + 1 hour 30 minutes to UTC TimeZone format: iso>
<$DateTime:2026-07-20T23:45:00Z - 45 minutes + 10 seconds format: iso>
```

Supported units include years, months, weeks, days, hours, minutes, seconds, milliseconds, microseconds, and nanoseconds. Fractional years and months are rejected; fractional exact-duration units are supported where they can be represented precisely.

## Output zone

```gherkin
<$DateTime:2026-07-20T23:45:00Z to America/Phoenix TimeZone format: uuuu-MM-dd HH:mm XXX VV>
```

IANA region IDs are preferred. Zone conversion preserves the instant.

## Output format

A trailing `format:` clause consumes the rest of the expression, so place it last:

```gherkin
<$DateTime:now format: EEEE, MMMM d, uuuu 'at' h:mm a>
```

Normal Java `DateTimeFormatter` patterns and aliases such as `iso`, `iso_instant`, `iso_zoned`, `epoch_millis`, and `epoch_seconds` are supported.

## Durations

```gherkin
<$Duration:PT2H30M>
<$Duration:2 days and 3 hours>
<$Duration:+ 2 hours + 30 minutes>
<$Duration:1.5 hours>
<$Duration:90 minutes format: hours>
<$Duration:1 day 2 hours 3 minutes format: human>
```

Duration expressions accept ISO-8601 input or friendly signed unit expressions. Years and months are not supported in standalone durations because `java.time.Duration` represents exact elapsed time.

## Time ranges

```gherkin
<$TimeRange:MON-FRI 09:00-17:00>
```

Use configured calendars for project-specific business-day and time-zone behavior.

## Working examples

- [Date/time feature coverage](../maven-consumer-project/src/test/resources/features/date-time-utilities.feature)
- [Calendar configuration](../maven-consumer-project/src/test/resources/configs/CALENDARS.yaml)

The feature file is the executable reference for the feature-file syntax currently supported by the framework.

[Previous: Service-call Scenarios](service-call-scenarios.md) · [Documentation home](README.md) · [Next: Keyboard Expressions](key-parser-dsl.md)
