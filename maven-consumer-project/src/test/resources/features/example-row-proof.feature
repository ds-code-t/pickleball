@example-row-proof
Feature: Partial scenario name keeps one example row

  Scenario Outline: Partial name example rows
    * , verify "<row>" equals "2"
    Examples:
      | row |
      | 1   |
      | 2   |
      | 3   |

  Scenario: Partial name plain scenario
    * FAIL: plain scenario should not run when example row 2 is selected
