Feature: Addition

  @Tag1
  Scenario: Add two numbers
    Given I have numbers 2 and 3
    When I add them
    Then the result should be 5
