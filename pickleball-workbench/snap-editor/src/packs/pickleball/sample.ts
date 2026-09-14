export const WORKBENCH_SAMPLE = `Feature: Workbench Live Scenario
  Scenario: Open the local test site
    Given navigate to: URL.home
    When , ensure "Pickleball Test Lab" Text is displayed:
    : And , click the "Open Forms Playground" Link
    Then , click the "Save, now" Button; wait for page
    * IF: 1 == 1:
    : * , save "A" as "result"
    * ELSE-IF: 2 == 2:
    : * , save "B" as "result"
    * ELSE:
    : * , save "C" as "result"
`;
