@step-override @step-override-bridge
Feature: Pickleball Step Override bridge

  Scenario: Generated Java override can be replaced and removed in one live scenario
    Given BEGIN STEP OVERRIDE BRIDGE TEST
    And STEP OVERRIDE BRIDGE SYNC POINT
    And VERIFY STEP OVERRIDE BRIDGE TEST
