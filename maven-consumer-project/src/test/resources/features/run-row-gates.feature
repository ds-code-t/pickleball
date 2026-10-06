Feature: Called rows for RunIf and RunBackground

  Background:
    * , save "B" as "<token>"

  @run-row-visit
  Scenario: Run row child visit
    * the called row "<token>" records its gates

  @run-row-bomb
  Scenario: Run row child bomb
    * FAIL: this row should have been skipped

  @run-row-multi
  Scenario: Run row multi bomb alpha
    * FAIL: multi match alpha should have been skipped

  @run-row-multi
  Scenario: Run row multi bomb beta
    * FAIL: multi match beta should have been skipped

  @run-row-multi-ok
  Scenario: Run row multi ok alpha
    * , save "alpha" as "multiAlpha"
    * the called row "<token>" records its gates

  @run-row-multi-ok
  Scenario: Run row multi ok beta
    * , save "beta" as "multiBeta"
    * the called row "<token>" records its gates

  Scenario Outline: Scan partial name rows
    * , save "<row>" as "exampleRow"
    * , verify "<row>" equals "2"
    Examples:
      | row |
      | 1   |
      | 2   |
      | 3   |
