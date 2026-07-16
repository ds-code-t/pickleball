Feature: Use keyboard expressions in a text field

  Background:
    * navigate to: URL.keyboard
    * , ensure "Keyboard Playground" Text is displayed

  @all @regression @browser @local-site @keyboard @keyboard-expressions @modifier-keys @smoke
  Scenario: Select all and replace the existing value
    * , enter "replace me" in the "Keyboard Input" Textbox
    * , press "CONTROL[A] BACK_SPACE" in the "Keyboard Input" Textbox
    * , enter "new value" in the "Keyboard Input" Textbox
    * , ensure "Keyboard Value: new value" Text is displayed

  @all @regression @browser @local-site @keyboard @keyboard-expressions @named-keys
  Scenario: A named key is delivered to the focused field
    * , click the "Keyboard Input" Textbox
    * , press "ENTER" in the "Keyboard Input" Textbox
    * , ensure "Last Key: Enter" Text is displayed
