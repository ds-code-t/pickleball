Feature: Test4 feature

  Scenario Outline: Starting Scenario
    * call scenario %pcs

    Examples:
      | Scenario |
      | 1        |
      | 2        |
      | 3        |


  Scenario Outline: Programmatically called Scenario
    * , if "1" equals "1":
  : * , save "A"
    Examples:
      | Scenario Tags |
      | %pcs          |