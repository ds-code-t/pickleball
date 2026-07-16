@dynamic @local-page
Feature: Pickleball dynamic steps against a local interaction playground
  The page is intentionally simple so the scenarios focus on dynamic step parsing,
  accessible element selection, DOM events, and visible-state assertions.

  Background:
    * navigate to: URL.dynamicSteps
    * , ensure "Dynamic Steps Playground" Text is displayed
    * , ensure "Ready for dynamic steps" Text is displayed

  Scenario: Textboxes and a textarea update visible text
    * , enter "Ava" in the "First Name" Textbox
    * , enter "Phoenix" in the "City" Textbox
    * , enter "Hello from Pickleball" in the "Comments" Textarea
    * , ensure "Name: Ava" Text is displayed
    * , ensure "City: Phoenix" Text is displayed
    * , ensure "Comments: Hello from Pickleball" Text is displayed

  Scenario: A checkbox updates its state and the DOM
    * , ensure "Receive Updates" Checkbox is unchecked
    * , click the "Receive Updates" Checkbox
    * , ensure "Receive Updates" Checkbox is checked
    * , ensure "Updates: checked" Text is displayed

  Scenario: Radio buttons update the selected contact preference
    * , click the "Email" Radio Button
    * , ensure "Email" Radio Button is selected
    * , ensure "Contact Preference: Email" Text is displayed
    * , click the "Phone" Radio Button
    * , ensure "Phone" Radio Button is selected
    * , ensure "Contact Preference: Phone" Text is displayed

  Scenario: A dropdown selection updates visible text
    * , select "Green" in the "Favorite Color" Dropdown
    * , ensure "Favorite Color: Green" Text is displayed
    * , select "Blue" in the "Favorite Color" Dropdown
    * , ensure "Favorite Color: Blue" Text is displayed

  Scenario: Ordinal selection chooses the correct repeated element
    * , click the 2nd "Choose" Button
    * , ensure "Chosen Button: 2" Text is displayed
    * , click the last "Choose" Button
    * , ensure "Chosen Button: 3" Text is displayed

  Scenario: A chained dynamic step performs multiple DOM interactions
    * , enter "Mia" in the "First Name" Textbox, enter "Tempe" in the "City" Textbox, and click the "Submit" Button
    * , ensure "Submitted: Mia | Tempe | none | no updates" Text is displayed
