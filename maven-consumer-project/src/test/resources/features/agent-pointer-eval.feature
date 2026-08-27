@agent-pointer-eval
Feature: Agent pointer-eval mixed harness
  Intentional mixed pass/fail harness for consumer AI-agent pointer tests.
  It is not part of @all or @regression. Run it only with pkb_tags=@agent-pointer-eval
  (or the pkb_runvars equivalent). Failures are canned and stable. Do not "fix"
  this file as if it were product coverage unless the human asked to change the harness.

  @agent-pointer-eval @agent-pointer-eval-pass
  Scenario: Agent pointer eval passing pickleball equality
    * , verify "pickleball" equals "pickleball"

  @agent-pointer-eval @agent-pointer-eval-pass
  Scenario: Agent pointer eval passing pointer token equality
    * , verify "pointer-eval" equals "pointer-eval"

  @agent-pointer-eval @agent-pointer-eval-fail
  Scenario: Agent pointer eval failing fruit mismatch
    * , verify "apple" equals "orange"

  @agent-pointer-eval @agent-pointer-eval-fail
  Scenario: Agent pointer eval failing greek-letter mismatch
    * , verify "alpha" equals "omega"

  @agent-pointer-eval @agent-pointer-eval-pass
  Scenario: Agent pointer eval passing home heading
    * navigate to: URL.home
    * , ensure "Pickleball Test Lab" Text is displayed

  @agent-pointer-eval @agent-pointer-eval-fail
  Scenario: Agent pointer eval failing forms heading
    * navigate to: URL.forms
    * , ensure "AGENT POINTER EVAL INTENTIONAL FORMS HEADING" Text is displayed
