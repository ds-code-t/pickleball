Feature: Choose workflow paths with nested steps and block conditionals

  Background:
    * navigate to: URL.workflow
    * , ensure "Conditional Workflow Playground" Text is displayed

  @all @regression @browser @local-site @workflow @nested-steps @conditional-steps @smoke
  Scenario: A nested condition passes its result and panel context to child steps
    * , click the "Use Ready State" Button
    * , in the "Decision Panel" Test Panel, if the "Submit Request" Button is enabled, and the "Validation Error" Text is not displayed:
    : * , click the "Submit Request" Button
    : * , ensure "Request Result: submitted" Text is displayed

  @all @regression @browser @local-site @workflow @nested-steps @question-parent
  Scenario: A question-mark parent passes only its condition
    * , click the "Use Ready State" Button
    * , in the "Decision Panel" Test Panel, the "Submit Request" Button is enabled?
    : * , ensure "Workflow State: ready" Text is displayed

  @all @regression @browser @local-site @workflow @block-conditionals @phrase-condition
  Scenario: Phrase-style block conditions select the error recovery path
    * , click the "Use Error State" Button
    * IF: the "Validation Error" Text is displayed:
    : * , click the "Refresh Request" Button
    : * , ensure "Workflow State: review" Text is displayed
    * ELSE:
    : * , click the "Submit Request" Button

  @all @regression @browser @local-site @workflow @block-conditionals @expression-condition
  Scenario: Expression-style block conditions use explicit Boolean logic
    * IF: 1 < 4 && true && 6 && "A" THEN: , click the "Use Ready State" Button
    * ELSE: , click the "Use Error State" Button
    * , ensure "Workflow State: ready" Text is displayed

  @all @regression @browser @local-site @workflow @block-conditionals @inline-condition
  Scenario: An inline block chain runs only its first matching branch
    * IF: 5 == 1 THEN: , click the "Use Error State" Button ELSE-IF: 5 == 5 THEN: , click the "Use Ready State" Button ELSE: , click the "Use Review State" Button
    * , ensure "Workflow State: ready" Text is displayed
