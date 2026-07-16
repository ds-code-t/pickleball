Feature: Navigate between the local test pages
  The test site exposes real links so navigation behavior can be described in business language.

  Background:
    * navigate to: URL.home
    * , ensure "Pickleball Test Lab" Text is displayed
    * , ensure "Site Status: ready" Text is displayed

  @all @regression @browser @local-site @navigation @smoke @links @page-navigation
  Scenario: Open a playground from the dashboard and return home
    * , click the "Open Forms Playground" Link
    * , ensure "Forms Playground" Text is displayed
    * , click the "Home" Link
    * , ensure "Site Status: ready" Text is displayed

  @all @regression @browser @local-site @navigation @primary-navigation
  Scenario: Use the primary navigation across several pages
    * , click the "Catalog" Link
    * , ensure "Catalog & Context Playground" Text is displayed
    * , click the "Workflow" Link
    * , ensure "Conditional Workflow Playground" Text is displayed
    * , click the "Keyboard" Link
    * , ensure "Keyboard Playground" Text is displayed
