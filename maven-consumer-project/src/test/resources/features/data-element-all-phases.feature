@all @regression @data-elements @data-element-phases-1-6
Feature: Data Element complete six-phase regression

  Scenario: All six Data Element phases preserve syntax and runtime behavior
    Given CLEAR SAVED VALUES

    # Phase 1: explicit registry, singular/plural forms, aliases,
    # shared text matching, attributes, cardinality, and selection validation.
    And RUN DATA ELEMENT PHASE 1 JAVA TESTS

    # Phase 2: lossless tabular projection and materialization.
    And RUN DATA ELEMENT PHASE 2 JAVA TESTS

    # Phase 3: runtime result modes, aggregate terminal values,
    # iteration expansion, parser compilation, and subtype preservation.
    And RUN DATA ELEMENT PHASE 3 JAVA TESTS

    # Phases 4 and 6: List, Map, Set, Multimap, JSON, YAML, XML,
    # Structured Data, Data String, and typed DocString behavior.
    And RUN DATA ELEMENT PHASE 4 AND 6 JAVA TESTS

    # Phase 5: copy-on-write contexts and replacement-only mutation.
    And RUN DATA ELEMENT PHASE 5 JAVA TESTS

    # Consumer-facing named DataTable syntax and terminal result modes.
    And CLEAR SAVED VALUES
    And SET "ALL_PHASES_TABLE" DATA TABLE
      | captureKey | id | status   |
      | phaseRow1  | r1 | ready    |
      | phaseRow2  | r2 | pending  |
      | phaseRow3  | r3 | complete |
      | phaseRow4  | r4 | archived |

    When , in the "<ALL_PHASES_TABLE>" Data Table, save Data Row as "ALL_PHASES_FIRST_ROW"
    Then RUN MAP QUERY "ALL_PHASES_FIRST_ROW" RETURNS TYPE "ObjectNode"
    And RUN MAP PATH "ALL_PHASES_FIRST_ROW.id" HAS VALUE "r1"
    And RUN MAP PATH "ALL_PHASES_FIRST_ROW.status" HAS VALUE "ready"

    When , in the "<ALL_PHASES_TABLE>" Data Table, save Data Rows as "ALL_PHASES_ROWS"
    Then RUN MAP QUERY "ALL_PHASES_ROWS" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "ALL_PHASES_ROWS[0].id" HAS VALUE "r1"
    And RUN MAP PATH "ALL_PHASES_ROWS[1].id" HAS VALUE "r2"
    And RUN MAP PATH "ALL_PHASES_ROWS[2].id" HAS VALUE "r3"
    And RUN MAP PATH "ALL_PHASES_ROWS[3].id" HAS VALUE "r4"

    # Every-Nth iteration is applied after candidate selection.
    When , in the "<ALL_PHASES_TABLE>" Data Table, for every 2nd Data Row:
  : * , save "<status>" as "<captureKey>"
    Then RUN MAP PATH "phaseRow2" HAS VALUE "pending"
    And RUN MAP PATH "phaseRow4" HAS VALUE "archived"

    # Unquoted Data Table prefers the nearest qualifying unnamed marker below.
    When , in the Data Table, for every Data Row:
  : * , save "<value>" as "<captureKey>"
    * ------
      | captureKey         | value          |
      | allPhasesBelowTable | selected below |
    Then RUN MAP PATH "allPhasesBelowTable" HAS VALUE "selected below"

    # With no qualifying DataTable below, lookup falls back to the nearest above.
    When , in the Data Table, for every Data Row:
  : * , save "<value>" as "ALL_PHASES_TABLE_ABOVE_FALLBACK"
    Then RUN MAP PATH "ALL_PHASES_TABLE_ABOVE_FALLBACK" HAS VALUE "selected below"

    # Unquoted Data prefers the nearest qualifying unnamed DocString below.
    When , save Data as "ALL_PHASES_DATA_BELOW"
    * ------
      """json
      {
        "source": "below",
        "nested": {
          "value": 7
        }
      }
      """
    Then RUN MAP QUERY "ALL_PHASES_DATA_BELOW" RETURNS TYPE "ObjectNode"
    And RUN MAP PATH "ALL_PHASES_DATA_BELOW.source" HAS VALUE "below"
    And RUN MAP PATH "ALL_PHASES_DATA_BELOW.nested.value" HAS VALUE "7"

    # With no qualifying marker below, unquoted Data falls back to the nearest above.
    When , save Data as "ALL_PHASES_DATA_ABOVE_FALLBACK"
    Then RUN MAP QUERY "ALL_PHASES_DATA_ABOVE_FALLBACK" RETURNS TYPE "ObjectNode"
    And RUN MAP PATH "ALL_PHASES_DATA_ABOVE_FALLBACK.source" HAS VALUE "below"
    And RUN MAP PATH "ALL_PHASES_DATA_ABOVE_FALLBACK.nested.value" HAS VALUE "7"
