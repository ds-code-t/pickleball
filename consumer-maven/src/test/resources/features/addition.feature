@smoke @calc
Feature: Addition using custom cucumber-core

  @smoke
  Scenario: Add two numbers
    Given a starting total of 0
    When I add 3 and 4
    Then the total should be 7

#  @wip
  Scenario: Add negative numbers
    Given a starting total of 0
    When I add -2 and -5
    Then the total should be -7
