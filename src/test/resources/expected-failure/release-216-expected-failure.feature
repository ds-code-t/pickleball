# Not part of @all. ExpectedFailureRunnerTest runs this feature through the
# real Cucumber/pickleball runner and requires each scenario to stay failed.
@expected-failure
Feature: Expected failures stay failed and still clean up

  Scenario: Registered-object cleanup runs when the scenario hard-fails
    * RUN EXPECTED FAILURE REGISTER CLEANUP hard
    * FAIL: release 216 hard failure

  Scenario: Registered-object cleanup runs when the scenario soft-fails
    * RUN EXPECTED FAILURE REGISTER CLEANUP failure
    * RUN EXPECTED FAILURE SOFT FAIL

  Scenario: softFail continues the step and runs the soft-failed flag
    * , save "no" as "flagSoft"
    * RUN EXPECTED FAILURE SOFT FAIL
    * RUN EXPECTED FAILURE NOTE CONTINUED
    * RUN IF SCENARIO SOFT FAILED: , save "yes" as "flagSoft"
    * , ensure "<flagSoft>" equals "yes"
    * RUN EXPECTED FAILURE NOTE SOFT FLAG
