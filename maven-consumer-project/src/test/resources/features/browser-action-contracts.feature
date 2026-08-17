@contract-coverage-217 @regression @browser @local-site @browser-action-contracts
Feature: Browser and component action contracts

  @window-switch
  Scenario: Switch moves to a new window and back to the previous window
    * navigate to: URL.windowActions
    * , ensure "Window Action Playground" Text is displayed
    * , click the "Open Child Window" Link
    * , switch the New Window
    * , ensure "Child Window" Text is displayed
    * , switch the Previous Window
    * , ensure "Window Action Playground" Text is displayed

  @component-close
  Scenario: Close uses the configured close control inside a matched component
    * navigate to: URL.components
    * , ensure "Dismissible Notice" Test Panel is displayed
    * , close the "Dismissible Notice" Test Panel
    * , ensure "Close State: closed" Text is displayed
    * , ensure the "Dismissible Notice" Test Panel is not displayed
