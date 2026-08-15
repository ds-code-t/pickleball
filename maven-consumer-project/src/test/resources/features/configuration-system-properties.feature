Feature: Pickleball configuration resolution

  @all @configuration @normal-source-precedence
  Scenario: Resolve normal project configuration from defaults through shared and runner properties
    * pkb property "example.precedence.shared" equals "pickleball.properties"
    * pkb property "example.precedence.local" equals "pickleball.properties"
    * pkb property "example.precedence.runner-property" equals "globalTestProperties"

  @configuration @quoted-pkb-values
  Scenario: Normalize command-line wrapping quotes from pkb values
    * pkb property "pkb_tags" equals "@quoted-pkb-values"
    * pkb property "pkb_reportingmode" equals "diagnostic"
    * pkb property "pkb_reportretention" equals "all"
    * pkb property "pkb_loglevel" equals "INFO"
    * pkb property "pkb_investigation_id" equals "diag-213-validation"
    * pkb property "pkb_run_purpose" equals "quoted-value-validation"

  @configuration @profile-direct-validation
  Scenario: Controlled RunVars determine execution while diagnostic lineage stays separate
    * pkb property "pkb_tags" equals "@profile-direct-validation"
    * pkb property "pkb_browser" equals "CHROME_HEADLESS"
    * pkb property "pkb_reportingmode" equals "diagnostic"
    * pkb property "pkb_reportretention" equals "all"
    * pkb property "pkb_investigation_id" equals "diag-214-run-profile"
    * pkb property "pkb_run_purpose" equals "direct-profile-validation"
    * pkb property "pkb_changed_variables" equals "pkb_browser"

  @configuration @profile-expanded-validation
  Scenario: Expanded controlled RunVars preserve literal member values
    * pkb property "pkb_tags" equals "@profile-expanded-validation"
    * pkb property "pkb_browser" equals "CHROME_HEADLESS"
    * pkb property "pkb_reportingmode" equals "diagnostic"
    * pkb property "pkb_reportretention" equals "all"
    * pkb property "pkb_rp_description" equals "expanded-direct-profile"
    * pkb property "pkb_investigation_id" equals "diag-214-expanded-profile"
    * pkb property "pkb_run_purpose" equals "expanded-profile-validation"
