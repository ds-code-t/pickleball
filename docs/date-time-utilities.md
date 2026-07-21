# Date and time utilities

Pickleball can resolve date/time instances, elapsed durations, and weekly time ranges inside dynamic-value expressions. This page documents the syntax implemented by `tools.dscode.coredefinitions.DateTimeUtilitySteps` and the public behavior of `tools.dscode.common.util.datetime` on the `master` branch.

The feature-file DSL is intentionally smaller than the Java API. In particular, the business-calendar classes expose Java methods such as `nextOpen(...)`, `lastOpen(...)`, and `addOpenDuration(...)`, but those method names are **not** natural-language operators in a `$DateTime` expression.

## Dynamic-value entry points

Use the expressions inside Pickleball's `<$...>` dynamic-value delimiters.

| Entry point | Implemented form | Result |
|---|---|---|
| Date/time | `<$DateTime:<date-time-spec>>` | `TemporalValue` of kind `DATE_TIME` |
| Named-calendar date/time | `<$DateTime:Calendar:<name> <date-time-spec>>` | `DATE_TIME` using the selected calendar |
| Current-time shortcut | `<$now ...>` | Equivalent to `DateTime:now ...` |
| Current-date shortcut | `<$today ...>` | Equivalent to `DateTime:today ...` |
| Next-date shortcut | `<$tomorrow ...>` | Equivalent to `DateTime:tomorrow ...` |
| Previous-date shortcut | `<$yesterday ...>` | Equivalent to `DateTime:yesterday ...` |
| Duration | `<$Duration:<duration-spec>>` | `TemporalValue` of kind `DURATION` |
| Time range | `<$TimeRange:<range-spec>>` | `TemporalValue` of kind `TIME_RANGE` |

The prefixes and shortcut names are case-insensitive. Calendar names are registry keys and must match the configured key.

Named-calendar selection is supported only through the full `DateTime:Calendar:<name> ...` form. A top-level `Calendar:` dynamic-value entry point is not defined.

Examples:

```gherkin
<$DateTime:now format: uuuu-MM-dd HH:mm:ss>
<$now format: uuuu-MM-dd>
<$today format: EEEE, MMMM d, uuuu>
<$tomorrow + 2 hours format: uuuu-MM-dd HH:mm>
<$yesterday format: uuuu-MM-dd>
<$DateTime:Calendar:OpsUS now format: uuuu-MM-dd HH:mm VV>
<$Duration:2 hours 30 minutes>
<$TimeRange:MON-FRI 09:00-17:00>
```

## Date/time expression grammar

The practical grammar is:

```text
DateTime:[Calendar:<name> ]<base>[ <signed-deltas>][ to <zone> TimeZone][ format: <output-format>]

(now|today|tomorrow|yesterday)
    [ <signed-deltas>][ to <zone> TimeZone][ format: <output-format>]
```

The recommended clause order is:

1. base value;
2. signed arithmetic;
3. output zone;
4. output format.

`format:` consumes the rest of the expression, so place it last.

### Supported base values

The evaluator recognizes these keywords exactly, ignoring case:

```text
now
today
tomorrow
yesterday
```

Any other base is passed to the selected `BusinessCalendar` for parsing.

The built-in parsers accept Java's standard ISO forms:

- ISO zoned date/time, such as `2026-07-20T19:45:00-04:00[America/New_York]`;
- ISO offset date/time, such as `2026-07-20T19:45:00-04:00`;
- ISO instant, such as `2026-07-20T23:45:00Z`;
- ISO local date/time, such as `2026-07-20T23:45:00`;
- ISO local date, such as `2026-07-20`;
- ISO local time, such as `23:45:00`.

A calendar may add strict custom input patterns with `DateTimeFormats` in `CALENDARS.yaml`.

### Important unzoned-input rule

An input with no offset or zone is interpreted as UTC/GMT and then converted to the selected calendar's zone. It is **not** initially interpreted in the calendar's local zone.

For example, with an `America/Phoenix` calendar, the unzoned input `2026-07-20T23:45:00` represents `23:45 UTC`, not `23:45 Phoenix time`.

For custom strict input patterns, prefer `uuuu` for the proleptic year. `yyyy` is year-of-era and may require an era to resolve under `ResolverStyle.STRICT`.

## Output formatting

Append one trailing `format:` clause:

```gherkin
<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: uuuu-MM-dd HH:mm:ss XXX>
```

Any ordinary Java `DateTimeFormatter` pattern is accepted. Literal text inside a pattern uses single quotes:

```gherkin
<$DateTime:now format: EEEE, MMMM d, uuuu 'at' h:mm a>
```

Common pattern letters:

| Pattern | Meaning |
|---|---|
| `u` / `uuuu` | proleptic year; recommended for strict parsing |
| `y` / `yyyy` | year of era |
| `M` / `MM` / `MMM` / `MMMM` | month |
| `d` / `dd` | day of month |
| `H` / `HH` | 24-hour clock |
| `h` / `hh` | 12-hour clock |
| `m` / `mm` | minute |
| `s` / `ss` | second |
| `S` | fractional second |
| `a` | AM/PM marker |
| `X`, `XX`, `XXX` | numeric offset |
| `VV` | zone ID |
| `z` | display-zone name |

The implementation also recognizes these output aliases. Matching is case-insensitive, and hyphens are normalized to underscores.

| Alias family | Output |
|---|---|
| `iso`, `iso_instant`, `iso-instant`, `instant` | ISO instant |
| `iso_zoned`, `iso-zoned`, `iso_zoned_date_time`, `iso-zoned-date-time` | ISO zoned date/time |
| `iso_offset`, `iso-offset`, `iso_offset_date_time`, `iso-offset-date-time` | ISO offset date/time |
| `epoch_millis`, `epoch-millis`, `epochmillis`, `millis` | epoch milliseconds |
| `epoch_seconds`, `epoch-seconds`, `epochseconds`, `seconds` | epoch seconds |
| `epoch_nanos`, `epoch-nanos`, `epochnanos`, `nanos` | epoch nanoseconds |

## Output-zone conversion

The implemented syntax is:

```text
to <zone-id> TimeZone
to <zone-id> time zone
```

Examples:

```gherkin
<$DateTime:2026-07-20T23:45:00Z to America/Phoenix TimeZone format: uuuu-MM-dd HH:mm XXX VV>
<$DateTime:2026-12-15T14:00:00Z to America/Los_Angeles time zone format: uuuu-MM-dd h:mm a VV>
<$now to Europe/London TimeZone format: uuuu-MM-dd HH:mm VV>
```

IANA region IDs are preferred. The parser first calls `ZoneId.of(...)` and then tries Java's `ZoneId.SHORT_IDS` map.

Changing the output zone preserves the instant. `asZone(...)` and the DSL's `to ... TimeZone` clause are presentation settings; they do not replace the stored instant.

## Date/time arithmetic

A date/time delta starts with `+` or `-`. There must be whitespace before the sign, and the sign must be followed by a numeric amount.

```gherkin
<$DateTime:now + 2 days format: uuuu-MM-dd HH:mm>
<$DateTime:2026-07-20T23:45:00Z + 1 hour 30 minutes to UTC TimeZone format: iso>
<$DateTime:2026-07-20T23:45:00Z - 45 minutes + 10 seconds to UTC TimeZone format: iso>
```

Rules:

- the first sign is required by the outer date/time evaluator;
- a later token with no sign inherits the most recent explicit sign;
- tokens are applied sequentially, so order can matter;
- whole years and months are allowed;
- fractional years and months are rejected;
- whole weeks and days use calendar-based `ZonedDateTime` arithmetic;
- decimal weeks, days, hours, minutes, seconds, milliseconds, microseconds, and nanoseconds use exact elapsed-duration arithmetic;
- a value requiring sub-nanosecond precision is rejected;
- unlike standalone durations, date/time delta text does not remove commas or the word `and`.

### Unit aliases

| Unit | Accepted aliases |
|---|---|
| years | `y`, `yr`, `yrs`, `year`, `years` |
| months | `mo`, `mon`, `mons`, `month`, `months` |
| weeks | `w`, `wk`, `wks`, `week`, `weeks` |
| days | `d`, `day`, `days` |
| hours | `h`, `hr`, `hrs`, `hour`, `hours` |
| minutes | `m`, `min`, `mins`, `minute`, `minutes` |
| seconds | `s`, `sec`, `secs`, `second`, `seconds` |
| milliseconds | `ms`, `milli`, `millis`, `millisecond`, `milliseconds` |
| microseconds | `us`, `µs`, `μs`, `micro`, `micros`, `microsecond`, `microseconds` |
| nanoseconds | `ns`, `nano`, `nanos`, `nanosecond`, `nanoseconds` |

## Inline syntax that is not implemented

The following forms are **not** recognized by `BusinessTime.evaluate(...)`:

```text
input format: ...
zone: ...
to zone: ...
plus: ...
minus: ...
next business day
opening time
closing time
next opening time
next closing time
```

Use configured `DateTimeFormats` for non-ISO input, `to <zone> TimeZone` for output conversion, and signed arithmetic such as `+ 2 days` or `- 30 minutes`.

The business-calendar operations listed above exist as Java methods, not as natural-language `$DateTime` operators.

## Durations

### Input syntax

`Duration:` supports either Java/ISO-8601 duration text or friendly signed unit expressions.

```gherkin
<$Duration:PT2H30M>
<$Duration:p2dt3h>
<$Duration:2 days and 3 hours>
<$Duration:2 days, 3 hours>
<$Duration:+ 2 hours + 30 minutes>
<$Duration:1.5 hours>
<$Duration:.5 minutes>
<$Duration:2 hours - 30 minutes 15 seconds>
```

Duration-specific normalization:

- an optional leading `Duration:` prefix is stripped;
- ISO duration input is case-insensitive;
- the word `and` is removed;
- commas become spaces;
- the first sign is optional and defaults to `+`;
- an unsigned token inherits the previous explicit sign;
- decimal exact units are supported;
- years and months are rejected because `java.time.Duration` represents exact elapsed time.

Duration expressions use the same week-through-nanosecond aliases listed above.

### Duration output formats

Append a trailing `format:` clause:

```gherkin
<$Duration:90 minutes format: hours>
<$Duration:90 minutes format: HH:mm:ss>
<$Duration:1 day 2 hours 3 minutes format: human>
```

Supported names are case-insensitive; hyphens are normalized to underscores.

| Output | Accepted names |
|---|---|
| ISO-8601 | `iso`, `iso_8601`, `iso-8601`, `iso8601` |
| nanoseconds | `nanos`, `nanoseconds`, `ns` |
| microseconds | `micros`, `microseconds`, `us` |
| milliseconds | `millis`, `milliseconds`, `ms` |
| decimal seconds | `seconds`, `second`, `secs`, `sec`, `s` |
| decimal minutes | `minutes`, `minute`, `mins`, `min`, `m` |
| decimal hours | `hours`, `hour`, `hrs`, `hr`, `h` |
| decimal days | `days`, `day`, `d` |
| clock hours/minutes | `HH:mm`, `H:mm` |
| clock hours/minutes/seconds | `HH:mm:ss`, `H:mm:ss` |
| readable parts | `human` |

Clock formatting uses the total hours, not a 24-hour wrap. `human` emits nonzero day/hour/minute/second/millisecond/microsecond/nanosecond parts.

## Weekly time ranges

`TimeRange:` parses one or more weekly segments and renders a canonical string.

```gherkin
<$TimeRange:09:00-17:00>
<$TimeRange:MON-FRI 09:00-17:00>
<$TimeRange:MON,WED,FRI 0900-1730>
<$TimeRange:MON 22:00-02:00>
<$TimeRange:MON-FRI 09:00-12:00, 13:00-17:00>
<$TimeRange:TUE 19:30:00.000000500-19:30:00.000000800>
```

### Day selectors

- abbreviations and full English names are accepted: `MON`/`MONDAY` through `SUN`/`SUNDAY`;
- comma-separated lists are accepted;
- inclusive ranges are accepted, including wraparound ranges;
- if a standalone `TimeRange:` omits days, the range applies to all seven days;
- an `Open` calendar rule requires a day selector.

### Time syntax

A boundary may be:

- `H:mm` or `HH:mm`;
- `H:mm:ss` or `HH:mm:ss`;
- a seconds value with 1–9 fractional digits;
- compact three- or four-digit time such as `900` or `0930`;
- exact `24:00` or `2400` for the end of a day.

A range whose end is earlier than its start is overnight. Equal start and end values are rejected as zero-length ranges. Calendar intervals are treated as half-open: the start is included and the end is excluded.

Canonical rendering:

- expands the selected weekdays in Monday-through-Sunday order;
- emits at least `HH:mm:ss`;
- trims trailing zeroes from fractional seconds;
- joins multiple segments with `, `.

`BusinessTimeRange.totalDuration()` and `totalNanos()` sum each segment's duration once for every selected day.

## Named calendars and `CALENDARS.yaml`

`CalendarRegistry` loads the object at `configs.CALENDARS` once and registers each top-level key as a calendar. The default key is `DefaultCalendar`.

```gherkin
<$DateTime:Calendar:OpsUS now format: uuuu-MM-dd HH:mm VV>
```

Always keep the `DateTime:` entry point before `Calendar:<name>`. The calendar selector is part of the date-time specification, not a separate dynamic-value family.

An unknown name currently causes `CalendarRegistry.get(name)` to return `null`; callers should use a configured key.

### Recognized calendar properties

```yaml
DefaultCalendar:
  TimeZone: UTC
  DateTimeFormats:
    - M/d/uuuu
    - uuuu-M-d H:m:s
  DefaultOutputPattern: uuuu-MM-dd HH:mm:ss VV
  DefaultOutputZone: UTC
  Open:
    - MON-FRI 09:00-17:00
  Closed:
    - 24-27 DEC
```

| Property | Behavior |
|---|---|
| `TimeZone` | Calendar zone; defaults to `UTC` |
| `DateTimeFormats` | Strict custom input patterns, tried before built-in ISO formatters |
| `DefaultOutputPattern` | Optional default rendering pattern |
| `DefaultOutputZone` | Optional default rendering zone; used only when a default pattern exists, otherwise the calendar zone is used |
| `Open` | Weekly opening rules |
| `Closed` | Closure rules that override opening rules |

### `Open` rule grammar

An open rule uses a weekday selector followed by one or more time ranges:

```yaml
Open:
  - MON-FRI 09:00-17:00
  - SAT 09:00-12:00, 13:00-16:00
  - MON 22:00-02:00
```

### `Closed` rule grammar

Closed rules may select years, months, days of month, weekdays, and optional time ranges. Supported examples include:

```yaml
Closed:
  - 24-27 DEC
  - 24,25,26,27 DEC
  - 2,5,24-27,31 DEC
  - WED,TUE DEC
  - 3,5 FEB 2026 1300-1500, 1100-1200
  - 15 JUN 2026 19:30:00.000000500-19:30:00.000000800
  - NOV-DEC MON-THU
```

A closure without a time range covers the full local day. A time-only clause after a comma-and-space extends the preceding date selector. `Closed` always overrides `Open`.

## Java API capabilities

The following functionality is available to Java callers. It is not automatically a feature-file operator.

### `BusinessCalendar`

- construction: `fromJson(String)` and `fromJson(JsonNode)`;
- metadata: `zone()`, `defaultOutputPattern()`, `defaultOutputZone()`;
- parsing/evaluation: `of(...)`, `evalToBusinessTime(...)`, `eval(...)`, `parseTimeRange(...)`;
- relative values: `now()`, `today()`, `tomorrow()`, `yesterday()`;
- status: `status(...)`, `isOpen(...)`, `isClosed(...)`;
- boundaries: `nextOpen(...)`, `lastOpen(...)`;
- open-time arithmetic: `addOpenDuration(...)`, `subtractOpenDuration(...)`;
- elapsed time: `durationBetween(start, end)`;
- normalization: `toCalendarZoneAssumeGmtIfMissing(...)`.

Searches for the next or previous open instant are bounded to approximately 370 days and may return `null`.

### `BusinessTime`

- stored value: `value()` and `calendar()`;
- evaluation: `of(...)`, `evaluate(...)`, `eval(...)`;
- display state: `asPattern(...)`, `asZone(...)`, `resetOutput()`, `render()`, `format(...)`;
- status and boundaries: `status()`, `isOpen()`, `isClosed()`, `nextOpen()`, `lastOpen()`;
- arithmetic: `add(...)`, `addOpen(...)`;
- elapsed time: `durationBetween(...)`.

`asPattern(...)` and `asZone(...)` alter presentation only. `addOpen(...)` rejects years and months because it converts the delta to an exact `Duration`.

### `TemporalValue`

Kinds are `DATE_TIME`, `DURATION`, and `TIME_RANGE`.

Factories:

- `dateTime(...)` from a spec, `BusinessTime`, `ZonedDateTime`, `OffsetDateTime`, or `Instant`;
- `duration(...)` from a spec or `Duration`;
- `timeRange(...)` from a spec or `BusinessTimeRange`;
- typed object wrappers `ofDateTimeObject(...)`, `ofDurationObject(...)`, and `ofTimeRangeObject(...)`.

Normalization and comparison helpers:

- `toIso()`;
- `toUnits()` and `toUnits(Unit)`;
- `toNanos()`;
- `toInstant()` for date/time values;
- `render()` / `toString()` for presentation.

Units are `YEARS`, `MONTHS`, `WEEKS`, `DAYS`, `HOURS`, `MINUTES`, `SECONDS`, `MILLIS`, `MICROS`, and `NANOS`. Duration and time-range conversion to years or months is rejected.

### `DurationFormattingUtils`

- `evaluate(...)` / `eval(...)` returns a duration `TemporalValue`;
- `parseSpec(...)` separates the value and optional format;
- `parseDuration(...)` returns `java.time.Duration`;
- `format(...)` renders using the duration formats listed above.

### `DateTimeDeltaParsingUtils`

- `parse(...)` returns sequential date/time delta tokens;
- `applyTo(...)` applies them to a `ZonedDateTime`;
- `parseDuration(...)` and `toDuration(...)` produce exact `Duration` values.

### `TemporalConversionUtils`

Date/time helpers:

- `dateTimeToInstant(...)`;
- `dateTimeToZonedDateTime(...)`;
- `dateTimeToIsoInstant(...)`;
- `dateTimeToIsoZonedDateTime(...)`;
- `dateTimeToEpochNanos(...)`;
- `dateTimeToEpochMillis(...)`;
- `dateTimeToUnits(...)`.

Duration helpers:

- `parseDuration(...)`;
- `durationToIso(...)`;
- `durationToNanos(...)`;
- `durationToUnits(...)`.

Typed and automatic helpers:

- `toIso(TemporalKind, ...)`;
- `toUnits(TemporalKind, ...)`;
- `toIsoAuto(...)`;
- `toUnitsAuto(...)`.

For exact equality and ordering, prefer instant/epoch nanoseconds for date-times and total nanoseconds for durations. Date/time conversion to years or months means completed UTC calendar units since the Unix epoch and is intentionally coarse.

### `BusinessTimeRange`

- parsing and canonical rendering;
- `segments()`;
- `totalDuration()` and `totalNanos()`;
- segment accessors for days, boundaries, overnight status, and duration.

### `CalendarRegistry`

- `getCalendar()` returns the thread-local calendar when set, otherwise `DefaultCalendar`;
- `get(name)` resolves a registered calendar;
- `registerJson(...)` registers all top-level calendar entries.

### `DateTimeParsingUtils`

This package-private helper performs strict, full-consumption parsing and selects the most specific supported Java temporal type. Its public overload accepts text plus pattern strings, but it is an implementation utility rather than a dynamic-value entry point.

## Consumer feature coverage

The executable feature is:

```text
maven-consumer-project/src/test/resources/features/date-time-utilities.feature
```

It uses only syntax accepted by the current parser. It deliberately does not use `input format:`, `zone:`, `to zone:`, `plus:`, `minus:`, or natural-language business-boundary phrases.

## Troubleshooting

### `Unparseable date/time` ending in `input`

The parser does not implement inline `input format:`. The first occurrence of `format:` is treated as the output clause, leaving the word `input` attached to the base value.

### A local value appears shifted

Unzoned values are assumed UTC/GMT before conversion to the calendar zone. Supply an ISO offset or zoned input when the source represents local wall-clock time.

### A zone clause is ignored

Use `to <zone> TimeZone` or `to <zone> time zone`. `zone:` and `to zone:` are not implemented.

### Arithmetic text is parsed as part of the base

Begin arithmetic with a signed numeric token, such as ` + 2 days`. The outer evaluator does not recognize `plus:` or `minus:`.

### A named calendar produces a null-pointer failure

Confirm that the exact calendar key is present under `configs.CALENDARS`. `CalendarRegistry.get(...)` currently returns `null` for an unknown key instead of throwing a descriptive error.

### A custom date pattern fails under strict parsing

Use `uuuu` for a strict proleptic year, ensure the pattern consumes the entire input, and include an offset or zone token when the input contains one.

---

[Previous: Mapping and Templating](mapping-and-templating.md) · [Documentation home](README.md) · [Next: Shared Configuration Files](config-files-and-resource-mapping.md)
