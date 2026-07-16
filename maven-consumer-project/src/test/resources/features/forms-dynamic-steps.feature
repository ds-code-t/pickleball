Feature: Use dynamic steps against form controls

  Background:
    * navigate to: URL.forms
    * , ensure "Forms Playground" Text is displayed

  @all @regression @browser @local-site @forms @dynamic-steps @smoke @form-input @selection-controls
  Scenario: Text entry and selection update visible DOM state
    * , enter "Ava" in the "First Name" Textbox
    * , enter "Tester" in the "Last Name" Textbox
    * , enter "ava@example.test" in the "Email Address" Textbox
    * , overwrite "3" in the "Quantity" Textbox
    * , enter "Created by Pickleball" in the "Notes" Textarea
    * , select "Premium" in the "Account Type" Dropdown
    * , click the "Email" Radio Button
    * , click the "Receive Updates" Checkbox
    * , ensure "Name: Ava Tester" Text is displayed
    * , ensure "Email: ava@example.test" Text is displayed
    * , ensure "Quantity: 3" Text is displayed
    * , ensure "Notes: Created by Pickleball" Text is displayed
    * , ensure "Account Type: Premium" Text is displayed
    * , ensure "Contact Preference: Email" Text is displayed
    * , ensure "Updates: checked" Text is displayed

  @all @regression @browser @local-site @forms @dynamic-steps @state-assertions @chained-steps @form-submission
  Scenario: State assertions and a chained action describe submission
    * , ensure the "Receive Updates" Checkbox is unchecked
    * , ensure the "Locked Action" Button is disabled
    * , enter "Mia" in the "First Name" Textbox, select "Standard" in the "Account Type" Dropdown, click the "Phone" Radio Button, and click the "Submit Form" Button
    * , ensure "Submitted: Mia | Standard | Phone | no updates" Text is displayed

  @all @regression @browser @local-site @forms @dynamic-steps @clear-reset
  Scenario: Clear and reset restore predictable values
    * , enter "Temporary" in the "First Name" Textbox
    * , clear the "First Name" Textbox
    * , ensure "Name: (empty)" Text is displayed
    * , enter "Keep" in the "First Name" Textbox
    * , click the "Reset Form" Button
    * , ensure "Name: (empty)" Text is displayed
    * , ensure "Submitted: not yet" Text is displayed

  @all @regression @browser @local-site @forms @dynamic-steps @pointer-actions
  Scenario: Pointer actions update the interaction status
    * , move to the "Interaction Target" Button
    * , ensure "Last Pointer Action: moved over" Text is displayed
    * , double click the "Interaction Target" Button
    * , ensure "Last Pointer Action: double click" Text is displayed
    * , right click the "Interaction Target" Button
    * , ensure "Last Pointer Action: right click" Text is displayed
