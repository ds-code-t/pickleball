@smoke @calc
Feature: Addition using custom cucumber-core

  @smoke
  Scenario: Add two numbers
    Given a starting total of 0
    When I add 3 and 4
    Then the total should be 7

  @wip
  Scenario: Add negative numbers
    Given a starting total of 0
    When I add -2 and -5
    Then the total should be -7

  Scenario Outline: ddf
    When I add <A> and -5

  Examples:
  | A |
  | 1  |
#  Scenario: conditionals2
#    * IF: 1 + 1 > 5
#  : Then print A
#    * ELSE-IF: 1 + 1 > 5
#  : Then print B
#    * ELSE-IF: 1 + 1 > 5
#  : Then print C
#    * ELSE:
#  : Then print D

#  @test1 @sc1
#  Scenario Outline: conditionals2
#    * IF: 1 + 1 > 5
#  : Then print A
#    * ELSE-IF: 1 + 1 > 5
#  : Then print B
#    * ELSE-IF: 1 + 1 > 5
#  : Then print C
#    * ELSE:
#  : Then print D
#
#    @sc2
#    Examples:
#      | Component Tags | Scenario Tags |
#      | test3          | @test4        |