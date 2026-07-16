Feature: Locate repeated and scoped catalog elements

  Background:
    * navigate to: URL.catalog
    * , ensure "Catalog & Context Playground" Text is displayed

  @all @regression @browser @local-site @catalog @context @custom-elements @smoke @filtering
  Scenario: Filter products and assert custom element categories
    * , ensure the "Starter Plan" Product Card is displayed
    * , ensure the "Available" Status Badge is displayed
    * , enter "Team" in the "Catalog Search" Textbox
    * , ensure "Visible Products: 1" Text is displayed
    * , ensure the "Team Plan" Product Card is displayed
    * , ensure the "Starter Plan" Product Card is not displayed

  @all @regression @browser @local-site @catalog @context @ordinal-elements @repeated-elements
  Scenario: Ordinal selection chooses repeated buttons
    * , click the 2nd "View Details" Button
    * , ensure "Selected Product: 2" Text is displayed
    * , click the last "View Details" Button
    * , ensure "Selected Product: 3" Text is displayed

  @all @regression @browser @local-site @catalog @context @page-context @scoped-elements
  Scenario: A page context restricts a repeated action
    * , in the "Secondary Queue" Test Panel, click the "Approve" Button
    * , ensure "Queue Result: secondary" Text is displayed
    * , in the "Primary Queue" Test Panel, click the "Approve" Button
    * , ensure "Queue Result: primary" Text is displayed

  @all @regression @browser @local-site @catalog @context @expanded-collapsed @state-assertions
  Scenario: Expanded and collapsed states are visible on a control
    * , ensure the "Advanced Filters" Button is collapsed
    * , click the "Advanced Filters" Button
    * , ensure the "Advanced Filters" Button is expanded
    * , ensure "Advanced Filter Panel: open" Text is displayed
