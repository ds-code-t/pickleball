Feature: Addition


  @Tag3
  Scenario: aaa
    Given QQQ
    Given QQQ2ss2

  @test1 @sc1 @smoke
  Scenario Outline: conditionals2
    * IF: 1 + 1 < 0
  : Then print A
    * ELSE-IF: 1 + 1 > 0
  : Then print B
    * ELSE-IF: 1 + 1 < 5
  : Then print C
    * ELSE:
  : Then print D

    @sc2
    Examples:
      | Component Tags | Scenario Tags |
      | test3          | @test4        |

#  Scenario: qq
#    Given I have numbers 2 and 3
#    Given QQQ
#    Given QQQ2ss2
#
#  @Tag1
#  Scenario: Add two numbers
#    Given QQQ
#    Given QQQ2ss2
#    Given I have numbers 2 and 3
#    When I add them
##    * thrdow error
#    Then the result should be 5
#
##
#  @Tag1
#  Scenario Outline: outline Add two numbers <A>
##    Given I have numbers <A> and 3
#    When I add them
#    Then print <A>
#    Examples:
#      | A |
#      | 2 |