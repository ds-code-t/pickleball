Feature: Addition

  @Tag1
  Scenario: Add two numbers
    Given I have numbers 2 and 3
    When I add them
#    * thrdow error
    Then the result should be 5

#
  @Tag1
  Scenario Outline: outline Add two numbers <A>
    Given I have numbers <A> and 3
    When I add them
    Then print <A>
Examples:
    | A |
    | 2 |