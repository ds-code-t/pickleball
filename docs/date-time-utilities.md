# Date and time utilities

Pickleball date/time expressions let a feature compute dates and times when the scenario runs. They are resolved through the dynamic-value mapper before the enclosing browser or assertion step executes.

The entry points are implemented by `tools.dscode.coredefinitions.DateTimeUtilitySteps` and the classes in `tools.dscode.common.util.datetime`. Date/time operations are evaluated by the selected `BusinessCalendar`, so configured time zones, work days, holidays, business hours, opening hours, and closing hours can participate in the calculation.

## Executable example

See the Maven consumer project’s [date-time-utilities.feature](../maven-consumer-project/src/test/resources/features/date-time-utilities.feature) for runnable examples against the local [date/time test page](../maven-consumer-project/src/test/resources/site/datetime.html).

## Expression entry points

Use expressions inside Pickleball's dynamic-value delimiters:

```gherkin
<$DateTime:now format: MM/dd/yyyy>
<$now format: yyyy-MM-dd>
<$today format: EEEE, MMMM d, yyyy>
<$tomorrow format: MM/dd/yyyy h:mm a>
<$yesterday format: yyyy-MM-dd>
<$Duration:PT2H30M>
<$TimeRange:09:00-17:00>
```

The supported families are:

| Family | Purpose |
|---|---|
| `DateTime:` | Parse, calculate, convert, and format a date/time value. |
| `now`, `today`, `tomorrow`, `yesterday` | Short forms for the most common starting values. |
| `Duration:` | Create a duration value for arithmetic or comparison. |
| `TimeRange:` | Create a time range, such as an opening-hours interval. |
| `Calendar:<name>` | Select a named calendar from `CALENDARS.yaml` before evaluating the rest of the expression. |

`DateTimeUtilitySteps` accepts both a full date/time form and the shortcut forms. The remaining text is delegated to the date/time utility package, so operators can be combined in one expression.

## Current date and time

```gherkin
Then , user enters "<$DateTime:now format: MM/dd/yyyy>" for the "Date" Textbox
Then , user enters "<$today format: yyyy-MM-dd>" for the "ISO Date" Textbox
Then , user enters "<$tomorrow format: MM/dd/yyyy h:mm a>" for the "Due Date" Textbox
Then , user enters "<$yesterday format: EEE, MMM d>" for the "Previous Date" Textbox
```

Use `today` when the time of day should be the calendar's start-of-day value. Use `now` when the current time matters.

## Output formatting

The `format:` operator uses Java date/time pattern letters. Common patterns include:

| Pattern | Example shape |
|---|---|
| `yyyy-MM-dd` | `2026-07-20` |
| `MM/dd/yyyy` | `07/20/2026` |
| `MMM d, yyyy` | `Jul 20, 2026` |
| `EEEE, MMMM d, yyyy` | `Monday, July 20, 2026` |
| `HH:mm` | `16:05` |
| `h:mm a` | `4:05 PM` |
| `yyyy-MM-dd'T'HH:mm:ssXXX` | `2026-07-20T16:05:00-07:00` |
| `VV` | `America/Phoenix` |
| `z` | A short display-zone name where available. |

Examples:

```gherkin
<$DateTime:now format: yyyy-MM-dd'T'HH:mm:ssXXX>
<$DateTime:tomorrow format: EEEE, MMMM d, yyyy 'at' h:mm a>
<$DateTime:now format: yyyyMMdd-HHmmss>
```

Quote literal text inside a pattern with single quotes. Avoid `YYYY` for ordinary calendar years; `YYYY` is the week-based year. Prefer `yyyy`.

## Input parsing and reformatting

A parsing expression supplies the source text and its source pattern, then applies a new output pattern. The feature suite included with this change demonstrates the branch's intended syntax:

```gherkin
<$DateTime:07/20/2026 input format: MM/dd/yyyy format: yyyy-MM-dd>
<$DateTime:20-Jul-2026 4:45 PM input format: dd-MMM-yyyy h:mm a format: yyyy-MM-dd HH:mm>
<$DateTime:2026-07-20T23:45:00Z input format: yyyy-MM-dd'T'HH:mm:ssX format: MM/dd/yyyy h:mm a>
```

Parsing rules:

1. The input pattern must describe the complete input value.
2. Pattern letters are case-sensitive: `MM` is month; `mm` is minute.
3. Use `HH` for 24-hour input and `h`/`hh` with `a` for 12-hour input.
4. Include an offset or zone token when it is present in the input.
5. Apply `format:` last when the result must be converted to a string for a browser field.

When an input carries no zone, the selected business calendar's zone is used.

## Date/time arithmetic

Date/time expressions can be moved by calendar units or duration values. Keep the unit explicit so a feature remains readable:

```gherkin
<$DateTime:now plus: 90 minutes format: MM/dd/yyyy h:mm a>
<$DateTime:today plus: 14 days format: yyyy-MM-dd>
<$DateTime:tomorrow minus: 30 minutes format: HH:mm>
<$DateTime:now plus: <$Duration:PT2H> format: yyyy-MM-dd HH:mm>
```

Use calendar-aware operations for business days and opening-hour boundaries rather than treating every day as 24 interchangeable hours.

## Time zones and conversion

A calendar supplies a default time zone. An expression may also state the source zone and target zone explicitly:

```gherkin
<$DateTime:2026-07-20 16:45 input format: yyyy-MM-dd HH:mm zone: America/Phoenix to zone: UTC format: yyyy-MM-dd HH:mm XXX>
<$DateTime:2026-12-15 09:00 input format: yyyy-MM-dd HH:mm zone: America/New_York to zone: America/Los_Angeles format: yyyy-MM-dd h:mm a VV>
<$DateTime:now to zone: Europe/London format: yyyy-MM-dd HH:mm VV>
```

Use IANA zone IDs such as `America/Phoenix`, `America/New_York`, `Europe/London`, and `UTC`. Region IDs are preferred over fixed abbreviations because they correctly model daylight-saving rules.

A zone conversion preserves the instant and changes its local representation. Merely attaching a different zone to unzoned text changes how that local text is interpreted, so source-zone and target-zone operations should not be confused.

## Durations and time ranges

`Duration:` values are useful for offsets and assertion margins:

```gherkin
<$Duration:PT15M>
<$Duration:PT2H>
<$Duration:P3D>
```

The examples above use ISO-8601 durations: `P` begins the value, `T` begins its time portion, `D` means days, `H` hours, and `M` minutes.

`TimeRange:` represents a start and end time:

```gherkin
<$TimeRange:09:00-17:00>
<$TimeRange:08:30-12:00>
```

Time ranges are especially useful when a calendar has split opening hours or when a scenario needs to display the configured interval.

## Selecting a configured calendar

A named calendar is selected with `Calendar:<name>`. The name must match a calendar key loaded from `maven-consumer-project/src/test/resources/configs/CALENDARS.yaml`.

Full form:

```gherkin
<$DateTime:Calendar:OpsUS now format: yyyy-MM-dd HH:mm VV>
```

Shortcut form:

```gherkin
<$Calendar:OpsUS today format: yyyy-MM-dd>
<$Calendar:OpsUS tomorrow opening time format: yyyy-MM-dd h:mm a VV>
```

Use a named calendar whenever a scenario depends on a particular region, holiday set, work week, or schedule. If no name is supplied, the default calendar is used.

## `CALENDARS.yaml`

The consumer project already loads:

```text
maven-consumer-project/src/test/resources/configs/CALENDARS.yaml
```

The existing configuration supplies the calendar data consumed by the date/time package. A calendar normally describes these concepts:

- a stable calendar name;
- an IANA time-zone ID;
- working and non-working weekdays;
- holidays and exceptional closures;
- normal opening and closing hours;
- optional day-specific or split business-hour ranges.

The example consumer already defines the `OpsUS` calendar in [`CALENDARS.yaml`](../maven-consumer-project/src/test/resources/configs/CALENDARS.yaml). The feature examples use that existing calendar directly; no separate example calendar or generated configuration is required.

### Configuration guidance

- Keep calendar names stable because feature files refer to them.
- Store zone IDs as IANA region names rather than local abbreviations.
- Express holidays as unambiguous ISO dates.
- Define closed days explicitly when they differ from the normal work week.
- Treat opening and closing boundaries consistently. Document whether the closing instant is inclusive or exclusive.
- Add exceptional hours before relying on a "next opening" calculation near a holiday.

## Business days, opening, and closing hours

Business-calendar operations should answer questions such as:

- Is the supplied instant inside business hours?
- What is the next business day?
- What is the previous business day?
- What is the opening time for a given business date?
- What is the closing time for a given business date?
- What is the next opening instant after a closed period?
- What is the current or next available business interval?

Examples used by the consumer test suite:

```gherkin
<$DateTime:Calendar:OpsUS today opening time format: yyyy-MM-dd h:mm a VV>
<$DateTime:Calendar:OpsUS today closing time format: yyyy-MM-dd h:mm a VV>
<$DateTime:Calendar:OpsUS now next business day format: yyyy-MM-dd>
<$DateTime:Calendar:OpsUS now next opening time format: yyyy-MM-dd h:mm a VV>
<$DateTime:Calendar:OpsUS now next closing time format: yyyy-MM-dd h:mm a VV>
```

The consumer scenarios intentionally use the pre-existing `OpsUS` key from `CALENDARS.yaml`.

## Assertion margins

Date/time values captured at different moments should rarely be compared as exact strings. Use a margin when a scenario allows clock drift, UI delay, truncation, or asynchronous processing.

```gherkin
Then , user saves "<$DateTime:tomorrow format: MM/dd/yyyy h:mm a>" as "due date"
And , user enters "<due date>" for the "Due Date" Textbox
And , the user verifies the "<due date>" is equal to the value of the "Due Date" Textbox within a margin of 2 hours.
```

Smaller margins are useful for values generated during the same scenario:

```gherkin
Then , user saves "<$DateTime:now format: yyyy-MM-dd HH:mm:ss>" as "captured now"
And , the user verifies the "<captured now>" is equal to the value of the "Server Time" Textbox within a margin of 5 seconds.
```

Choose the narrowest margin that reflects the business rule. A two-hour margin should not be used merely to hide a missing time-zone conversion.

## Browser-test page

The example consumer page is:

```text
maven-consumer-project/src/test/resources/site/datetime.html
```

It exposes labeled textboxes for current dates, due dates, parsed/reformatted text, source and converted zones, duration/range values, business dates, and opening/closing times. The feature navigates through `URL.dateTime`.

## Troubleshooting

### A value is not resolved

Confirm that the expression begins with a registered family and is inside `<$...>`.

### Month and minute are swapped

Pattern letters are case-sensitive. Use `MM` for month and `mm` for minute.

### The displayed time is several hours off

Check both the source zone and the target zone. Do not compensate with a large assertion margin.

### A next-business-day result is surprising

Check the selected calendar, zone, weekend definition, holiday list, and exceptional closures in `CALENDARS.yaml`.

### Opening or closing time falls on the wrong date

Check whether the schedule crosses midnight and whether the operation is based on the local date in the calendar's zone.

## Implementation note

`DateTimeUtilitySteps` registers the dynamic-value prefixes and delegates date/time evaluation to `BusinessCalendar.eval(...)`; duration and time-range values are created through the temporal-value utilities. When adding a new operator, update the utility implementation, this page, and the consumer feature together so the documentation remains executable.

---

[Previous: Mapping and Templating](mapping-and-templating.md) · [Documentation home](README.md) · [Next: Shared Configuration Files](config-files-and-resource-mapping.md)
