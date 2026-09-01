Feature: Use it as an anaphoric element in dynamic steps
  `it` refers to a previously named element. Gather keeps `it` on the action
  that owns it, and replacement walks previous phrases, including a nested
  parent or ancestor step. Quoted "it" stays ordinary text.

  Background:
    * navigate to: URL.forms
    * , ensure "Forms Playground" Text is displayed

  @all @regression @browser @local-site @forms @dynamic-steps @it-placeholder @nested-steps @pointer-actions
  Scenario: it binds backward across phrases, trailing actions, and nested ancestors
    * , enter "it" in the "First Name" Textbox
    * , ensure "Name: it" Text is displayed
    * , if the "Submit Form" Button is displayed, ensure it is displayed
    * , if the "Account Type" Dropdown is displayed, select "Premium" in it
    * , ensure "Account Type: Premium" Text is displayed
    * , ensure the "Receive Updates" Checkbox is unchecked
    * , if the "Receive Updates" Checkbox is displayed, click it, and wait 1 seconds.
    * , ensure "Receive Updates" Checkbox is checked
    * , ensure "Updates: checked" Text is displayed
    * , overwrite "Ava" in the "First Name" Textbox
    * , if the "Submit Form" Button is displayed, click it, and wait 1 seconds.
    * , ensure "Submitted: Ava | Premium | none | updates" Text is displayed
    * , click the "Reset Form" Button
    * , enter "Mia" in the "First Name" Textbox
    * , if the "Submit Form" Button is displayed, click it
    * , ensure "Submitted: Mia | none | none | no updates" Text is displayed
    * , click the "Reset Form" Button
    * , enter "Kai" in the "First Name" Textbox
    * , if the "Submit Form" Button is displayed, click the "Submit Form" Button, and wait 1 seconds.
    * , ensure "Submitted: Kai | none | none | no updates" Text is displayed
    * , click the "Reset Form" Button
    * , enter "Nia" in the "First Name" Textbox
    * , if the "Email" Radio Button is displayed, click it, and click the "Submit Form" Button
    * , ensure "Submitted: Nia | none | Email | no updates" Text is displayed
    * , click the "Reset Form" Button
    * , enter "Bo" in the "First Name" Textbox
    * , if the "Submit Form" Button is displayed, click it, then wait 1 seconds.
    * , ensure "Submitted: Bo | none | none | no updates" Text is displayed
    * , click the "Reset Form" Button
    * , enter "Rio" in the "First Name" Textbox
    * , if the "Phone" Radio Button is displayed, click it; click the "Submit Form" Button
    * , ensure "Submitted: Rio | none | Phone | no updates" Text is displayed
    * , if the "Interaction Target" Button is displayed, double click it, and click the "Reset Form" Button
    * , ensure "Last Pointer Action: double click" Text is displayed
    * , if the "Interaction Target" Button is displayed, move to it, and wait 1 seconds.
    * , ensure "Last Pointer Action: moved over" Text is displayed
    * , if the "Interaction Target" Button is displayed, right click it, and click the "Reset Form" Button
    * , ensure "Last Pointer Action: right click" Text is displayed
    * , enter "Parent" in the "First Name" Textbox
    * , in the "Profile Form" Test Panel, if the "Submit Form" Button is displayed:
    : * , click it, and wait 1 seconds.
    * , ensure "Submitted: Parent | none | none | no updates" Text is displayed
    * , click the "Reset Form" Button
    * , enter "Ancestor" in the "First Name" Textbox
    * , if the "Submit Form" Button is displayed:
    : * , save "nested-marker" as "itAncestorMarker":
    :: * , click it
    * , ensure "Submitted: Ancestor | none | none | no updates" Text is displayed
    * , ensure "<itAncestorMarker>" equals "nested-marker"
