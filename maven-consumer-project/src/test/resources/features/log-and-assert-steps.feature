@all @regression @log-assert-steps @assert-steps
Feature: Author-facing log and assert steps

  Scenario: TRACE DEBUG INFO and WARN do not fail the scenario
    * TRACE: starting checkout
    * DEBUG: loading cart
    * INFO: starting checkout
    * WARN: retrying payment
    * , save "logged" as "afterInfoLogs"
    * , ensure "<afterInfoLogs>" equals "logged"

  Scenario: ASSERT expression and phrase clauses pass
    * ASSERT: 1 < 2
    * ASSERT: "A" equals "A"
    * ASSERT: 'A' equals 'a'
    * ASSERT: 1 < 2 || false
    * ASSERT: 1 < 2 | "A" equals "A"
    * ASSERT: 1 < 2 || false | "A" equals "A"

  Scenario: SOFT ASSERT success does not fail the scenario
    * SOFT ASSERT: 1 < 2 | "A" equals "A"
    * , save "soft-ok" as "afterSoftSuccess"
    * , ensure "<afterSoftSuccess>" equals "soft-ok"

  @internal-java-checks
  Scenario: Observe ERROR FAIL hard-assert fail-fast and soft-assert failures
    * RUN LOG AND ASSERT JAVA TESTS
