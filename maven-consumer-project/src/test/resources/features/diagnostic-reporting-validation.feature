Feature: AI diagnostic reporting validation

  @diagnostic-validation @diagnostic-pass-suite @diagnostic-logging
  Scenario: Diagnostic log level layering
    * emit diagnostic log markers "diagnostic-logging"
    * , verify "diagnostic" equals "diagnostic"

  @diagnostic-validation @diagnostic-pass-suite @diagnostic-outline
  Scenario Outline: Diagnostic outline row identity
    * emit diagnostic log markers "<rowId>"
    * , verify "<actual>" equals "<expected>"

    Examples:
      | rowId         | actual | expected |
      | outline-alpha | alpha  | alpha    |
      | outline-beta  | beta   | beta     |

  @diagnostic-validation @diagnostic-pass-suite @diagnostic-service @service-call @local-api
  Scenario: Diagnostic service call evidence
    * RUN "diagnosticHealth" SERVICE CALL: %health-full-url
      | pkb_callpath    | endpoint              |
      | classpath:calls | http://127.0.0.1:8765 |
    * , verify "<diagnosticHealth.RESPONSE.statusCode>" equals "200"

  @diagnostic-validation @diagnostic-pass-suite @diagnostic-component @browser @local-site
  Scenario: Diagnostic component invocation evidence
    * RUN COMPONENT SCENARIO: Save customer component
      | pkb_componentpath | customerName   | tier    |
      | classpath:features | Diagnostic Ava | Premium |
    * , ensure "Saved Customer: Diagnostic Ava | Premium" Text is displayed

  @diagnostic-validation @diagnostic-pass-suite @diagnostic-browser-baseline @browser @local-site
  Scenario: Diagnostic browser baseline
    * navigate to: URL.forms
    * , ensure "Forms Playground" Text is displayed
    * , enter "Ava" in the "First Name" Textbox
    * , enter "Baseline" in the "Last Name" Textbox
    * , select "Premium" in the "Account Type" Dropdown
    * capture diagnostic screenshot "diagnostic-browser-baseline"
    * , ensure "Name: Ava Baseline" Text is displayed
    * , ensure "Account Type: Premium" Text is displayed

  @diagnostic-validation @diagnostic-pass-suite @diagnostic-browser-variant @browser @local-site
  Scenario: Diagnostic browser variant
    * navigate to: URL.forms
    * , ensure "Forms Playground" Text is displayed
    * , enter "Mia" in the "First Name" Textbox
    * , enter "Variant" in the "Last Name" Textbox
    * , select "Standard" in the "Account Type" Dropdown
    * capture diagnostic screenshot "diagnostic-browser-variant"
    * , ensure "Name: Mia Variant" Text is displayed
    * , ensure "Account Type: Standard" Text is displayed

  @diagnostic-validation @diagnostic-browser-fail @browser @local-site
  Scenario: Diagnostic browser failure evidence
    * navigate to: URL.forms
    * , ensure "Forms Playground" Text is displayed
    * , enter "Failure" in the "First Name" Textbox
    * capture diagnostic screenshot "diagnostic-before-intentional-failure"
    * , ensure "DIAGNOSTIC INTENTIONAL FAILURE SENTINEL" Text is displayed

  @diagnostic-validation @diagnostic-soft-fail
  Scenario: Diagnostic soft assertion failure evidence
    * emit diagnostic log markers "diagnostic-soft-failure"
    * , verify "actual-soft" equals "expected-soft"

  @diagnostic-validation @diagnostic-hard-fail
  Scenario: Diagnostic hard assertion failure evidence
    * emit diagnostic log markers "diagnostic-hard-failure"
    * , ensure "actual-hard" equals "expected-hard"
