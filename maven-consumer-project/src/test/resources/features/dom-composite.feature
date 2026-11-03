Feature: Dom-composite adapter path and backward-compatible phrases

  @all @regression @browser @local-site @dom-composite @adapter-actions
  Scenario: Click, type, hover, scroll, drag, keys, wait, and presence share one path
    * navigate to: URL.forms
    * , enter "Ava" in the "First Name" Textbox
    * , ensure "Name: Ava" Text is displayed
    * , click the "Email" Radio Button
    * , ensure "Contact Preference: Email" Text is displayed
    * , hover the "Interaction Target" Button
    * , ensure "Last Pointer Action: moved over" Text is displayed
    * , press "A" in the "First Name" Textbox
    * , ensure "Name: Avaa" Text is displayed
    * , wait 1 seconds
    * , ensure the "Locked Action" Button is displayed
    * navigate to: URL.components
    * , scroll the "Scroll Target" Button
    * , ensure "Scroll State: moved" Text is displayed
    * , dragAndDrop the "Drag Source" Button the "Drop Target" Button
    * , ensure "Drag Result: dropped" Text is displayed

  @all @regression @browser @local-site @dom-composite @adapter-lookups
  Scenario: Text, dictionary XPath, CSS options, and context words resolve
    * navigate to: URL.forms
    * , ensure the "Submit Form" Button is displayed
    * , ensure the Button containing "Submit" is displayed
    * , ensure the Button matching "Submit Form" is displayed
    * , select "Premium" in the "Account Type" Dropdown
    * , ensure "Account Type: Premium" Text is displayed
    * navigate to: URL.catalog
    * , ensure the "Starter Plan" Product Card is displayed
    * , ensure the "Missing Plan" Product Card is not displayed
    * , in the "Secondary Queue" Test Panel, click the "Approve" Button
    * , ensure "Queue Result: secondary" Text is displayed
    * , from the "Secondary Queue" Test Panel, ensure the "Approved" Status Badge is not displayed
    * , for the "Primary Queue" Test Panel, ensure the "Review" Status Badge is not displayed
    * navigate to: URL.components
    * , after the "Order Anchor" Button, ensure the "Before Marker" Button is not displayed
    * , before the "Order Anchor" Button, ensure the "After Marker" Button is not displayed

  @all @regression @browser @local-site @dom-composite @backward-compat
  Scenario: Representative existing phrases still run
    * navigate to: URL.forms
    * , enter "Mia" in the "First Name" Textbox, select "Standard" in the "Account Type" Dropdown, click the "Phone" Radio Button, and click the "Submit Form" Button
    * , ensure "Submitted: Mia | Standard | Phone | no updates" Text is displayed
    * navigate to: URL.catalog
    * , click the 2nd "View Details" Button
    * , ensure "Selected Product: 2" Text is displayed
    * navigate to: URL.keyboard
    * , click the "Keyboard Input" Textbox
    * , press "ENTER" in the "Keyboard Input" Textbox
    * , ensure "Last Key: Enter" Text is displayed
