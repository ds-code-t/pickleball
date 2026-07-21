@all @regression @datetime @dynamic
Feature: Implemented date and time utility expressions
  The scenarios exercise the dynamic-value syntax currently accepted by
  DateTimeUtilitySteps and tools.dscode.common.util.datetime.

  # The feature intentionally does not use unsupported clauses such as:
  # input format:, zone:, to zone:, plus:, minus:, next business day,
  # opening time, closing time, next opening time, or next closing time.

  @browser @local-page
  Scenario: Resolve every DateTimeUtilitySteps entry point
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed
    Then , user enters "<$DateTime:now format: uuuu-MM-dd HH:mm:ss>" for the "Formatted Date Time" Textbox
    And , user enters "<$now format: uuuu-MM-dd HH:mm:ss>" for the "Date" Textbox
    And , user enters "<$today format: uuuu-MM-dd>" for the "ISO Date" Textbox
    And , user enters "<$tomorrow format: uuuu-MM-dd HH:mm>" for the "Due Date" Textbox
    And , user enters "<$yesterday format: uuuu-MM-dd>" for the "Previous Date" Textbox
    And , user enters "<$Duration:PT2H30M>" for the "Duration" Textbox
    And , user enters "<$TimeRange:09:00-17:00>" for the "Business Hours Range" Textbox

  @browser @local-page
  Scenario: Entry-point prefixes and keywords are case-insensitive
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed
    Then , verify "<$dAtEtImE:2026-07-20T23:45:00Z to UTC TimeZone format: iso>" equals "2026-07-20T23:45:00Z"
    And , user enters "<$NoW format: uuuu-MM-dd HH:mm:ss>" for the "Date" Textbox
    And , user enters "<$ToDaY format: uuuu-MM-dd>" for the "ISO Date" Textbox
    And , user enters "<$ToMoRrOw format: uuuu-MM-dd>" for the "Due Date" Textbox
    And , user enters "<$YeStErDaY format: uuuu-MM-dd>" for the "Previous Date" Textbox
    And , verify "<$dUrAtIoN:90 minutes FoRmAt: IsO>" equals "PT1H30M"
    And , verify "<$tImErAnGe:MON 09:00-17:00>" equals "MON 09:00:00-17:00:00"

  Scenario: Parse every built-in ISO base form
    Then , verify "<$DateTime:2026-07-20T19:45:00-04:00[America/New_York] to UTC TimeZone format: iso>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T19:45:00-04:00 to UTC TimeZone format: iso>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00 to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ssXXX>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20 to UTC TimeZone format: uuuu-MM-dd HH:mm:ss XXX>" equals "2026-07-20 00:00:00 Z"
    And , verify "<$DateTime:23:45:00 to UTC TimeZone format: HH:mm:ss>" equals "23:45:00"

  @browser @local-page
  Scenario: Convert an instant with both implemented TimeZone spellings
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed
    Then , verify "<$DateTime:2026-07-20T23:45:00Z to America/Phoenix TimeZone format: uuuu-MM-dd HH:mm XXX VV>" equals "2026-07-20 16:45 -07:00 America/Phoenix"
    And , verify "<$DateTime:2026-12-15T14:00:00Z to America/Los_Angeles time zone format: uuuu-MM-dd h:mm a VV>" equals "2026-12-15 6:00 AM America/Los_Angeles"
    And , user enters "2026-07-20T23:45:00Z" for the "Source Zone Date Time" Textbox
    And , user enters "<$DateTime:2026-07-20T23:45:00Z to America/Phoenix TimeZone format: uuuu-MM-dd HH:mm XXX VV>" for the "Converted Zone Date Time" Textbox

  Scenario: Use every date-time output alias
    Then , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso_instant>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso-instant>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: instant>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso_zoned>" equals "2026-07-20T23:45:00Z[UTC]"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso-zoned>" equals "2026-07-20T23:45:00Z[UTC]"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso_zoned_date_time>" equals "2026-07-20T23:45:00Z[UTC]"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso-zoned-date-time>" equals "2026-07-20T23:45:00Z[UTC]"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso_offset>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso-offset>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso_offset_date_time>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: iso-offset-date-time>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epoch_millis>" equals "1784591100000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epoch-millis>" equals "1784591100000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epochmillis>" equals "1784591100000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: millis>" equals "1784591100000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epoch_seconds>" equals "1784591100"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epoch-seconds>" equals "1784591100"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epochseconds>" equals "1784591100"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: seconds>" equals "1784591100"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epoch_nanos>" equals "1784591100000000000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epoch-nanos>" equals "1784591100000000000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: epochnanos>" equals "1784591100000000000"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: nanos>" equals "1784591100000000000"

  Scenario: Use Java output patterns and quoted literals
    Then , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: uuuu-MM-dd>" equals "2026-07-20"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: EEEE, MMMM d, uuuu>" equals "Monday, July 20, 2026"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ssXXX>" equals "2026-07-20T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z to UTC TimeZone format: MMMM d, uuuu 'at' h:mm a>" equals "July 20, 2026 at 11:45 PM"

  Scenario: Apply signed exact date-time deltas sequentially
    Then , verify "<$DateTime:2026-07-20T23:45:00Z + 1 hour 30 minutes to UTC TimeZone format: iso>" equals "2026-07-21T01:15:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z - 45 minutes 15 seconds to UTC TimeZone format: iso>" equals "2026-07-20T22:59:45Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1.5 hours to UTC TimeZone format: iso>" equals "2026-07-21T01:15:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 500 millis + 250 micros + 125 nanos to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX>" equals "2026-07-20T23:45:00.500250125Z"

  Scenario: Accept every date-time delta unit alias
    Then , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 y format: uuuu-MM-dd>" as "delta y"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 yr format: uuuu-MM-dd>" as "delta yr"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 yrs format: uuuu-MM-dd>" as "delta yrs"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 year format: uuuu-MM-dd>" as "delta year"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 years format: uuuu-MM-dd>" as "delta years"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 mo format: uuuu-MM-dd>" as "delta mo"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 mon format: uuuu-MM-dd>" as "delta mon"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 mons format: uuuu-MM-dd>" as "delta mons"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 month format: uuuu-MM-dd>" as "delta month"
    And , user saves "<$DateTime:2026-01-15T12:00:00Z + 1 months format: uuuu-MM-dd>" as "delta months"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 w to UTC TimeZone format: iso>" equals "2026-07-27T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 wk to UTC TimeZone format: iso>" equals "2026-07-27T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 wks to UTC TimeZone format: iso>" equals "2026-07-27T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 week to UTC TimeZone format: iso>" equals "2026-07-27T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 weeks to UTC TimeZone format: iso>" equals "2026-07-27T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 d to UTC TimeZone format: iso>" equals "2026-07-21T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 day to UTC TimeZone format: iso>" equals "2026-07-21T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 days to UTC TimeZone format: iso>" equals "2026-07-21T23:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 h to UTC TimeZone format: iso>" equals "2026-07-21T00:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 hr to UTC TimeZone format: iso>" equals "2026-07-21T00:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 hrs to UTC TimeZone format: iso>" equals "2026-07-21T00:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 hour to UTC TimeZone format: iso>" equals "2026-07-21T00:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 hours to UTC TimeZone format: iso>" equals "2026-07-21T00:45:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 m to UTC TimeZone format: iso>" equals "2026-07-20T23:46:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 min to UTC TimeZone format: iso>" equals "2026-07-20T23:46:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 mins to UTC TimeZone format: iso>" equals "2026-07-20T23:46:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 minute to UTC TimeZone format: iso>" equals "2026-07-20T23:46:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 minutes to UTC TimeZone format: iso>" equals "2026-07-20T23:46:00Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 s to UTC TimeZone format: iso>" equals "2026-07-20T23:45:01Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 sec to UTC TimeZone format: iso>" equals "2026-07-20T23:45:01Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 secs to UTC TimeZone format: iso>" equals "2026-07-20T23:45:01Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 second to UTC TimeZone format: iso>" equals "2026-07-20T23:45:01Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 seconds to UTC TimeZone format: iso>" equals "2026-07-20T23:45:01Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 ms to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSXXX>" equals "2026-07-20T23:45:00.001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 milli to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSXXX>" equals "2026-07-20T23:45:00.001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 millis to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSXXX>" equals "2026-07-20T23:45:00.001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 millisecond to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSXXX>" equals "2026-07-20T23:45:00.001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 milliseconds to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSXXX>" equals "2026-07-20T23:45:00.001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 us to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 µs to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 μs to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 micro to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 micros to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 microsecond to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 microseconds to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSXXX>" equals "2026-07-20T23:45:00.000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 ns to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX>" equals "2026-07-20T23:45:00.000000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 nano to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX>" equals "2026-07-20T23:45:00.000000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 nanos to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX>" equals "2026-07-20T23:45:00.000000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 nanosecond to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX>" equals "2026-07-20T23:45:00.000000001Z"
    And , verify "<$DateTime:2026-07-20T23:45:00Z + 1 nanoseconds to UTC TimeZone format: uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX>" equals "2026-07-20T23:45:00.000000001Z"

  @browser @local-page
  Scenario: Parse ISO and friendly duration syntax
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed
    Then , verify "<$Duration:PT2H30M format: iso>" equals "PT2H30M"
    And , verify "<$Duration:p2dt3h format: iso>" equals "PT51H"
    And , verify "<$Duration:2 days and 3 hours format: iso>" equals "PT51H"
    And , verify "<$Duration:2 days, 3 hours format: iso>" equals "PT51H"
    And , verify "<$Duration:+ 2 hours + 30 minutes format: iso>" equals "PT2H30M"
    And , verify "<$Duration:1.5 hours format: iso>" equals "PT1H30M"
    And , verify "<$Duration:.5 minutes format: iso>" equals "PT30S"
    And , verify "<$Duration:2 hours - 30 minutes 15 seconds format: iso>" equals "PT1H29M45S"
    And , user enters "<$Duration:2 hours 30 minutes>" for the "Duration" Textbox

  Scenario Outline: Accept exact duration <unit> unit alias
    Then , verify "<$Duration:1 <unit> format: nanos>" equals "<nanos>"

    Examples:
      | unit         | nanos           |
      | w            | 604800000000000 |
      | wk           | 604800000000000 |
      | wks          | 604800000000000 |
      | week         | 604800000000000 |
      | weeks        | 604800000000000 |
      | d            | 86400000000000  |
      | day          | 86400000000000  |
      | days         | 86400000000000  |
      | h            | 3600000000000   |
      | hr           | 3600000000000   |
      | hrs          | 3600000000000   |
      | hour         | 3600000000000   |
      | hours        | 3600000000000   |
      | m            | 60000000000     |
      | min          | 60000000000     |
      | mins         | 60000000000     |
      | minute       | 60000000000     |
      | minutes      | 60000000000     |
      | s            | 1000000000      |
      | sec          | 1000000000      |
      | secs         | 1000000000      |
      | second       | 1000000000      |
      | seconds      | 1000000000      |
      | ms           | 1000000         |
      | milli        | 1000000         |
      | millis       | 1000000         |
      | millisecond  | 1000000         |
      | milliseconds | 1000000         |
      | us           | 1000            |
      | µs           | 1000            |
      | μs           | 1000            |
      | micro        | 1000            |
      | micros       | 1000            |
      | microsecond  | 1000            |
      | microseconds | 1000            |
      | ns           | 1               |
      | nano         | 1               |
      | nanos        | 1               |
      | nanosecond   | 1               |
      | nanoseconds  | 1               |

  Scenario: Render every duration output format family
    Then , verify "<$Duration:90 minutes format: iso>" equals "PT1H30M"
    And , verify "<$Duration:90 minutes format: iso_8601>" equals "PT1H30M"
    And , verify "<$Duration:90 minutes format: iso-8601>" equals "PT1H30M"
    And , verify "<$Duration:90 minutes format: iso8601>" equals "PT1H30M"
    And , verify "<$Duration:90 minutes format: nanos>" equals "5400000000000"
    And , verify "<$Duration:90 minutes format: nanoseconds>" equals "5400000000000"
    And , verify "<$Duration:90 minutes format: ns>" equals "5400000000000"
    And , verify "<$Duration:90 minutes format: micros>" equals "5400000000"
    And , verify "<$Duration:90 minutes format: microseconds>" equals "5400000000"
    And , verify "<$Duration:90 minutes format: us>" equals "5400000000"
    And , verify "<$Duration:90 minutes format: millis>" equals "5400000"
    And , verify "<$Duration:90 minutes format: milliseconds>" equals "5400000"
    And , verify "<$Duration:90 minutes format: ms>" equals "5400000"
    And , verify "<$Duration:90 minutes format: seconds>" equals "5400"
    And , verify "<$Duration:90 minutes format: second>" equals "5400"
    And , verify "<$Duration:90 minutes format: secs>" equals "5400"
    And , verify "<$Duration:90 minutes format: sec>" equals "5400"
    And , verify "<$Duration:90 minutes format: s>" equals "5400"
    And , verify "<$Duration:90 minutes format: minutes>" equals "90"
    And , verify "<$Duration:90 minutes format: minute>" equals "90"
    And , verify "<$Duration:90 minutes format: mins>" equals "90"
    And , verify "<$Duration:90 minutes format: min>" equals "90"
    And , verify "<$Duration:90 minutes format: m>" equals "90"
    And , verify "<$Duration:90 minutes format: hours>" equals "1.5"
    And , verify "<$Duration:90 minutes format: hour>" equals "1.5"
    And , verify "<$Duration:90 minutes format: hrs>" equals "1.5"
    And , verify "<$Duration:90 minutes format: hr>" equals "1.5"
    And , verify "<$Duration:90 minutes format: h>" equals "1.5"
    And , verify "<$Duration:36 hours format: days>" equals "1.5"
    And , verify "<$Duration:36 hours format: day>" equals "1.5"
    And , verify "<$Duration:36 hours format: d>" equals "1.5"
    And , verify "<$Duration:90 minutes format: HH:mm>" equals "01:30"
    And , verify "<$Duration:90 minutes format: H:mm>" equals "01:30"
    And , verify "<$Duration:90 minutes format: HH:mm:ss>" equals "01:30:00"
    And , verify "<$Duration:90 minutes format: H:mm:ss>" equals "01:30:00"
    And , verify "<$Duration:1 day 2 hours 3 minutes 4 seconds 5 milliseconds 6 microseconds 7 nanoseconds format: human>" equals "1 day 2 hours 3 minutes 4 seconds 5 milliseconds 6 microseconds 7 nanoseconds"

  @browser @local-page
  Scenario: Parse time ranges, day selectors, compact times, overnight ranges, and precision
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed
    Then , verify "<$TimeRange:09:00-17:00>" equals "MON,TUE,WED,THU,FRI,SAT,SUN 09:00:00-17:00:00"
    And , verify "<$TimeRange:MON-FRI 09:00-17:00>" equals "MON,TUE,WED,THU,FRI 09:00:00-17:00:00"
    And , verify "<$TimeRange:MONDAY-FRIDAY 09:00-17:00>" equals "MON,TUE,WED,THU,FRI 09:00:00-17:00:00"
    And , verify "<$TimeRange:MON,WED,FRI 0900-1730>" equals "MON,WED,FRI 09:00:00-17:30:00"
    And , verify "<$TimeRange:FRI-MON 09:00-17:00>" equals "MON,FRI,SAT,SUN 09:00:00-17:00:00"
    And , verify "<$TimeRange:MON 22:00-02:00>" equals "MON 22:00:00-02:00:00"
    And , verify "<$TimeRange:MON 00:00-24:00>" equals "MON 00:00:00-24:00"
    And , verify "<$TimeRange:MON-FRI 09:00-12:00, 13:00-17:00>" equals "MON,TUE,WED,THU,FRI 09:00:00-12:00:00, MON,TUE,WED,THU,FRI 13:00:00-17:00:00"
    And , verify "<$TimeRange:TUE 19:30:00.000000500-19:30:00.000000800>" equals "TUE 19:30:00.0000005-19:30:00.0000008"
    And , user enters "<$TimeRange:MON-FRI 09:00-12:00, 13:00-17:00>" for the "Business Hours Range" Textbox

  @business-calendar
  Scenario: Select separate named calendars with full date-time syntax
    Then , verify "<$DateTime:Calendar:DefaultCalendar 2026-07-20T23:45:00Z to UTC TimeZone format: uuuu-MM-dd HH:mm:ss VV>" equals "2026-07-20 23:45:00 UTC"
    And , verify "<$DateTime:Calendar:OpsUS 2026-07-20T23:45:00Z format: uuuu-MM-dd HH:mm:ss VV>" equals "2026-07-20 19:45:00 America/New_York"

  @business-calendar @browser @local-page
  Scenario: Use the OpsUS calendar independently of the default calendar
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed
    Then , user saves "<$DateTime:Calendar:OpsUS 2026-12-15T14:00:00Z format: uuuu-MM-dd h:mm a VV>" as "ops calendar date"
    And , verify "<ops calendar date>" equals "2026-12-15 9:00 AM America/New_York"
    And , user enters "<ops calendar date>" for the "Reformatted Date Time" Textbox
