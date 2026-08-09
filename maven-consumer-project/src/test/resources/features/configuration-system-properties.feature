Feature: Pickleball JVM system-property normalization

  @configuration @quoted-pkb-values
  Scenario: Normalize command-line wrapping quotes from pkb values
    * pkb property "pkb_tags" equals "@quoted-pkb-values"
    * pkb property "pkb_reportingmode" equals "diagnostic"
    * pkb property "pkb_reportretention" equals "all"
    * pkb property "pkb_loglevel" equals "INFO"
    * pkb property "pkb_investigation_id" equals "diag-213-validation"
    * pkb property "pkb_run_purpose" equals "quoted-value-validation"
