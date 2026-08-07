@all @regression @data-elements @data-element-phase-3
Feature: Data Element runtime result modes

  Scenario: Singular and plural terminal table projections preserve cardinality
    Given CLEAR SAVED VALUES
    And SET "PHASE3_RESULT_TABLE" DATA TABLE
      | id | status   |
      | r1 | ready    |
      | r2 | pending  |
      | r3 | complete |
    When , in the "<PHASE3_RESULT_TABLE>" Data Table, save Data Row as "PHASE3_FIRST_ROW"
    Then RUN MAP QUERY "PHASE3_FIRST_ROW" RETURNS TYPE "ObjectNode"
    And RUN MAP PATH "PHASE3_FIRST_ROW.id" HAS VALUE "r1"
    And RUN MAP PATH "PHASE3_FIRST_ROW.status" HAS VALUE "ready"
    When , in the "<PHASE3_RESULT_TABLE>" Data Table, save Data Rows as "PHASE3_ALL_ROWS"
    Then RUN MAP QUERY "PHASE3_ALL_ROWS" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "PHASE3_ALL_ROWS[0].id" HAS VALUE "r1"
    And RUN MAP PATH "PHASE3_ALL_ROWS[1].id" HAS VALUE "r2"
    And RUN MAP PATH "PHASE3_ALL_ROWS[2].id" HAS VALUE "r3"

  Scenario: Every-Nth table iteration applies stride after selection
    Given CLEAR SAVED VALUES
    And SET "PHASE3_STRIDE_TABLE" DATA TABLE
      | captureKey | value |
      | first      | one   |
      | second     | two   |
      | third      | three |
      | fourth     | four  |
    When , in the "<PHASE3_STRIDE_TABLE>" Data Table, for every 2nd Data Row:
    : * , save "<value>" as "<captureKey>"
    Then RUN MAP PATH "second" HAS VALUE "two"
    And RUN MAP PATH "fourth" HAS VALUE "four"
