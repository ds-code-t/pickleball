@all @separate-line-else
Feature: Separate-line ELSE after a single-line IF THEN

  Scenario: Separate-line ELSE runs after a false single-line IF
    * IF: 1 == 2 THEN: , save "I" as "c"
    * ELSE: , save "F" as "c"
    * , verify "<c>" equals "F"

  Scenario: Separate-line ELSE-IF and ELSE after a false single-line IF
    * IF: 1 == 2 THEN: , save "A" as "d"
    * ELSE-IF: 2 == 2 THEN: , save "B" as "d"
    * ELSE: , save "C" as "d"
    * , verify "<d>" equals "B"

  Scenario: Separate-line ELSE is skipped after a true single-line IF
    * IF: 2 == 2 THEN: , save "I" as "g"
    * ELSE: , save "F" as "g"
    * , verify "<g>" equals "I"

  Scenario: Block ELSE after a false single-line IF
    * IF: 1 == 2 THEN: , save "A" as "h"
    * ELSE:
    * , save "H" as "h"
    * , verify "<h>" equals "H"
