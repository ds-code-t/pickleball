@all @regression @data-elements @data-element-native @data-element-native-tabular @newphases
Feature: Native Data Element Cucumber projections

  Scenario: Query and materialize every Cucumber table projection
    Given CLEAR SAVED VALUES

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Row as "firstRow"
    Then , verify "<firstRow.id>" equals "r1"
    And , verify "<firstRow.status[0]>" equals "ready"
    And , verify "<firstRow.status[1]>" equals "pending"
    And , verify "<firstRow.score>" equals "10"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save 2nd Data Row as "secondRow"
    Then , verify "<secondRow.id>" equals "r2"
    And , verify "<secondRow.status[0]>" equals "blocked"
    And , verify "<secondRow.status[1]>" equals "ready"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save last Data Row as "lastRow"
    Then , verify "<lastRow.id>" equals "r4"
    And , verify "<lastRow.score>" equals "40"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Rows as "allRows"
    Then , verify "<allRows[0].id>" equals "r1"
    And , verify "<allRows[1].id>" equals "r2"
    And , verify "<allRows[2].id>" equals "r3"
    And , verify "<allRows[3].id>" equals "r4"
    And , verify "<allRows[2].status[1]>" equals "complete"

    # Comparison attributes and return attributes are independent.
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save value of Data Entry with key equaling 'STATUS' as "firstStatus"
    Then , verify "<firstStatus>" equals "ready"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save value of Data Entries with key equaling "status" as "allStatusValues"
    Then , verify "<allStatusValues[0]>" equals "ready"
    And , verify "<allStatusValues[1]>" equals "pending"
    And , verify "<allStatusValues[6]>" equals "complete"
    And , verify "<allStatusValues[7]>" equals "archived"

    # Data Element return values participate in ordinary dynamic assertions.
    Then , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, verify value of Data Entry with key equaling "score" is greater than 9
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, verify value of Data Entry with key equaling "score" is less than or equal to 10
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, verify value of Data Entry with key equaling "status" starts with "rea"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, verify value of Data Entry with key equaling "id" matches "r[0-9]+"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Columns as "columns"
    Then , verify "<columns[0].r1>" equals "ready"
    And , verify "<columns[1].r1>" equals "pending"
    And , verify "<columns[2].r4>" equals "40"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Lists as "dataLists"
    Then , verify "<dataLists[0][0]>" equals "id"
    And , verify "<dataLists[0][1]>" equals "status"
    And , verify "<dataLists[1][0]>" equals "r1"
    And , verify "<dataLists[4][4]>" equals "row4Seen"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Column Lists as "columnLists"
    Then , verify "<columnLists[0][0]>" equals "id"
    And , verify "<columnLists[0][4]>" equals "r4"
    And , verify "<columnLists[1][0]>" equals "status"
    And , verify "<columnLists[1][1]>" equals "ready"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Cells as "cells"
    Then , verify "<cells[0]>" equals "id"
    And , verify "<cells[1]>" equals "status"
    And , verify "<cells[5]>" equals "r1"
    And , verify "<cells[24]>" equals "row4Seen"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Headers as "headers"
    Then , verify "<headers[0]>" equals "id"
    And , verify "<headers[1]>" equals "status"
    And , verify "<headers[4]>" equals "captureKey"
    And , verify "<headers[19]>" equals "captureKey"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Values as "values"
    Then , verify "<values[0]>" equals "r1"
    And , verify "<values[1]>" equals "ready"
    And , verify "<values[4]>" equals "row1Seen"
    And , verify "<values[19]>" equals "row4Seen"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Entry as "firstEntry"
    And , save "<firstEntry>" JSON String as "firstEntryJson"
    Then , verify "<firstEntryJson>" contains '"Data Header":"id"'
    And , verify "<firstEntryJson>" contains '"Data Value":"r1"'

  Scenario: Iterate Data Rows Data Cells Data Headers and optional selections
    Given CLEAR SAVED VALUES

    # Phase 3 iteration/cardinality plus Phase 5 phrase-context reads.
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, for every 2nd Data Row:
    : * , save "<id>" as "<captureKey>"
    Then , verify "<row2Seen>" equals "r2"
    And , verify "<row4Seen>" equals "r4"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, for every Data Cell:
    : * , save "<value>" as "lastCell"
    Then , verify "<lastCell>" equals "row4Seen"

    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, for every Data Header:
    : * , save "<value>" as "lastHeader"
    Then , verify "<lastHeader>" equals "captureKey"

    When , save "unchanged" as "optionalRowSentinel"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, for any Data Row equaling "does-not-exist":
    : * , save "changed" as "optionalRowSentinel"
    Then , verify "<optionalRowSentinel>" equals "unchanged"

  Scenario: Convert a Cucumber DataTable to JsonNode and back to DataTable
    Given CLEAR SAVED VALUES

    # Legacy Data converts the native marker DataTable to Jackson data.
    When , save "<data:Data element native fixtures.Cucumber sources.roundtrip>" Data as "tableAsData"
    Then , verify "<tableAsData[0].id>" equals "a1"
    And , verify "<tableAsData[1].owner>" equals "Ben"
    And , verify "<tableAsData[2].score>" equals "33"

    # The resulting JsonNode can become a native Data Table context again.
    When , in the "<tableAsData>" Data Table, save Data Rows as "rowsAfterRoundTrip"
    Then , verify "<rowsAfterRoundTrip[0].id>" equals "a1"
    And , verify "<rowsAfterRoundTrip[1].active>" equals "false"
    And , verify "<rowsAfterRoundTrip[2].owner>" equals "Cara"

    When , in the "<tableAsData>" Data Table, for every Data Row:
    : * , save "<owner>" as "lastRoundTripOwner"
    Then , verify "<lastRoundTripOwner>" equals "Cara"

  Scenario: Unquoted Data Table prefers the first unnamed table below
    Given CLEAR SAVED VALUES

    * ------
      | captureKey | value        |
      | aboveTable | not selected |

    When , in the Data Table, for every Data Row:
    : * , save "<value>" as "<captureKey>"

    * ------
      | captureKey | value          |
      | belowTable | selected below |

    * ------
      | captureKey      | value              |
      | laterBelowTable | not selected later |

    Then , verify "<belowTable>" equals "selected below"

  Scenario: Unquoted Data Table falls back to the nearest unnamed table above
    Given CLEAR SAVED VALUES

    * ------
      | captureKey | value              |
      | fartherUp  | not selected above |

    * ------
      | captureKey | value                  |
      | nearestUp  | selected nearest above |

    When , in the Data Table, for every Data Row:
    : * , save "<value>" as "<captureKey>"

    Then , verify "<nearestUp>" equals "selected nearest above"
