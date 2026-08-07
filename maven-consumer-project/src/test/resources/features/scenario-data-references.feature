Feature: Scenario data references

  @all @regression @scenario-data
  Scenario: Read marker arguments from the default data path
    * VERIFY DATA ADDRESS "Customer record.payload" HAS MARKER "payload"
    * VERIFY DATA ADDRESS "Customer record.payload" HAS DATA TABLE WITH 3 DATA ROWS
    * VERIFY DATA ADDRESS "Customer record.message" HAS DOC STRING CONTENT "marker doc string"
    * VERIFY EMBEDDED DATA TABLE ADDRESS "Customer record.payload" HAS 3 DATA ROWS
    * VERIFY EMBEDDED DOC STRING ADDRESS "Customer record.message" HAS CONTENT "marker doc string"

  @all @regression @scenario-data
  Scenario: Use RUN SCENARIO options for marker data lookup
    * VERIFY DATA ADDRESS "payload" HAS MARKER "payload"
      | pkb_features            | pkb_name          |
      | src/test/resources/data | ^Customer record$ |

  @all @regression @scenario-data @data-table @data-row
  Scenario: Iterate a marker DataTable from another scenario
    Given CLEAR SAVED VALUES
    When , in the "<data:Data reference records.Customer record.payload>" Data Table, for every Data Row:
    : * , save "<Key1>" as "lastMarkerKey", and save "<Key2>" as "lastMarkerValue"
    : * , save "<Key2>" as "<Key1>"
    Then RUN MAP PATH "qq" HAS VALUE "ww"
    And RUN MAP PATH "ee" HAS VALUE "rr"
    And RUN MAP PATH "tt" HAS VALUE "yy"
    And RUN MAP PATH "lastMarkerKey" HAS VALUE "tt"
    And RUN MAP PATH "lastMarkerValue" HAS VALUE "yy"

  @all @regression @scenario-data
  Scenario: Resolve an attached table from a normalized current marker
    * ------ -- pay-load 2
      | Key   | Value   |
      | local | current |
    * VERIFY EMBEDDED DATA TABLE ADDRESS "pay-load 2" HAS 1 DATA ROWS


  @all @regression @scenario-data @data-table @data-row
  Scenario: An unquoted Data Table prefers the first unnamed marker below
    Given CLEAR SAVED VALUES
    * ------
      | captureKey | value         |
      | aboveTable | not selected  |
    When , in the Data Table, for every Data Row:
    : * , save "<value>" as "<captureKey>"
    * ------
      | captureKey | value          |
      | belowTable | selected below |
    * ------
      | captureKey     | value              |
      | laterBelowTable | not selected later |
    Then RUN MAP PATH "belowTable" HAS VALUE "selected below"

  @all @regression @scenario-data @data-table @data-row
  Scenario: An unquoted Data Table falls back to the nearest unnamed marker above
    Given CLEAR SAVED VALUES
    * ------
      | captureKey | value              |
      | fartherUp  | not selected above |
    * ------
      | captureKey | value                  |
      | nearestUp  | selected nearest above |
    When , in the Data Table, for every Data Row:
    : * , save "<value>" as "<captureKey>"
    Then RUN MAP PATH "nearestUp" HAS VALUE "selected nearest above"

  @all @regression @scenario-data @data
  Scenario: Unquoted Data prefers an unnamed DocString below and converts it to JSON
    Given CLEAR SAVED VALUES
    * ------
      """json
      {
        "source": "above"
      }
      """
    When , save Data as "unnamedDocData"
    * ------
      """json
      {
        "source": "below",
        "nested": {
          "value": 7
        }
      }
      """
    Then RUN MAP QUERY "unnamedDocData" RETURNS TYPE "ObjectNode"
    And RUN MAP PATH "unnamedDocData.source" HAS VALUE "below"
    And RUN MAP PATH "unnamedDocData.nested.value" HAS VALUE "7"

  @all @regression @scenario-data @data
  Scenario: Unquoted Data falls back to an unnamed DataTable above and converts it to JSON
    Given CLEAR SAVED VALUES
    * ------
      | captureKey | value          |
      | tableData  | selected above |
    When , save Data as "unnamedTableData"
    Then RUN MAP QUERY "unnamedTableData" RETURNS TYPE "ObjectNode"
    And RUN MAP PATH "unnamedTableData.captureKey" HAS VALUE "tableData"
    And RUN MAP PATH "unnamedTableData.value" HAS VALUE "selected above"

  @all @regression @scenario-data
  Scenario: Cache every marker before execution filtering
    * VERIFY CURRENT SCENARIO MARKER CACHE HAS NAMED "after end" AND UNNAMED STEP 3
    * ---endstep
    * ------
    * ---after end
