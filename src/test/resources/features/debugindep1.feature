Feature: Using Plugin Steps333 indep

  @zzs1
  Scenario: Main AAA
#    Then I should see debug outputlzz
    Then I should see debug outputlzz
    *  Scenario:
      | Scenario Tags | A     | B |
      | @zzs9         | SOFT | s |
      | @zzs9         | z     | 3 |
    Given I am running a testlzz xxx<A> and <B>
#    Given I am running a testlzz fff and gggg

  @zzs9
  Scenario: Component BBB
    Given I am running a testlzz aa3 and 4
#    Given I am running a testlzz 5 and 6
#    Given I am running a testlzz 3 and 4
#    Given I am running a testlzz 5 and 6
    Given I am running a testlzz bbb<A> and <B>
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz 3 and 4
    Given I am running a testlzz ccc5 and 6




  Scenario: coord1
    Given I am running a testlzz 11ERRsORs and 6
    Given the user is at coordinates I am running a testlzz SOFT and 6
    Given I am running a testlzz 33ERRsORs and 6




#  Scenario: aaaaasdve4
#    * DocString Test
##  """
##  This is the content of the DocString.
##  """
#
#  @tagss
#  Scenario: DDaaa w
#    Given DDDqq 'qqqq1' and 'qqqqq1'
##      | Scaenario | A |
##      | 1         | 2 |
#    Given I am running a testlzz qqqq1 and qqqqq1
#
#
#
#
##  @tagz
##  Scenario: sDebug test using plugin steps
##    Given I am running a test
##    When I execute a step
##    Then I should see debug output
#
#  @tagss
#  Scenario: indep qsDebug test using plugin stepsaa vb3
#    Given I am running a testlzz qqqq1 and qqqqq1
#    Given I am running a testlzz qqqq1 and qqqqq1
#    Given I am running a testlzz qqqq1 and qqqqq1
#
#  @tagz
#  Scenario: GGGGGGGGGGGGG
#    Given I am running a testlzz AAAA1 and BBBBBB1
#    Given I am running a testlzz AAAA1 and BBBBBB1
#    Then Scenario:
#    Given I am running a testlzz AAAA2 and BBBBBB2
#    Then I should see debug outputlzz
#
##  @zzs
#  Scenario: HHHHHHHaaa
#    Given I am running a testlzz fff and gggg
#    Given I am running a testlzz fff and gggg
#
#
#  @zzs1
#  Scenario: HHHHHHHbbb
#    But I should see debug outputlzz
##Buts I should see debug outputlzz
##  If I should see debug outputlzz
##    Then I should see debug outputlzz
##  Étant donné I should see debug outputlzz
##    Then I should see debug outputlzz
#
#
#  @qq1
#  Scenario Outline: gggaaaas <Scenario Tags>
#    Then I should see debug outputlzz
#    Then I should see debug outputlzz
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz fff and gggg
#
#    Examples:
#      | Scenario Tagsg | A  | Bg | A  |
#      | @zzs 1         | 11 | 22 | 33 |
##      | @zzs  2       | 44 | 55 | |
##
##
##    Examples:
##      | Scenario Tags2g | A2g  | g2  |
##      | @zzs 1        | 22 | 33 |
##      | @zzs  2       | 44 | 55 |
#
#
#
#@zzs 5
#  Scenario: sds
##    Then I should see debug outputlzz
#    Then I should see debug outputlzz
#    *  Scenario:
#      | Scenario Tags | A | B |
#      | @zzs9         | z | s |
##      | @zzs9         | zzzzzzzzzz |
##    Given I am running a testlzz <A> and <B>
##    Given I am running a testlzz fff and gggg

#  @zzs9
#  Scenario: zzzzz 11
#    Then I should see debug outputlzz
##
##  @zzs9
##  Scenario: cccc 11
##    Then I should see debug outputlzz
#  @zzs9 @priority-500
##  Scenario: qqqqqq <Scenario Tags>
##    Given I am running a testlzz <A> and <B>
#
##  @zzs9 @Priority-1
#    @zzs9 @priority-500
#  Scenario Outline: qqqqqq <Scenario Tags>
##    Then I should see debug outputlzz
##    Then I should see debug outputlzz
#    Given I am running a testlzz <A> and <B>
##    Given I am running a testlzz fff and gggg
#
#    Examples:
#      | Scenario Tags         | A | B |
#      | @zzs  1   @priority-3 | a |   |
##      | @ww  2                | 44 | 55 |
#
##    Examples:
##      | Scenario Tags                | A  | B  |
##      | @zzs  3          @priority-5 | 22 | 33 |
##      | @zzs  4 @priority-1          | 44 | 55 |
##
###      | @zzs  3       | 44 | 55 |
###      | @zzs  4       | 44 | 55 |
###      | @zzs  5       | 44 | 55 |
###      | @zzs  6  @priority-1    | 44 | 55 |
###      | @zzs  7       | 44 | 55 |
##
##
##    Examples:
##
##      | Scenario Tags           | A    | B     |
##
##      | @zzs 5                  | qq22 | zzz33 |
##
##      | @zzs  6   @priority-300 | 44   | 55    |
##      | @zzs  7                 | 44   | 55    |
#
#


