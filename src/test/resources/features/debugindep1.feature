Feature:


  Scenario Outline: SO A , <Scenario>  - <arg1>
#    Then I should see debug outputlzz
    Then I should see debug outputlzz
    *  Scenario:
      | Scenario Tags  | A     | B |
      | @sob<Scenario> | ERROR | s |
#      | @zzs9          | z     | 3 |
    Given I am running a testlzz <arg1> and <Scenario>
    Examples:
      | Scenario | arg1  |
      | 1        | val1a |
      | 2        | val2a |
      | 3        | val3a |


  Scenario Outline: SO B <arg1> <Scenario Tags>
#    Then I should see debug outputlzz
    Then I should see debug outputlzz
    *  Scenario:
      | Scenario Tags | A     | B |
      | @zzs8         | ERROR | s |
      | @zzs9         | z     | 3 |
    Given I am running a testlzz <arg1> and <Scenario Tags>
    Examples:
      | Scenario Tags | arg1 |
      | @sob1         | val1 |
      | @sob2         | val2 |


  @zzs8
  Scenario Outline: Component CCC 8 <Scenario> <arg1> <A> <B>
    Given I am running a testlzz EwRROR and <arg1>
#    Given I am running a testlzz 5 and 6
#    Given I am running a testlzz 3 and 4
#    Given I am running a testlzz 5 and 6
    Given I am running a testlzz bbb<A> and <B>
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz 3 and 4
    Given I am running a testlzz ccc5 and 6
    Examples:
      | Scenario | arg1 |
      | s1       | val1 |
      | s2       | val2 |


  @zzs9
  Scenario: Component BBB
    Given I am running a testlzz EwRROR and 4
#    Given I am running a testlzz 5 and 6
#    Given I am running a testlzz 3 and 4
#    Given I am running a testlzz 5 and 6
    Given I am running a testlzz bbb<A> and <B>
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz <A> and <B>
#    Given I am running a testlzz 3 and 4
    Given I am running a testlzz ccc5 and 6


  Scenario Outline: aawdqq <Scenario>
    Given I see colour
    Given I see <C>
    Given I see color
    Given Do you like cucumber?
    Examples:
      | Scenario | C      |
      | 1        | colour |
      | 1        | color  |
      | 1        | colors |
      | 1        | color  |


  Scenario: qq
    Given I see colour
    Given I see color
    Given Do you like cucumber?


  Scenario Outline: dsdf
    When I am running a testlzz xxx<A> and <B>
    Given I have the following string: `<(aaa)>`
    Examples:
      | Scenario | (aaa) |
      | 1        | B     |

  Scenario: ss3423
    Given I have the following string: `This i\"s \'a "quoted" string`
  @IF:          *    | I am running a testlzz META and <B>
    When I am running a testlzz xxx<A> and <B>


  Scenario: ss
    Given I have the following string list: ["item \"one\"", 'item \'t"wo\'', `item \`thr"ee\``]
    Given I have the following string list: ["item \"one\"", 'item \'t\"wo\'', `item \`thr"ee\``]
#    When I am running a testlzz xxx<A> and <B>
    Given I have the following string: "This i\`s\' a \"quoted\" string"
    Given I have the following string: 'This i\"s\` a \'quoted\' string'

#    Given I have the following string list: ["item one", 'item two', `item three`]
#    Given I have the following string list: ["item \"one\"", 'item \'two\'', `item \`three\``]
#    Given I have the following string list: ["item, one", 'item, two', `item, three`]
#    Given I have the following string list: ["item, one", "item \"two\"", "item three", "" ]
#    Given I have the following string list: []




  Scenario: Mai22
    Then I should see debug outputlzz
    Given I am running a testlzz xxx<A> and <B>
      | a | b |
      | 1 | 2 |
    Then I should see debug outputlzz

  @zzs1 @dfs
  Scenario: Main AAA
#    Then I should see debug outputlzz
    Then I should see debug outputlzz
    *  Scenario:
      | Scenario Tags | A     | B |
      | @zzs9         | SOsFT | s |
      | @zzs9         | z     | 3 |
    Given I am running a testlzz xxx<A> and <B>
#    Given I am running a testlzz fff and gggg


#
#  @dfs
#  Scenario: coord1
#  dss
#   Given I am running a testlzz 11ERRsORszzzzzzzzzzzzzzzzzzzzz and 6
#  Given the user is at coordinates I am running a testlzz SOFsT and 6
#  Given I am running a testlzz 33ERRsORs and 6
#
#  Given I am running a testlzz aa3 and s
#  Given I am running a testlzz bbb<A> and <B>
#  Verify I am running a testlzz ccc5 and 6
#
#  Given I am running a testlzz ccc5 and 6
##    Verify I am running a testlzz ccc5 and 6
#  Given I am running a testlzz ccc5 and 6


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


