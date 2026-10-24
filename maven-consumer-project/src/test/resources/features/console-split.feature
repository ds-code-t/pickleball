Feature: Split a Workbench console when Cucumber prints no scenario banner

  @console-split
  Scenario: Pend
    * navigate to: URL.home
    * , ensure "Pickleball Test Lab" Text is displayed
    * PRINT CONSOLE PROBE PEND

  @console-split
  Scenario: Pend case
    * navigate to: URL.home
    * , ensure "Pickleball Test Lab" Text is displayed
    * PRINT CONSOLE PROBE CASE

  @console-split
  Scenario: C:\Users\Dan She'll\page
    * navigate to: URL.home
    * , ensure "Pickleball Test Lab" Text is displayed
    * PRINT CONSOLE PROBE WIN
