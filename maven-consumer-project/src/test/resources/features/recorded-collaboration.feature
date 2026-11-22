@dynamic @local-page @recorded
Feature: A routine recorded from the interaction playground
  This scenario is the shape `record --export --format=feature` writes.
  The steps are ordinary dynamic steps. The Examples row is the recorded values.

  Scenario Outline: Recorded name, color, and submit
    * navigate to: URL.dynamicSteps
    * , enter "<firstName>" in the "First Name" Textbox
    * , select "<favoriteColor>" in the "Favorite Color" Dropdown
    * , click the "Submit" Button
    * , ensure "Name: <firstName>" Text is displayed
    * , ensure "Favorite Color: <favoriteColor>" Text is displayed

    Examples:
      | firstName | favoriteColor |
      | Ava | Green |
      | Mia | Blue |
