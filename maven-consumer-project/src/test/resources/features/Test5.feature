Feature: Test5 feature

  Scenario Outline: Starting Scenario
    * call scenario <tag>

    Examples:
      | Scenario | tag   |
      | 1        | %pcs |
      | 2        | %pcs3 |
      | 2        | %pcs3 |
      | 3        | %pcs1 |
      | 3        | %pcs4 |
      | 3        | %pcs2 |


  Scenario Outline: Programmatically called Scenario1
    * , if "1" equals "1":
  : * , save "A"
    Examples:
      | Scenario Tags |
      | %pcs1         |


  Scenario Outline: Programmatically called Scenario2
    * , if "1" equals "1":
  : * , save "A"
    Examples:
      | Scenario Tags |
      | %pcs2         |