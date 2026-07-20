@all @regression @browser @datetime @dynamic @local-page
Feature: Date and time utility expressions
  Date/time values can be formatted, parsed, converted between zones, compared
  with a margin, and evaluated against the configured business calendar.

  # Uses the pre-existing OpsUS calendar from configs/CALENDARS.yaml.

  Background:
    * navigate to: URL.dateTime
    * , ensure "Date & Time Playground" Text is displayed

  Scenario: Format current and relative dates
    Then , user enters "<$DateTime:now format: MM/dd/yyyy>" for the "Date" Textbox
    And , user enters "<$today format: yyyy-MM-dd>" for the "ISO Date" Textbox
    And , user enters "<$tomorrow format: MM/dd/yyyy h:mm a>" for the "Due Date" Textbox
    And , user enters "<$yesterday format: EEE, MMM d, yyyy>" for the "Previous Date" Textbox
    And , user saves "<$DateTime:now format: yyyy-MM-dd'T'HH:mm:ssXXX>" as "formatted now"
    And , user enters "<formatted now>" for the "Formatted Date Time" Textbox
    And , ensure "Formatted Date Time" Textbox value is "<formatted now>"

  Scenario: Save and compare a due date with an allowed margin
    Then , user saves "<$DateTime:tomorrow format: MM/dd/yyyy h:mm a>" as "due date"
    And , user enters "<due date>" for the "Due Date" Textbox
    And , the user verifies the "<due date>" is equal to the value of the "Due Date" Textbox within a margin of 2 hours.

  Scenario Outline: Parse input text and reformat the result
    Then , user saves "<$DateTime:<source> input format: <input-pattern> format: <output-pattern>>" as "reformatted date"
    And , user enters "<source>" for the "Source Date Time" Textbox
    And , user enters "<reformatted date>" for the "Reformatted Date Time" Textbox
    And , ensure "Reformatted Date Time" Textbox value is "<expected>"

    Examples:
      | source                | input-pattern             | output-pattern      | expected         |
      | 07/20/2026            | MM/dd/yyyy                | yyyy-MM-dd          | 2026-07-20       |
      | 20-Jul-2026 4:45 PM   | dd-MMM-yyyy h:mm a        | yyyy-MM-dd HH:mm    | 2026-07-20 16:45 |
      | 2026-07-20T23:45:00Z  | yyyy-MM-dd'T'HH:mm:ssX    | MM/dd/yyyy HH:mm    | 07/20/2026 23:45 |

  Scenario: Convert a Phoenix local time to UTC
    Then , user saves "<$DateTime:2026-07-20 16:45 input format: yyyy-MM-dd HH:mm zone: America/Phoenix to zone: UTC format: yyyy-MM-dd HH:mm XXX>" as "utc date time"
    And , user enters "2026-07-20 16:45 America/Phoenix" for the "Source Zone Date Time" Textbox
    And , user enters "<utc date time>" for the "Converted Zone Date Time" Textbox
    And , ensure "Converted Zone Date Time" Textbox value is "2026-07-20 23:45 Z"

  Scenario: Convert a winter New York local time to Los Angeles
    Then , user saves "<$DateTime:2026-12-15 09:00 input format: yyyy-MM-dd HH:mm zone: America/New_York to zone: America/Los_Angeles format: yyyy-MM-dd h:mm a VV>" as "los angeles date time"
    And , user enters "<los angeles date time>" for the "Converted Zone Date Time" Textbox
    And , ensure "Converted Zone Date Time" Textbox value is "2026-12-15 6:00 AM America/Los_Angeles"

  Scenario: Use duration and time range values
    Then , user saves "<$Duration:PT2H30M>" as "service duration"
    And , user enters "<service duration>" for the "Duration" Textbox
    And , ensure "Duration" Textbox value is "<service duration>"
    Then , user saves "<$TimeRange:09:00-17:00>" as "business range"
    And , user enters "<business range>" for the "Business Hours Range" Textbox
    And , ensure "Business Hours Range" Textbox value is "<business range>"

  @business-calendar
  Scenario: Evaluate a named calendar and its business boundaries
    Then , user saves "<$DateTime:Calendar:OpsUS now next business day format: yyyy-MM-dd>" as "next business date"
    And , user enters "<next business date>" for the "Business Date" Textbox
    And , ensure "Business Date" Textbox value is "<next business date>"

    Then , user saves "<$DateTime:Calendar:OpsUS today opening time format: yyyy-MM-dd h:mm a VV>" as "opening time"
    And , user enters "<opening time>" for the "Opening Time" Textbox
    And , ensure "Opening Time" Textbox value is "<opening time>"

    Then , user saves "<$DateTime:Calendar:OpsUS today closing time format: yyyy-MM-dd h:mm a VV>" as "closing time"
    And , user enters "<closing time>" for the "Closing Time" Textbox
    And , ensure "Closing Time" Textbox value is "<closing time>"

  @business-calendar
  Scenario: Find the next opening and closing instants
    Then , user saves "<$DateTime:Calendar:OpsUS now next opening time format: yyyy-MM-dd h:mm a VV>" as "next opening"
    And , user saves "<$DateTime:Calendar:OpsUS now next closing time format: yyyy-MM-dd h:mm a VV>" as "next closing"
    And , user enters "<next opening>" for the "Opening Time" Textbox
    And , user enters "<next closing>" for the "Closing Time" Textbox
    And , the user verifies the "<next opening>" is before the value of the "Closing Time" Textbox.
