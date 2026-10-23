# Not part of @all. ScenarioObjectHookTest runs this through the pickleball engine.
@scenario-object
Feature: Scenario object store

  Scenario: the before hook object is visible to the step
    Then the hook object is visible

  Scenario: the next scenario does not see the previous step object
    Then the previous step object is gone
