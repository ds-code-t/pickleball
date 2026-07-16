Feature: Accept and dismiss browser dialogs

  Background:
    * navigate to: URL.dialogs
    * , ensure "Dialogs Playground" Text is displayed

  @all @regression @browser @local-site @dialogs @alerts @accept-dialog @smoke
  Scenario: Accept a JavaScript alert
    * , click the "Show Alert" Button
    * , accept the Alert
    * , ensure "Dialog Result: alert accepted" Text is displayed

  @all @regression @browser @local-site @dialogs @confirmations @accept-dialog
  Scenario: Accept a JavaScript confirmation
    * , click the "Show Confirmation" Button
    * , accept the Alert
    * , ensure "Dialog Result: confirmation accepted" Text is displayed

  @all @regression @browser @local-site @dialogs @confirmations @dismiss-dialog
  Scenario: Dismiss a JavaScript confirmation
    * , click the "Show Confirmation" Button
    * , dismiss the Alert
    * , ensure "Dialog Result: confirmation dismissed" Text is displayed
